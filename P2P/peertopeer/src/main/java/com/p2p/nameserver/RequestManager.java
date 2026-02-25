package com.p2p.nameserver;

import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.shared.LogRegistry;

import java.util.*;
import java.util.concurrent.*;

public class RequestManager {
    private final NameServer nameServer;
    private final Map<String, PendingRequest> pendingRequests;
    private final ScheduledExecutorService scheduler;
    private final LogRegistry logRegistry;

    public RequestManager(NameServer nameServer) {
        this.nameServer = nameServer;
        this.pendingRequests = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.logRegistry = new LogRegistry();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::cleanupExpiredRequests, 30, 30, TimeUnit.SECONDS);
        logRegistry.info("RequestManager", "Gestor de peticiones iniciado");
    }

    public String registerRequest(String filename, TCPNetworkModule.PeerConnection source) {
        String requestId = UUID.randomUUID().toString();
        PendingRequest request = new PendingRequest(requestId, filename, source);
        pendingRequests.put(requestId, request);

        logRegistry.info("RequestManager", "Petición registrada: " + filename + " [ID: " + requestId + "]");

        // Timeout después de 5 segundos
        scheduler.schedule(() -> handleTimeout(requestId), 5, TimeUnit.SECONDS);

        return requestId;
    }

    public void handleResponse(Message message, TCPNetworkModule.PeerConnection source) {
        String requestId = (String) message.getPayload("requestId");
        PendingRequest request = pendingRequests.remove(requestId);

        if (request != null) {
            boolean authoritative = (boolean) message.getPayload("authoritative");
            String filename = (String) message.getPayload("filename");
            String owner = (String) message.getPayload("owner");

            logRegistry.info("RequestManager", "Respuesta recibida para " + filename +
                    " [Autoritativa: " + authoritative + "]");

            if (authoritative) {
                // Actualizar caché local
                long timestamp = (long) message.getPayload("timestamp");
                NameServer.FileInfo info = new NameServer.FileInfo(filename, owner, timestamp);
                nameServer.getLocalCache().put(filename, info);

                // Reenviar respuesta al solicitante original
                try {
                    TCPNetworkModule.PeerConnection originalSource = request.getSource();
                    if (originalSource != null && originalSource.isConnected()) {
                        originalSource.getOos().writeObject(message);
                        originalSource.getOos().flush();
                    }
                } catch (Exception e) {
                    logRegistry.error("RequestManager", "Error reenviando respuesta: " + e.getMessage());
                }
            }
        }
    }

    public void forwardQuery(Message message, String requestId) {
        // Reenviar consulta a otros peers (excepto al que la envió)
        String excludePeer = message.getSenderId();

        nameServer.getNetworkModule().broadcast(message);

        logRegistry.info("RequestManager", "Consulta reenviada a peers [ID: " + requestId + "]");
    }

    private void handleTimeout(String requestId) {
        PendingRequest request = pendingRequests.remove(requestId);
        if (request != null) {
            logRegistry.warning("RequestManager", "Timeout para solicitud: " + requestId +
                    " [" + request.getFilename() + "]");

            // Enviar NACK al solicitante
            try {
                Message nack = new Message(MessageType.NACK_RESPONSE, nameServer.getNetworkModule().getNodeId());
                nack.addPayload("filename", request.getFilename());
                nack.addPayload("requestId", requestId);
                nack.addPayload("reason", "Timeout - No se encontró el archivo en la red");

                TCPNetworkModule.PeerConnection source = request.getSource();
                if (source != null && source.isConnected()) {
                    source.getOos().writeObject(nack);
                    source.getOos().flush();
                }
            } catch (Exception e) {
                logRegistry.error("RequestManager", "Error enviando NACK: " + e.getMessage());
            }
        }
    }

    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        int beforeCount = pendingRequests.size();

        pendingRequests.entrySet().removeIf(entry -> now - entry.getValue().getTimestamp() > 10000);

        int removedCount = beforeCount - pendingRequests.size();
        if (removedCount > 0) {
            logRegistry.info("RequestManager", "Limpiadas " + removedCount + " solicitudes expiradas");
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        pendingRequests.clear();
        logRegistry.info("RequestManager", "Gestor de peticiones detenido");
    }

    // Clase interna para solicitudes pendientes
    private static class PendingRequest {
        private final String id;
        private final String filename;
        private final TCPNetworkModule.PeerConnection source;
        private final long timestamp;

        public PendingRequest(String id, String filename, TCPNetworkModule.PeerConnection source) {
            this.id = id;
            this.filename = filename;
            this.source = source;
            this.timestamp = System.currentTimeMillis();
        }

        public String getId() {
            return id;
        }

        public String getFilename() {
            return filename;
        }

        public TCPNetworkModule.PeerConnection getSource() {
            return source;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
