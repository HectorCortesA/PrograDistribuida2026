package com.p2p.monitor;

import com.p2p.cache.LocalCache;
import com.p2p.consensus.ConsensusManager;
import com.p2p.metadata.FileMetadata;
import com.p2p.metadata.MetadataStore;
import com.p2p.shared.LogRegistry;
import com.p2p.utils.ThreadManager;

import java.util.concurrent.TimeUnit;

public class TTLMonitor {

    private final LocalCache        localCache;
    private final MetadataStore     metadataStore;
    private final ThreadManager     threadManager;
    private final ConsensusManager  consensusManager; // ← para iniciar votación al expirar
    private final LogRegistry       logRegistry;
    private boolean running;

    public TTLMonitor(LocalCache localCache, MetadataStore metadataStore,
                      ThreadManager threadManager, ConsensusManager consensusManager) {
        this.localCache       = localCache;
        this.metadataStore    = metadataStore;
        this.threadManager    = threadManager;
        this.consensusManager = consensusManager;
        this.logRegistry      = new LogRegistry();
        this.running          = true;
    }

    public void start() {
        logRegistry.info("TTLMonitor", "Monitor TTL iniciado");

        // Cada 1 min: limpiar caché expirada
        threadManager.getScheduler().scheduleAtFixedRate(
                this::checkExpiredEntries, 1, 1, TimeUnit.MINUTES);

        // Cada 5 min: revisar metadatos expirados
        threadManager.getScheduler().scheduleAtFixedRate(
                this::checkMetadataExpiration, 5, 5, TimeUnit.MINUTES);
    }

    private void checkExpiredEntries() {
        int before = localCache.size();
        localCache.cleanup();
        int removed = before - localCache.size();
        if (removed > 0)
            logRegistry.info("TTLMonitor", "Eliminadas " + removed + " entradas expiradas de caché");
    }

    private void checkMetadataExpiration() {
        int expiredCount = 0;

        for (FileMetadata metadata : metadataStore.getAllMetadata()) {
            // ── TTL = 0 (FOREVER): NUNCA expirar ni actualizar ──
            if (metadata.isForever()) continue;

            if (metadata.isExpired()) {
                handleExpiredFile(metadata);
                expiredCount++;
            }
        }

        if (expiredCount > 0)
            logRegistry.info("TTLMonitor", "Procesados " + expiredCount + " metadatos expirados");
    }

    /**
     * Al detectar un archivo expirado:
     * 1. Inicia consenso distribuido para verificar si algún peer lo tiene.
     *    - Si ninguno lo tiene → se borra (lo ejecuta ConsensusManager).
     *    - Si alguno lo tiene → se actualiza el dueño (lo maneja ConsensusManager).
     * 2. Elimina la entrada de caché local mientras se resuelve.
     */
    private void handleExpiredFile(FileMetadata metadata) {
        String filename = metadata.getFilename();
        String oldOwner = metadata.getOwner();

        logRegistry.warning("TTLMonitor",
                "Archivo expirado: " + filename + " (dueño anterior: " + oldOwner + ")");

        // Eliminar del caché local mientras el consenso resuelve
        localCache.remove(filename);

        // Iniciar consenso distribuido
        logRegistry.info("TTLMonitor",
                "Iniciando consenso para verificar disponibilidad de: " + filename);
        consensusManager.initiateFileRemovalConsensus(filename);
    }

    public void updateTTL(String filename, long newTTL) {
        FileMetadata metadata = metadataStore.getMetadata(filename);
        if (metadata != null) {
            if (newTTL == 0) {
                metadata.setExpiration(FileMetadata.TTL_FOREVER);
                logRegistry.info("TTLMonitor", "TTL=0 (forever) asignado a: " + filename);
            } else {
                metadata.setExpiration(System.currentTimeMillis() + newTTL);
                logRegistry.info("TTLMonitor",
                        "TTL actualizado para " + filename + ": " + newTTL + "ms");
            }
        }
    }

    public void stop() {
        running = false;
        logRegistry.info("TTLMonitor", "Monitor TTL detenido");
    }
}
