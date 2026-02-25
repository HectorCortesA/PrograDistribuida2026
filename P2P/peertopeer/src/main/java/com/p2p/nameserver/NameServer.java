package com.p2p.nameserver;

import com.p2p.network.*;
import com.p2p.shared.SharedList;
import com.p2p.shared.LogRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NameServer implements TCPNetworkModule.MessageListener {
    private final TCPNetworkModule networkModule;
    private final SharedList sharedList;
    private final LogRegistry logRegistry;
    private final RequestManager requestManager;
    private final Map<String, List<String>> remoteFiles;

    public NameServer(TCPNetworkModule networkModule, SharedList sharedList, LogRegistry logRegistry) {
        this.networkModule = networkModule;
        this.sharedList = sharedList;
        this.logRegistry = logRegistry;
        this.requestManager = new RequestManager(this);
        this.remoteFiles = new ConcurrentHashMap<>();

        this.requestManager.start();
    }

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
        logRegistry.info("NameServer", "Peer conectado: " + peerId);
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        logRegistry.info("NameServer", "Peer desconectado: " + peerId);
        // Eliminar archivos de este peer
        remoteFiles.entrySet().removeIf(entry -> {
            entry.getValue().remove(peerId);
            return entry.getValue().isEmpty();
        });
    }

    private void handlePeerAnnounce(Message message, TCPNetworkModule.PeerConnection source) {
        String peerId = message.getSenderId();
        Object sharedFilesObj = message.getPayload("sharedFiles");

        if (sharedFilesObj instanceof List) {
            List<String> files = (List<String>) sharedFilesObj;

            if (files != null && !files.isEmpty()) {
                for (String file : files) {
                    remoteFiles.computeIfAbsent(file, k -> new ArrayList<>()).add(peerId);
                    logRegistry.info("NameServer", "Archivo remoto: " + file + " en " + peerId);
                }
                logRegistry.info("NameServer", "Peer " + peerId + " comparte: " + files.size() + " archivos");
            }
        }
    }

    private void handleNameQuery(Message message, TCPNetworkModule.PeerConnection source) {
        String filename = (String) message.getPayload("filename");
        String requestId = requestManager.registerRequest(filename, source);

        logRegistry.info("NameServer", "Consulta de nombre para: " + filename);

        // Buscar en archivos locales
        if (sharedList.isShared(filename)) {
            Message response = new Message(MessageType.NAME_RESPONSE, networkModule.getNodeId());
            response.addPayload("filename", filename);
            response.addPayload("owner", networkModule.getNodeId());
            response.addPayload("authoritative", true);
            response.addPayload("requestId", requestId);
            response.addPayload("timestamp", System.currentTimeMillis());

            try {
                source.getOos().writeObject(response);
                source.getOos().flush();
                logRegistry.info("NameServer", "Respuesta enviada (local) para: " + filename);
            } catch (Exception e) {
                logRegistry.error("NameServer", "Error enviando respuesta: " + e.getMessage());
            }
            return;
        }

        // Buscar en archivos remotos conocidos
        List<String> locations = remoteFiles.get(filename);
        if (locations != null && !locations.isEmpty()) {
            Message response = new Message(MessageType.NAME_RESPONSE, networkModule.getNodeId());
            response.addPayload("filename", filename);
            response.addPayload("owner", locations.get(0));
            response.addPayload("authoritative", true);
            response.addPayload("requestId", requestId);
            response.addPayload("timestamp", System.currentTimeMillis());

            try {
                source.getOos().writeObject(response);
                source.getOos().flush();
                logRegistry.info("NameServer", "Respuesta enviada (remota) para: " + filename);
            } catch (Exception e) {
                logRegistry.error("NameServer", "Error enviando respuesta: " + e.getMessage());
            }
            return;
        }

        // Reenviar consulta a otros peers
        message.addPayload("requestId", requestId);
        networkModule.broadcast(message);
        logRegistry.info("NameServer", "Consulta reenviada para: " + filename);
    }

    public List<String> getRemoteFiles() {
        return new ArrayList<>(remoteFiles.keySet());
    }

    public List<String> getPeersForFile(String filename) {
        return remoteFiles.getOrDefault(filename, new ArrayList<>());
    }

    public TCPNetworkModule getNetworkModule() {
        return networkModule;
    }
}