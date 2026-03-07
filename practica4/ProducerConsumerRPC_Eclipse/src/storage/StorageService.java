package storage;

import rpc.StorageRPC;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * =========================================================
 *  STORAGE - Nivel 2 de la Arquitectura de 3 Niveles
 * =========================================================
 * Simula una MÁQUINA STORAGE independiente que actúa como
 * intermediario central entre Productor y Consumidor.
 *
 * Muestra en consola el estado de sus dos estructuras en cada operación:
 *
 *  COLA DE VECTORES  : recibe vectores del Productor (pushVector RPC)
 *                      entrega vectores al Consumidor (pullVector RPC)
 *
 *  LISTA DE RESULTADOS: recibe resultados del Consumidor (pushResult RPC)
 *                       calcula la suma acumulada
 *
 * Salida de consola esperada:
 *  [MÁQUINA-STORAGE] ← RPC pushVector([312, 87, 654]) recibido
 *  [MÁQUINA-STORAGE]   ✓ ACEPTADO y encolado
 *  [MÁQUINA-STORAGE]   ┌── COLA DE VECTORES (pendientes): 3 elemento(s)
 *  [MÁQUINA-STORAGE]   └── LISTA DE RESULTADOS           : 1 elemento(s)
 */
public class StorageService implements StorageRPC {

    /** Número máximo de resultados antes de detener el sistema. */
    public static final int MAX_RESULTS = 1_000_000;

    /**
     * COLA 1: Cola bloqueante de vectores (Productor → Consumidor).
     * BlockingQueue garantiza acceso concurrente seguro sin locks explícitos.
     * Capacidad máxima: 10 000 para evitar uso excesivo de memoria.
     */
    private final BlockingQueue<int[]> vectorQueue;

    /**
     * COLA 2 / LISTA: Lista sincronizada de resultados (Consumidor → Storage).
     * Almacena todos los resultados hasta llegar a MAX_RESULTS.
     */
    private final List<Long> resultList;

    /**
     * Historial de vectores ya vistos: garantiza unicidad global.
     * Clave: "v0,v1,v2" — verificación O(1) por hash.
     */
    private final Set<String> vectorHistory;

    /** Contador atómico de resultados (hilo-seguro sin synchronized). */
    private final AtomicInteger resultCount;

    /** Suma acumulada de todos los resultados. */
    private final AtomicLong totalSum;

    /** Bandera de apagado: volatile para visibilidad inmediata entre hilos. */
    private volatile boolean shutdown;

    private long startTime;
    private long endTime;

    /**
     * Constructor: inicializa todas las estructuras del Storage.
     */
    public StorageService() {
        this.vectorQueue   = new LinkedBlockingQueue<>(10_000);
        this.resultList    = Collections.synchronizedList(new ArrayList<>());
        this.vectorHistory = Collections.synchronizedSet(new HashSet<>());
        this.resultCount   = new AtomicInteger(0);
        this.totalSum      = new AtomicLong(0L);
        this.shutdown      = false;
        this.startTime     = System.currentTimeMillis();

        System.out.println("┌─────────────────────────────────────────────────────");
        System.out.println("│ [MÁQUINA-STORAGE] INICIADA");
        System.out.println("│   Estructura 1 : COLA DE VECTORES    (BlockingQueue<int[]>)");
        System.out.println("│   Estructura 2 : LISTA DE RESULTADOS (List<Long> sincronizada)");
        System.out.printf( "│   Límite       : %,d resultados%n", MAX_RESULTS);
        System.out.println("└─────────────────────────────────────────────────────");
    }

    // =========================================================
    //  MÉTODOS RPC
    // =========================================================

    /**
     * RPC pushVector — llamado por el Productor para depositar un vector.
     *
     * 1. Verifica que el vector no sea duplicado (usando vectorHistory).
     * 2. Si es nuevo: lo encola en vectorQueue.
     * 3. Muestra en consola el estado de ambas estructuras.
     *
     * @param vector int[3] generado por el Productor
     * @return true si fue aceptado, false si era duplicado
     */
    @Override
    public synchronized boolean pushVector(int[] vector) {
        if (shutdown) return false;

        String key = vector[0] + "," + vector[1] + "," + vector[2];

        System.out.printf("[MÁQUINA-STORAGE] ← RPC pushVector([%d, %d, %d]) recibido%n",
                vector[0], vector[1], vector[2]);

        if (vectorHistory.contains(key)) {
            System.out.println("[MÁQUINA-STORAGE]   ✗ RECHAZADO: vector ya existe en historial");
            return false;
        }

        // Vector nuevo: aceptar
        vectorHistory.add(key);
        vectorQueue.offer(vector);

        System.out.println("[MÁQUINA-STORAGE]   ✓ ACEPTADO → encolado en COLA DE VECTORES");
        System.out.printf("[MÁQUINA-STORAGE]   ┌── COLA DE VECTORES (pendientes) : %d elemento(s)%n",
                vectorQueue.size());
        System.out.printf("[MÁQUINA-STORAGE]   └── LISTA DE RESULTADOS            : %d elemento(s)%n",
                resultCount.get());
        return true;
    }

    /**
     * RPC pullVector — llamado por el Consumidor para obtener el siguiente vector.
     *
     * Bloquea hasta que haya un vector disponible o el sistema se detenga.
     * Muestra en consola qué vector se entrega y el estado de las estructuras.
     *
     * @return int[3] el siguiente vector, o null si el sistema se apagó
     * @throws InterruptedException si el hilo es interrumpido
     */
    @Override
    public int[] pullVector() throws InterruptedException {
        while (!shutdown) {
            int[] vector = vectorQueue.poll(200, TimeUnit.MILLISECONDS);
            if (vector != null) {
                System.out.printf("[MÁQUINA-STORAGE]   → RPC pullVector() entregando [%d, %d, %d]%n",
                        vector[0], vector[1], vector[2]);
                System.out.printf("[MÁQUINA-STORAGE]   ┌── COLA DE VECTORES (pendientes) : %d elemento(s)%n",
                        vectorQueue.size());
                System.out.printf("[MÁQUINA-STORAGE]   └── LISTA DE RESULTADOS            : %d elemento(s)%n",
                        resultCount.get());
                return vector;
            }
        }
        return vectorQueue.poll();
    }

    /**
     * RPC pushResult — llamado por el Consumidor para depositar un resultado.
     *
     * 1. Agrega el resultado a la LISTA DE RESULTADOS.
     * 2. Actualiza la suma acumulada.
     * 3. Muestra el estado de ambas estructuras.
     * 4. Si se alcanzó MAX_RESULTS, activa el apagado.
     *
     * @param result resultado calculado por el Consumidor
     */
    @Override
    public void pushResult(long result) {
        if (shutdown) return;

        resultList.add(result);
        totalSum.addAndGet(result);
        int count = resultCount.incrementAndGet();

        System.out.printf("[MÁQUINA-STORAGE] ← RPC pushResult(%d) recibido%n", result);
        System.out.printf("[MÁQUINA-STORAGE]   ✓ Guardado en LISTA DE RESULTADOS%n");
        System.out.printf("[MÁQUINA-STORAGE]   ┌── COLA DE VECTORES (pendientes) : %d elemento(s)%n",
                vectorQueue.size());
        System.out.printf("[MÁQUINA-STORAGE]   └── LISTA DE RESULTADOS            : %d elemento(s) " +
                "| suma acumulada: %,d%n", count, totalSum.get());

        // Reporte de progreso cada 100 000 resultados
        if (count % 100_000 == 0) {
            System.out.println("[MÁQUINA-STORAGE] ══════════════════════════════════════════════════");
            System.out.printf( "[MÁQUINA-STORAGE] ▶ PROGRESO: %,d / %,d resultados completados%n",
                    count, MAX_RESULTS);
            System.out.printf( "[MÁQUINA-STORAGE]   Suma acumulada hasta ahora: %,d%n", totalSum.get());
            System.out.println("[MÁQUINA-STORAGE] ══════════════════════════════════════════════════");
        }

        // Condición de parada
        if (count >= MAX_RESULTS) {
            endTime = System.currentTimeMillis();
            shutdown();
        }
    }

    /** @return cantidad total de resultados almacenados */
    @Override
    public int getResultCount() { return resultCount.get(); }

    /** @return suma total de todos los resultados */
    @Override
    public long getTotalSum() { return totalSum.get(); }

    /** Activa el apagado del sistema y notifica a todos los hilos. */
    @Override
    public synchronized void shutdown() {
        if (!shutdown) {
            shutdown = true;
            endTime = System.currentTimeMillis();
            System.out.println("[MÁQUINA-STORAGE] ⚑ APAGADO ACTIVADO — límite de resultados alcanzado.");
        }
    }

    /** @return true si el sistema fue apagado */
    @Override
    public boolean isShutdown() { return shutdown; }

    /** @return lista inmutable de resultados almacenados */
    @Override
    public List<Long> getResults() {
        return Collections.unmodifiableList(resultList);
    }

    // =========================================================
    //  REPORTE FINAL
    // =========================================================

    /**
     * Calcula e imprime el reporte final con:
     *  - Total de vectores producidos (únicos)
     *  - Total de resultados almacenados
     *  - SUMA TOTAL de todos los resultados
     *  - Tiempo total de ejecución
     */
    public void printFinalReport() {
        long elapsed = (endTime > 0 ? endTime : System.currentTimeMillis()) - startTime;
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║         REPORTE FINAL — MÁQUINA STORAGE              ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf( "║  Vectores únicos producidos  : %,15d        ║%n", vectorHistory.size());
        System.out.printf( "║  Resultados en lista         : %,15d        ║%n", resultCount.get());
        System.out.printf( "║  ▶ SUMA TOTAL DE RESULTADOS  : %,15d        ║%n", totalSum.get());
        System.out.printf( "║  Tiempo de ejecución         : %,12d ms     ║%n", elapsed);
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }
}
