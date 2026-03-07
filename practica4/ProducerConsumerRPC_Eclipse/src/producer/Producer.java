package producer;

import rpc.StorageRPC;

import java.util.Random;

/**
 * =========================================================
 *  PRODUCTOR - Nivel 1 de la Arquitectura de 3 Niveles
 * =========================================================
 * Simula una MÁQUINA PRODUCTORA independiente.
 *
 * En la consola se muestra paso a paso:
 *   1. El vector de 3 números que genera
 *   2. La llamada RPC que hace al Storage
 *   3. La respuesta que recibe del Storage
 *
 * En un sistema real con 3 máquinas, este proceso correría
 * en una computadora distinta y se comunicaría con el Storage
 * por red (sockets / gRPC). Aquí se simula con hilos.
 *
 * Salida de consola esperada:
 *  [MÁQUINA-PRODUCTORA-1] ▶ Vector generado    : [312, 87, 654]
 *  [MÁQUINA-PRODUCTORA-1]   Llamada RPC        : pushVector([312, 87, 654])
 *  [MÁQUINA-PRODUCTORA-1]   Respuesta Storage  : ✓ ACEPTADO
 */
public class Producer implements Runnable {

    /** Referencia al stub RPC del Storage (en producción: cliente de red). */
    private final StorageRPC storageStub;

    /** Generador de números aleatorios. */
    private final Random random;

    /** Identificador de esta máquina productora. */
    private final int producerId;

    /** Nombre de display en consola. */
    private final String name;

    /** Vectores aceptados por el Storage. */
    private long vectorsAccepted;

    /** Vectores rechazados (duplicados). */
    private long vectorsRejected;

    /**
     * Constructor del Productor.
     *
     * @param storageStub referencia RPC al Storage
     * @param producerId  identificador de esta máquina
     */
    public Producer(StorageRPC storageStub, int producerId) {
        this.storageStub    = storageStub;
        this.producerId     = producerId;
        this.name           = "MÁQUINA-PRODUCTORA-" + producerId;
        this.random         = new Random();
        this.vectorsAccepted = 0;
        this.vectorsRejected = 0;
    }

    /**
     * Ciclo principal del Productor.
     *
     * Cada iteración:
     *  1. Genera un vector de 3 números distintos en [1, 1000].
     *  2. Muestra el vector en consola.
     *  3. Llama pushVector() en el Storage (RPC).
     *  4. Muestra la respuesta recibida del Storage.
     */
    @Override
    public void run() {
        System.out.println("┌─────────────────────────────────────────────────────");
        System.out.println("│ [" + name + "] INICIADA");
        System.out.println("│   Rol     : Generar vectores de 3 números únicos en [1, 1000]");
        System.out.println("│   Destino : Storage (via RPC → pushVector)");
        System.out.println("└─────────────────────────────────────────────────────");

        while (!storageStub.isShutdown()) {

            // ── PASO 1: Generar el vector aleatorio ──────────────────
            int[] vector = generateUniqueVector();

            System.out.printf("[%s] ▶ Vector generado    : [%d, %d, %d]%n",
                    name, vector[0], vector[1], vector[2]);

            // ── PASO 2: Llamar al Storage vía RPC ────────────────────
            System.out.printf("[%s]   Llamada RPC        : pushVector([%d, %d, %d])%n",
                    name, vector[0], vector[1], vector[2]);

            boolean accepted = storageStub.pushVector(vector);

            // ── PASO 3: Mostrar respuesta del Storage ─────────────────
            if (accepted) {
                vectorsAccepted++;
                System.out.printf("[%s]   Respuesta Storage : ✓ ACEPTADO  " +
                        "(total aceptados: %,d)%n", name, vectorsAccepted);
            } else {
                vectorsRejected++;
                System.out.printf("[%s]   Respuesta Storage : ✗ RECHAZADO " +
                        "(vector duplicado, generando otro...)%n", name);
            }
        }

        // ── Mensaje de cierre ──────────────────────────────────────
        System.out.println("┌─────────────────────────────────────────────────────");
        System.out.printf( "│ [%s] DETENIDA%n", name);
        System.out.printf( "│   Vectores aceptados : %,d%n", vectorsAccepted);
        System.out.printf( "│   Vectores rechazados: %,d (duplicados)%n", vectorsRejected);
        System.out.println("└─────────────────────────────────────────────────────");
    }

    // =========================================================
    //  MÉTODO PRIVADO: generación del vector
    // =========================================================

    /**
     * Genera un vector de 3 números DISTINTOS entre sí en el rango [1, 1000].
     *
     * Usa selección sin reemplazo: mientras el número aleatorio ya esté
     * marcado como usado dentro del vector, genera otro.
     *
     * @return int[3] con 3 valores únicos en [1, 1000]
     */
    private int[] generateUniqueVector() {
        int[] vector = new int[3];
        boolean[] used = new boolean[1001]; // índices válidos: 1..1000

        for (int i = 0; i < 3; i++) {
            int num;
            do {
                num = random.nextInt(1000) + 1; // [1, 1000]
            } while (used[num]);
            used[num] = true;
            vector[i] = num;
        }
        return vector;
    }

    // Getters de estadísticas
    public long getVectorsAccepted() { return vectorsAccepted; }
    public long getVectorsRejected() { return vectorsRejected; }
}
