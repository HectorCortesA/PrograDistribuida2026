package com.p2p.nameserver;

import com.p2p.network.*;
import com.p2p.shared.SharedList;
import com.p2p.shared.LogRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NameServer implements TCPNetworkModule.MessageListener {

    // ── NUEVO: interfaz para notificar al GUI en tiempo real ───────────────
    public interface RemoteFilesListener {
        void onRemoteFilesUpdated(String peerId, List<String> files);
    }

    private RemoteFilesListener remoteFilesListener;

    /** Registra el listener que se llamará cada vez que llegue un PEER_ANNOUNCE. */
    public void setRemoteFilesListener(RemoteFilesListener listener) {
        this.remoteFilesListener = listener;
    }
    // ──────────────────────────────────────────────────────────────────────

    private final TCPNetworkModule networkModule;
    private final SharedList sharedList;
    private final LogRegistry logRegistry;
    private final RequestManager requestManager;

    // FIX: Set<String> en lugar de List<String> → evita peers duplicados
    private final Map<String, Set<String>> remoteFiles;

    public NameServer(TCPNetworkModule networkModule, SharedList sharedList,
            LogRegistry logRegistry) {
        this.networkModule = networkModule;
        this.sharedList = sharedList;
        this.logRegistry = logRegistry;
        this.requestManager = new RequestManager(this);
        this.remoteFiles = new ConcurrentHashMap<>();

        this.requestManager.start();
    }

    // ── MessageListener ────────────────────────────────────────────────────

    @Override
    public void onMessage(Message message, TCPNetworkModule.PeerConnection source) {
        switch (message.getType()) {
            case PEER_ANNOUNCE:
                handlePeerAnnounce(message, source);
                break;
            case NAME_QUERY:
                handleNameQuery(message, source);
                break;
            case NAME_RESPONSE:
                requestManager.handleResponse(message, source);
                break;
            default:
                break;
        }
    }

    @Override
    public void onPeerConnected(String peerId) {
        logRegistry.info("NameServer", "Nuevo peer conectado: " + peerId);
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        logRegistry.info("NameServer", "Peer desconectado: " + peerId);
        // Eliminar todos los archivos que tenía este peer
        remoteFiles.entrySet().removeIf(entry -> {
            entry.getValue().remove(peerId);
            return entry.getValue().isEmpty();
        });
    }

    // ── Handlers privados ──────────────────────────────────────────────────

    private void handlePeerAnnounce(Message message,
            TCPNetworkModule.PeerConnection source) {
        String peerId = message.getSenderId();
        Object raw = message.getPayload("sharedFiles");

        if (!(raw instanceof List))
            return;

        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) raw;
        if (files == null || files.isEmpty())
            return;

        // FIX: Limpiar entradas previas de este peer (si actualizó su lista)
        remoteFiles.values().forEach(peers -> peers.remove(peerId));
        remoteFiles.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        for (String file : files) {
            logRegistry.info("NameServer",
                    "Archivo remoto: " + file + " en peer " + peerId);
            remoteFiles.computeIfAbsent(file, k -> ConcurrentHashMap.newKeySet())
                    .add(peerId);
        }
        logRegistry.info("NameServer",
                "Peer " + peerId + " comparte " + files.size()
                        + " archivos: " + files);

        // NUEVO: Notificar al GUI de forma inmediata
        if (remoteFilesListener != null) {
            remoteFilesListener.onRemoteFilesUpdated(peerId, new ArrayList<>(files));
        }
    }

    private void handleNameQuery(Message message,
            TCPNetworkModule.PeerConnection source) {
        String filename = (String) message.getPayload("filename");
        String requestId = requestManager.registerRequest(filename, source);

        logRegistry.info("NameServer", "Consulta de nombre para: " + filename);

        // 1. Buscar localmente
        if (sharedList.isShared(filename)) {
            sendNameResponse(source, filename, networkModule.getNodeId(), requestId);
            return;
        }

        // 2. Buscar en archivos remotos conocidos
        Set<String> locations = remoteFiles.get(filename);
        if (locations != null && !locations.isEmpty()) {
            sendNameResponse(source, filename,
                    locations.iterator().next(), requestId);
            return;
        }

        // 3. Reenviar consulta a otros peers
        message.addPayload("requestId", requestId);
        networkModule.broadcast(message);
        logRegistry.info("NameServer", "Consulta reenviada para: " + filename);
    }

    private void sendNameResponse(TCPNetworkModule.PeerConnection dest,
            String filename, String owner, String requestId) {
        Message response = new Message(MessageType.NAME_RESPONSE,
                networkModule.getNodeId());
        response.addPayload("filename", filename);
        response.addPayload("owner", owner);
        response.addPayload("authoritative", true);
        response.addPayload("requestId", requestId);
        response.addPayload("timestamp", System.currentTimeMillis());
        try {
            dest.getOos().writeObject(response);
            dest.getOos().flush();
            logRegistry.info("NameServer",
                    "Respuesta NAME enviada para: " + filename + " → " + owner);
        } catch (Exception e) {
            logRegistry.error("NameServer",
                    "Error enviando respuesta NAME: " + e.getMessage());
        }
    }

    // ── API pública ────────────────────────────────────────────────────────

    /** Retorna solo los nombres de archivos remotos. */
    public List<String> getRemoteFiles() {
        return new ArrayList<>(remoteFiles.keySet());
    }

    /**
     * Retorna archivos remotos en formato "archivo ← peer"
     * para mostrarlo directamente en el panel del GUI.
     */
    public List<String> getRemoteFilesWithPeerInfo() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : remoteFiles.entrySet()) {
            String filename = entry.getKey();
            for (String peer : entry.getValue()) {
                result.add(filename + "   ←   " + peer);
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    public List<String> getPeersForFile(String filename) {
        Set<String> peers = remoteFiles.get(filename);
        return peers != null ? new ArrayList<>(peers) : new ArrayList<>();
    }

    public TCPNetworkModule getNetworkModule() {
        return networkModule;
    }
}