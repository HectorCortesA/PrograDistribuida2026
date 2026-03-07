package consumer;

import rpc.StorageRPC;

/**
 * =========================================================
 *  CONSUMIDOR - Nivel 3 de la Arquitectura de 3 Niveles
 * =========================================================
 * Simula una MÁQUINA CONSUMIDORA independiente.
 *
 * En la consola se muestra paso a paso:
 *   1. La llamada RPC al Storage para obtener un vector
 *   2. El vector recibido
 *   3. El cálculo realizado (suma de los 3 números)
 *   4. El resultado enviado de vuelta al Storage
 *
 * Función matemática:
 *   f(v) = v[0] + v[1] + v[2]   (suma simple de los 3 elementos)
 *
 * En un sistema real con 3 máquinas, este proceso correría
 * en una computadora distinta y obtendría los vectores del
 * Storage por red (sockets / gRPC). Aquí se simula con hilos.
 *
 * Salida de consola esperada:
 *  [MÁQUINA-CONSUMIDORA-1]   Llamada RPC       : pullVector()
 *  [MÁQUINA-CONSUMIDORA-1] ◀ Vector recibido   : [312, 87, 654]
 *  [MÁQUINA-CONSUMIDORA-1]   Procesando        : 312 + 87 + 654 = 1053
 *  [MÁQUINA-CONSUMIDORA-1]   Llamada RPC       : pushResult(1053)
 *  [MÁQUINA-CONSUMIDORA-1]   Storage confirmó  : ✓ resultado almacenado
 */
public class Consumer implements Runnable {

    /** Referencia al stub RPC del Storage (en producción: cliente de red). */
    private final StorageRPC storageStub;

    /** Identificador de esta máquina consumidora. */
    private final int consumerId;

    /** Nombre de display en consola. */
    private final String name;

    /** Contador de vectores procesados exitosamente. */
    private long vectorsProcessed;

    /**
     * Constructor del Consumidor.
     *
     * @param storageStub referencia RPC al Storage
     * @param consumerId  identificador de esta máquina
     */
    public Consumer(StorageRPC storageStub, int consumerId) {
        this.storageStub      = storageStub;
        this.consumerId       = consumerId;
        this.name             = "MÁQUINA-CONSUMIDORA-" + consumerId;
        this.vectorsProcessed = 0;
    }

    /**
     * Ciclo principal del Consumidor.
     *
     * Cada iteración:
     *  1. Solicita un vector al Storage (RPC pullVector — bloqueante si la cola está vacía).
     *  2. Muestra el vector recibido.
     *  3. Aplica la función matemática (suma de los 3 números).
     *  4. Muestra el cálculo detallado.
     *  5. Envía el resultado al Storage (RPC pushResult).
     *  6. Confirma el almacenamiento.
     */
    @Override
    public void run() {
        System.out.println("┌─────────────────────────────────────────────────────");
        System.out.println("│ [" + name + "] INICIADA");
        System.out.println("│   Rol    : Consumir vectores, calcular suma y devolver resultado");
        System.out.println("│   Origen : Storage (via RPC → pullVector)");
        System.out.println("│   Destino: Storage (via RPC → pushResult)");
        System.out.println("└─────────────────────────────────────────────────────");

        while (!storageStub.isShutdown()) {
            try {
                // ── PASO 1: Pedir un vector al Storage (RPC) ─────────────
                System.out.printf("[%s]   Llamada RPC       : pullVector()  " +
                        "(esperando vector...)%n", name);

                int[] vector = storageStub.pullVector();

                // pullVector puede retornar null si el sistema se apagó
                if (vector == null) break;

                // ── PASO 2: Mostrar el vector recibido ───────────────────
                System.out.printf("[%s] ◀ Vector recibido   : [%d, %d, %d]%n",
                        name, vector[0], vector[1], vector[2]);

                // ── PASO 3: Aplicar función matemática y mostrar cálculo ─
                long result = applyMathFunction(vector);

                System.out.printf("[%s]   Procesando        : %d + %d + %d = %d%n",
                        name, vector[0], vector[1], vector[2], result);

                // ── PASO 4: Enviar resultado al Storage (RPC) ────────────
                System.out.printf("[%s]   Llamada RPC       : pushResult(%d)%n",
                        name, result);

                storageStub.pushResult(result);

                vectorsProcessed++;

                System.out.printf("[%s]   Storage confirmó  : ✓ resultado almacenado " +
                        "(procesados: %,d)%n", name, vectorsProcessed);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // ── Mensaje de cierre ──────────────────────────────────────
        System.out.println("┌─────────────────────────────────────────────────────");
        System.out.printf( "│ [%s] DETENIDA%n", name);
        System.out.printf( "│   Vectores procesados: %,d%n", vectorsProcessed);
        System.out.println("└─────────────────────────────────────────────────────");
    }

    // =========================================================
    //  FUNCIÓN MATEMÁTICA
    // =========================================================

    /**
     * Función matemática aplicada al vector recibido.
     *
     * Fórmula: f(v) = v[0] + v[1] + v[2]
     * (Suma simple de los 3 elementos del vector)
     *
     * Ejemplo:
     *   vector = [312, 87, 654]
     *   f(v)   = 312 + 87 + 654 = 1053
     *
     * @param vector arreglo de 3 enteros en [1, 1000]
     * @return suma de los 3 elementos (long para consistencia con el Storage)
     */
    private long applyMathFunction(int[] vector) {
        return (long) vector[0] + vector[1] + vector[2];
    }

    // Getter de estadísticas
    public long getVectorsProcessed() { return vectorsProcessed; }
}
