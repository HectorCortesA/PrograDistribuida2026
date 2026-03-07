package main;

import consumer.Consumer;
import producer.Producer;
import storage.StorageService;

import java.util.ArrayList;
import java.util.List;

/**
 * =========================================================
 *  CLASE PRINCIPAL — Orquestador del sistema distribuido
 * =========================================================
 * Simula una arquitectura de 3 máquinas en una sola JVM:
 *
 *   [MÁQUINA-PRODUCTORA-1]  ← hilo independiente
 *   [MÁQUINA-STORAGE]       ← hilo independiente
 *   [MÁQUINA-CONSUMIDORA-1] ← hilo independiente
 *
 * En un entorno real con 3 computadoras, cada clase correría
 * en su propia JVM y se comunicarían por red (sockets / gRPC).
 * Aquí los hilos simulan esas máquinas independientes.
 *
 * La consola muestra exactamente qué hace cada "máquina":
 *  - PRODUCTORA : genera el vector y lo envía al Storage
 *  - STORAGE    : recibe/entrega vectores y almacena resultados
 *  - CONSUMIDORA: toma el vector, suma los 3 números, devuelve resultado
 *
 * El sistema se detiene automáticamente al llegar a 1 000 000 resultados.
 */
public class MainSystem {

    /** Número de máquinas productoras (1 para consola legible; aumentar para velocidad). */
    private static final int NUM_PRODUCERS = 1;

    /** Número de máquinas consumidoras (1 para consola legible; aumentar para velocidad). */
    private static final int NUM_CONSUMERS = 1;

    public static void main(String[] args) throws InterruptedException {

        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║     SISTEMA DISTRIBUIDO: PRODUCTOR → STORAGE → CONSUMIDOR     ║");
        System.out.println("║     Programación Distribuida y Aplicada — Simulación 3 VMs    ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Máquinas productoras : %-3d (hilos simulando VMs remotas)    ║%n", NUM_PRODUCERS);
        System.out.printf( "║  Máquinas consumidoras: %-3d (hilos simulando VMs remotas)    ║%n", NUM_CONSUMERS);
        System.out.printf( "║  Límite del sistema   : %,d resultados                  ║%n", StorageService.MAX_RESULTS);
        System.out.println("║  Función matemática   : f(v) = v[0] + v[1] + v[2]  (suma)    ║");
        System.out.println("║  Comunicación         : RPC simulado (en prod.: sockets/gRPC) ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("  LEYENDA DE CONSOLA:");
        System.out.println("  [MÁQUINA-PRODUCTORA-N]  → acciones de la VM productora");
        System.out.println("  [MÁQUINA-STORAGE]        → acciones de la VM storage");
        System.out.println("  [MÁQUINA-CONSUMIDORA-N] → acciones de la VM consumidora");
        System.out.println();
        System.out.println("══════════════════════════ INICIO DE EJECUCIÓN ════════════════════");
        System.out.println();

        // ── 1. Crear la máquina Storage ──────────────────────────────────
        StorageService storage = new StorageService();
        System.out.println();

        // ── 2. Lanzar máquinas Consumidoras ──────────────────────────────
        List<Thread>   consumerThreads = new ArrayList<>();
        List<Consumer> consumers       = new ArrayList<>();

        for (int i = 1; i <= NUM_CONSUMERS; i++) {
            Consumer consumer = new Consumer(storage, i);
            consumers.add(consumer);
            Thread t = new Thread(consumer, "Consumidora-" + i);
            consumerThreads.add(t);
            t.start();
        }

        // ── 3. Lanzar máquinas Productoras ───────────────────────────────
        List<Thread>   producerThreads = new ArrayList<>();
        List<Producer> producers       = new ArrayList<>();

        for (int i = 1; i <= NUM_PRODUCERS; i++) {
            Producer producer = new Producer(storage, i);
            producers.add(producer);
            Thread t = new Thread(producer, "Productora-" + i);
            producerThreads.add(t);
            t.start();
        }

        // ── 4. Esperar hasta que el Storage señale el apagado ─────────────
        //       (se activa cuando se alcanzan MAX_RESULTS resultados)
        while (!storage.isShutdown()) {
            Thread.sleep(500);
        }

        // ── 5. Detener todos los hilos limpiamente ────────────────────────
        System.out.println("\n[SISTEMA] Señal de parada recibida. Deteniendo todas las máquinas...");
        for (Thread t : producerThreads) t.interrupt();
        for (Thread t : consumerThreads) t.interrupt();
        for (Thread t : producerThreads) t.join(10_000);
        for (Thread t : consumerThreads) t.join(10_000);

        // ── 6. Reporte final del Storage ──────────────────────────────────
        System.out.println("\n══════════════════════════ FIN DE EJECUCIÓN ═══════════════════════");
        storage.printFinalReport();

        // ── 7. Estadísticas por máquina ───────────────────────────────────
        System.out.println("\n--- Estadísticas por máquina ---");
        for (int i = 0; i < producers.size(); i++) {
            Producer p = producers.get(i);
            System.out.printf("  MÁQUINA-PRODUCTORA-%d  → Aceptados: %,d | Rechazados: %,d%n",
                    i + 1, p.getVectorsAccepted(), p.getVectorsRejected());
        }
        for (int i = 0; i < consumers.size(); i++) {
            Consumer c = consumers.get(i);
            System.out.printf("  MÁQUINA-CONSUMIDORA-%d → Procesados: %,d%n",
                    i + 1, c.getVectorsProcessed());
        }

        System.out.println("\n[SISTEMA] Ejecución finalizada correctamente.");
    }
}
