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
        this.nameServer      = nameServer;
        this.pendingRequests = new ConcurrentHashMap<>();
        this.scheduler       = Executors.newScheduledThreadPool(2);
        this.logRegistry     = new LogRegistry();
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
        scheduler.schedule(() -> handleTimeout(requestId), 5, TimeUnit.SECONDS);
        return requestId;
    }

    /**
     * Procesa una NAME_RESPONSE.
     *
     * - Si es AUTORITATIVA: actualiza caché y reenvía al solicitante original.
     * - Si es NO AUTORITATIVA: hace follow-up directamente al dueño real.
     */
    public void handleResponse(Message message, TCPNetworkModule.PeerConnection source) {
        String requestId = (String) message.getPayload("requestId");
        if (requestId == null) return;

        PendingRequest request = pendingRequests.remove(requestId);
        if (request == null) return;

        boolean authoritative = Boolean.TRUE.equals(message.getPayload("authoritative"));
        String  filename      = (String) message.getPayload("filename");
        String  owner         = (String) message.getPayload("owner");

        logRegistry.info("RequestManager", "Respuesta para '" + filename
                + "' [Autoritativa: " + authoritative + "]");

        if (authoritative) {
            // Actualizar caché con la respuesta autoritativa
            long ts = message.getTimestamp();
            NameServer.FileInfo info = new NameServer.FileInfo(filename, owner, ts + 3_600_000L);
            nameServer.getLocalCache().put(filename, info);

            // Reenviar respuesta al solicitante original
            forwardToSource(request.getSource(), message);
            logRegistry.info("RequestManager", "Caché actualizada y respuesta reenviada para: " + filename);

        } else {
            // ── RESPUESTA NO AUTORITATIVA ──
            // Necesitamos preguntar directamente al dueño real
            logRegistry.info("RequestManager", "Respuesta no-autoritativa. Follow-up al dueño: " + owner);
            followUpWithOwner(filename, owner, request);
        }
    }

    /**
     * Envía una NAME_QUERY directamente al peer dueño.
     * Si el dueño responde autoritativamente, la respuesta se propaga al
     * solicitante original a través del RequestManager.
     */
    private void followUpWithOwner(String filename, String ownerPeerId, PendingRequest originalRequest) {
        TCPNetworkModule nm = nameServer.getNetworkModule();

        if (!nm.isConnectedTo(ownerPeerId)) {
            logRegistry.warning("RequestManager", "Dueño no conectado: " + ownerPeerId
                    + " para " + filename + ". Enviando NACK al solicitante.");
            sendNackToSource(originalRequest, filename, "Dueño del archivo no conectado");
            return;
        }

        String newId = UUID.randomUUID().toString();
        // El nuevo pending tiene como "source" el mismo solicitante original
        PendingRequest followUp = new PendingRequest(newId, filename, originalRequest.getSource());
        followUp.addTriedPeer(ownerPeerId);
        pendingRequests.put(newId, followUp);

        Message query = new Message(MessageType.NAME_QUERY, nm.getNodeId());
        query.addPayload("filename",  filename);
        query.addPayload("requestId", newId);
        nm.sendMessage(query, ownerPeerId);

        logRegistry.info("RequestManager", "Follow-up enviado al dueño " + ownerPeerId
                + " para: " + filename + " [nuevo ID: " + newId + "]");

        // Timeout para este follow-up
        scheduler.schedule(() -> handleTimeout(newId), 5, TimeUnit.SECONDS);
    }

    /**
     * Procesa un NACK_RESPONSE.
     * Intenta con otro peer de la red. Si no quedan peers, envía NACK final.
     */
    public void handleNack(Message message) {
        String requestId = (String) message.getPayload("requestId");
        if (requestId == null) return;

        PendingRequest request = pendingRequests.remove(requestId);
        if (request == null) return;

        String filename = (String) message.getPayload("filename");
        logRegistry.info("RequestManager", "NACK recibido para: " + filename
                + ". Intentando con otro peer...");

        TCPNetworkModule nm = nameServer.getNetworkModule();
        Set<String> tried = request.getTriedPeers();

        // Buscar un peer que no hayamos consultado aún
        for (String peerId : nm.getPeers().keySet()) {
            if (tried.contains(peerId)) continue;

            String newId = UUID.randomUUID().toString();
            PendingRequest retry = new PendingRequest(newId, filename, request.getSource());
            retry.addAllTriedPeers(tried);
            retry.addTriedPeer(peerId);
            pendingRequests.put(newId, retry);

            Message query = new Message(MessageType.NAME_QUERY, nm.getNodeId());
            query.addPayload("filename",  filename);
            query.addPayload("requestId", newId);
            nm.sendMessage(query, peerId);

            logRegistry.info("RequestManager", "NACK – Reintentando con: " + peerId
                    + " para '" + filename + "' [nuevo ID: " + newId + "]");

            scheduler.schedule(() -> handleTimeout(newId), 5, TimeUnit.SECONDS);
            return; // esperamos esta respuesta
        }

        // No quedan peers que consultar
        logRegistry.info("RequestManager", "Ningún servidor en la red conoce: " + filename);
        sendNackToSource(request, filename, "Ningún servidor en la red conoce el archivo");
    }

    // ── Timeout ──────────────────────────────────────────────────────────

    private void handleTimeout(String requestId) {
        PendingRequest request = pendingRequests.remove(requestId);
        if (request == null) return;

        logRegistry.warning("RequestManager", "Timeout para solicitud: " + requestId
                + " [" + request.getFilename() + "]");
        sendNackToSource(request, request.getFilename(),
                "Timeout – No se encontró respuesta en la red");
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    private void forwardToSource(TCPNetworkModule.PeerConnection source, Message message) {
        try {
            if (source != null && source.isConnected()) source.send(message);
        } catch (Exception e) {
            logRegistry.error("RequestManager", "Error reenviando respuesta: " + e.getMessage());
        }
    }

    private void sendNackToSource(PendingRequest request, String filename, String reason) {
        try {
            TCPNetworkModule nm = nameServer.getNetworkModule();
            Message nack = new Message(MessageType.NACK_RESPONSE, nm.getNodeId());
            nack.addPayload("filename", filename);
            nack.addPayload("requestId", request.getId());
            nack.addPayload("reason", reason);
            TCPNetworkModule.PeerConnection src = request.getSource();
            if (src != null && src.isConnected()) src.send(nack);
        } catch (Exception e) {
            logRegistry.error("RequestManager", "Error enviando NACK: " + e.getMessage());
        }
    }

    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        int before = pendingRequests.size();
        pendingRequests.entrySet().removeIf(e -> now - e.getValue().getTimestamp() > 15_000);
        int removed = before - pendingRequests.size();
        if (removed > 0)
            logRegistry.info("RequestManager", "Limpiadas " + removed + " solicitudes expiradas");
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        pendingRequests.clear();
        logRegistry.info("RequestManager", "Gestor de peticiones detenido");
    }

    // ── PendingRequest ────────────────────────────────────────────────────

    private static class PendingRequest {
        private final String id;
        private final String filename;
        private final TCPNetworkModule.PeerConnection source;
        private final long timestamp;
        /** Peers ya consultados para este archivo (evita ciclos). */
        private final Set<String> triedPeers = new HashSet<>();

        public PendingRequest(String id, String filename, TCPNetworkModule.PeerConnection source) {
            this.id        = id;
            this.filename  = filename;
            this.source    = source;
            this.timestamp = System.currentTimeMillis();
        }

        public String getId()             { return id; }
        public String getFilename()       { return filename; }
        public TCPNetworkModule.PeerConnection getSource() { return source; }
        public long getTimestamp()        { return timestamp; }
        public Set<String> getTriedPeers() { return triedPeers; }
        public void addTriedPeer(String p) { triedPeers.add(p); }
        public void addAllTriedPeers(Set<String> peers) { triedPeers.addAll(peers); }
    }
}
