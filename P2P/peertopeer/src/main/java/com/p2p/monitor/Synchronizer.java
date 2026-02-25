package com.p2p.monitor;

import com.p2p.conflict.ConflictEntry;
import com.p2p.conflict.ConflictRegistry;
import com.p2p.metadata.FileMetadata;
import com.p2p.metadata.MetadataStore;
import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.shared.ActiveCopies;
import com.p2p.shared.LogRegistry;
import com.p2p.utils.FileUtils;
import com.p2p.utils.ThreadManager;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Synchronizer {
    private final MetadataStore metadataStore;
    private final ConflictRegistry conflictRegistry;
    private final ActiveCopies activeCopies;
    private final TCPNetworkModule networkModule;
    private final ThreadManager threadManager;
    private final LogRegistry logRegistry;
    private final Map<String, Long> lastSyncTimes;

    private boolean running;

    public Synchronizer(MetadataStore metadataStore, ConflictRegistry conflictRegistry,
            ActiveCopies activeCopies, TCPNetworkModule networkModule,
            ThreadManager threadManager) {
        this.metadataStore = metadataStore;
        this.conflictRegistry = conflictRegistry;
        this.activeCopies = activeCopies;
        this.networkModule = networkModule;
        this.threadManager = threadManager;
        this.logRegistry = new LogRegistry();
        this.lastSyncTimes = new HashMap<>();
        this.running = true;
    }

    public void start() {
        logRegistry.info("Synchronizer", "Sincronizador iniciado");

        threadManager.getScheduler().scheduleAtFixedRate(
                this::synchronizeAllFiles,
                30, 30, TimeUnit.SECONDS);
    }

    public boolean checkForChanges(String filename) {
        File localFile = FileUtils.getSharedFile(filename);
        if (!localFile.exists()) {
            localFile = FileUtils.getLocalFile(filename);
            if (!localFile.exists()) {
                return false;
            }
        }

        try {
            String currentChecksum = FileUtils.calculateChecksum(localFile);
            long currentModified = localFile.lastModified();
            long currentSize = localFile.length();

            FileMetadata metadata = metadataStore.getMetadata(filename);

            if (metadata != null) {
                boolean checksumChanged = !currentChecksum.equals(metadata.getChecksum());
                boolean sizeChanged = currentSize != metadata.getSize();
                boolean modifiedChanged = currentModified > metadata.getLastModified();

                boolean changed = checksumChanged || sizeChanged || modifiedChanged;

                if (changed) {
                    logRegistry.info("Synchronizer",
                            "Cambio detectado en archivo: " + filename);
                    handleFileChange(filename, currentChecksum, currentSize, metadata);
                }

                return changed;
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            logRegistry.error("Synchronizer",
                    "Error verificando cambios en " + filename + ": " + e.getMessage());
        }

        return false;
    }

    private void handleFileChange(String filename, String newChecksum, long newSize,
            FileMetadata oldMetadata) {
        // Verificar conflictos
        if (conflictRegistry.hasConflict(filename)) {
            logRegistry.warning("Synchronizer",
                    "⚠ CONFLICTO durante sincronización de: " + filename);
            handleConflictDuringSync(filename, oldMetadata);
            return;
        }

        // Actualizar metadatos
        FileMetadata updatedMetadata = new FileMetadata(
                filename,
                newChecksum,
                oldMetadata.getOwner(),
                oldMetadata.getExpiration(),
                newSize);
        metadataStore.updateMetadata(updatedMetadata);

        // Notificar a copias activas
        if (activeCopies.hasActiveCopies(filename)) {
            notifyActiveCopies(filename);
        }

        // Propagar cambio a la red
        propagateChange(filename, newChecksum, newSize);
    }

    private void handleConflictDuringSync(String filename, FileMetadata oldMetadata) {
        logRegistry.error("Synchronizer",
                "Conflicto detectado - No se puede sincronizar " + filename);
    }

    private void synchronizeAllFiles() {
        logRegistry.info("Synchronizer", "Iniciando sincronización completa");

        for (FileMetadata metadata : metadataStore.getAllMetadata()) {
            String filename = metadata.getFilename();

            // No sincronizar muy frecuentemente
            Long lastSync = lastSyncTimes.get(filename);
            if (lastSync != null && System.currentTimeMillis() - lastSync < 10000) {
                continue;
            }

            if (checkForChanges(filename)) {
                lastSyncTimes.put(filename, System.currentTimeMillis());
            }
        }
    }

    private void notifyActiveCopies(String filename) {
        activeCopies.notifyCopyHolders(filename);

        // Enviar notificación por red
        Message notification = new Message(MessageType.SYNC_RESPONSE,
                networkModule.getNodeId());
        notification.addPayload("filename", filename);
        notification.addPayload("action", "FILE_CHANGED");

        networkModule.broadcast(notification);
    }

    private void propagateChange(String filename, String checksum, long size) {
        Message changeMsg = new Message(MessageType.SYNC_REQUEST,
                networkModule.getNodeId());
        changeMsg.addPayload("filename", filename);
        changeMsg.addPayload("checksum", checksum);
        changeMsg.addPayload("size", size);
        changeMsg.addPayload("timestamp", System.currentTimeMillis());

        networkModule.broadcast(changeMsg);
        logRegistry.info("Synchronizer", "Cambio propagado para: " + filename);
    }

    public void requestSync(String filename, String targetPeer) {
        Message syncRequest = new Message(MessageType.SYNC_REQUEST,
                networkModule.getNodeId());
        syncRequest.addPayload("filename", filename);
        syncRequest.addPayload("requestor", networkModule.getNodeId());

        networkModule.sendMessage(syncRequest, targetPeer);
        logRegistry.info("Synchronizer",
                "Solicitud de sincronización enviada a " + targetPeer + " para " + filename);
    }

    public void stop() {
        running = false;
        logRegistry.info("Synchronizer", "Sincronizador detenido");
    }
}