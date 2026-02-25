package com.p2p.monitor;

import com.p2p.cache.LocalCache;
import com.p2p.metadata.FileMetadata;
import com.p2p.metadata.MetadataStore;
import com.p2p.shared.LogRegistry;
import com.p2p.utils.ThreadManager;

import java.util.concurrent.TimeUnit;

public class TTLMonitor {
    private final LocalCache localCache;
    private final MetadataStore metadataStore;
    private final ThreadManager threadManager;
    private final LogRegistry logRegistry;
    // FIX: 'running' debe ser volatile para visibilidad entre hilos
    private volatile boolean running;

    public TTLMonitor(LocalCache localCache, MetadataStore metadataStore,
            ThreadManager threadManager) {
        this.localCache = localCache;
        this.metadataStore = metadataStore;
        this.threadManager = threadManager;
        this.logRegistry = new LogRegistry();
        this.running = true;
    }

    public void start() {
        logRegistry.info("TTLMonitor", "Monitor TTL iniciado");

        threadManager.getScheduler().scheduleAtFixedRate(
                this::checkExpiredEntries, 1, 1, TimeUnit.MINUTES);

        threadManager.getScheduler().scheduleAtFixedRate(
                this::checkMetadataExpiration, 5, 5, TimeUnit.MINUTES);
    }

    private void checkExpiredEntries() {
        if (!running)
            return;
        int beforeCount = localCache.size();
        localCache.cleanup();
        int removed = beforeCount - localCache.size();

        if (removed > 0) {
            logRegistry.info("TTLMonitor",
                    "Eliminadas " + removed + " entradas expiradas de caché");
        }
    }

    private void checkMetadataExpiration() {
        if (!running)
            return;
        int expiredCount = 0;

        for (FileMetadata metadata : metadataStore.getAllMetadata()) {
            if (metadata.isExpired()) {
                handleExpiredOwnership(metadata);
                expiredCount++;
            }
        }

        if (expiredCount > 0) {
            logRegistry.info("TTLMonitor",
                    "Procesados " + expiredCount + " metadatos expirados");
        }
    }

    private void handleExpiredOwnership(FileMetadata metadata) {
        String filename = metadata.getFilename();
        String oldOwner = metadata.getOwner();

        logRegistry.warning("TTLMonitor",
                "Dueño expirado para archivo: " + filename +
                        " (anterior: " + oldOwner + ")");

        // Eliminar del caché local
        localCache.remove(filename);

        // Actualizar metadatos: quitar owner y extender expiración 1 hora más
        metadata.setOwner(null);
        metadata.setExpiration(System.currentTimeMillis() + 3_600_000L);
    }

    public void updateTTL(String filename, long newTTL) {
        FileMetadata metadata = metadataStore.getMetadata(filename);
        if (metadata != null) {
            metadata.setExpiration(System.currentTimeMillis() + newTTL);
            logRegistry.info("TTLMonitor",
                    "TTL actualizado para " + filename + ": " + newTTL + "ms");
        }
    }

    public void stop() {
        running = false;
        logRegistry.info("TTLMonitor", "Monitor TTL detenido");
    }
}