package com.p2p.nameserver;

import com.p2p.network.*;
import com.p2p.cache.LocalCache;
import com.p2p.metadata.MetadataStore;
import com.p2p.shared.SharedList;
import com.p2p.conflict.ConflictRegistry;
import com.p2p.shared.LogRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NameServer implements MessageListener {
    private final TCPNetworkModule networkModule;
    private final SharedList sharedList;
    private final MetadataStore metadataStore;
    private final LocalCache localCache;
    private final ConflictRegistry conflictRegistry;
    private final LogRegistry logRegistry;

    private final Map<String, List<String>> fileLocations;
    private final RequestManager requestManager;

    public NameServer(TCPNetworkModule networkModule, SharedList sharedList,
            MetadataStore metadataStore, LocalCache localCache,
            ConflictRegistry conflictRegistry, LogRegistry logRegistry) {
        this.networkModule = networkModule;
        this.sharedList = sharedList;
        this.metadataStore = metadataStore;
        this.localCache = localCache;
        this.conflictRegistry = conflictRegistry;
        this.logRegistry = logRegistry;
        this.fileLocations = new ConcurrentHashMap<>();
        this.requestManager = new RequestManager(this);

        networkModule.addListener(this);
    }

    public void start() {
        requestManager.start();
        logRegistry.info("NameServer", "Servidor de nombres iniciado en nodo: " + networkModule.getNodeId());
    }

    @Override
    public void onMessage(Message message, TCPNetworkModule.PeerConnection source) {
        switch (message.getType()) {
            case NAME_QUERY:
                handleNameQuery(message, source);
                break;
            case FILE_REQUEST:
                handleFileRequest(message, source);
                break;
            case PEER_ANNOUNCE:
                handlePeerAnnounce(message, source);
                break;
            case PEER_DISCOVERY:
                handlePeerDiscovery(source);
                break;
            default:
                // Ignorar otros tipos
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
        // Eliminar archivos de este peer
        fileLocations.entrySet().removeIf(entry -> {
            entry.getValue().remove(peerId);
            return entry.getValue().isEmpty();
        });
    }

    private void handleNameQuery(Message message, TCPNetworkModule.PeerConnection source) {
        String filename = (String) message.getPayload("filename");
        String requestId = (String) message.getPayload("requestId");

        logRegistry.info("NameServer", "Consulta de nombre para: " + filename);

        // 1. Buscar en caché local
        FileInfo cachedInfo = localCache.get(filename);
        if (cachedInfo != null && !cachedInfo.isExpired()) {
            sendResponse(source, filename, cachedInfo, true, requestId);
            return;
        }

        // 2. Buscar en archivos locales
        if (sharedList.isShared(filename)) {
            FileInfo localInfo = new FileInfo(filename, networkModule.getNodeId(),
                    System.currentTimeMillis() + 3600000);
            localCache.put(filename, localInfo);
            sendResponse(source, filename, localInfo, true, requestId);
            return;
        }

        // 3. Buscar en ubicaciones conocidas
        List<String> locations = fileLocations.get(filename);
        if (locations != null && !locations.isEmpty()) {
            FileInfo remoteInfo = new FileInfo(filename, locations.get(0),
                    System.currentTimeMillis() + 3600000);
            sendResponse(source, filename, remoteInfo, true, requestId);
            return;
        }

        // 4. Si no se encuentra, enviar NACK
        Message nack = new Message(MessageType.NACK_RESPONSE, networkModule.getNodeId());
        nack.addPayload("filename", filename);
        nack.addPayload("requestId", requestId);
        nack.addPayload("reason", "Archivo no encontrado");

        try {
            source.getOos().writeObject(nack);
            source.getOos().flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendResponse(TCPNetworkModule.PeerConnection dest, String filename,
            FileInfo info, boolean authoritative, String requestId) {
        try {
            Message response = new Message(MessageType.NAME_RESPONSE, networkModule.getNodeId());
            response.addPayload("filename", filename);
            response.addPayload("owner", info.getOwner());
            response.addPayload("authoritative", authoritative);
            response.addPayload("timestamp", info.getTimestamp());
            response.addPayload("requestId", requestId);

            dest.getOos().writeObject(response);
            dest.getOos().flush();

            logRegistry.info("NameServer", "Respuesta enviada para: " + filename);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleFileRequest(Message message, TCPNetworkModule.PeerConnection source) {
        String filename = (String) message.getPayload("filename");
        String requester = (String) message.getPayload("requester");

        logRegistry.info("NameServer", "Solicitud de archivo: " + filename + " de " + requester);

        // Verificar si tenemos el archivo
        if (sharedList.isShared(filename)) {
            try {
                Message response = new Message(MessageType.FILE_RESPONSE, networkModule.getNodeId());
                response.addPayload("filename", filename);
                response.addPayload("available", true);
                response.addPayload("owner", networkModule.getNodeId());

                source.getOos().writeObject(response);
                source.getOos().flush();

                // Registrar préstamo para detección de conflictos
                conflictRegistry.registerBorrow(filename, requester);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void handlePeerAnnounce(Message message, TCPNetworkModule.PeerConnection source) {
        String peerId = message.getSenderId();
        List<String> sharedFiles = (List<String>) message.getPayload("sharedFiles");

        if (sharedFiles != null) {
            for (String file : sharedFiles) {
                fileLocations.computeIfAbsent(file, k -> new ArrayList<>()).add(peerId);
            }
            logRegistry.info("NameServer", "Peer " + peerId + " comparte " + sharedFiles.size() + " archivos");
        }
    }

    private void handlePeerDiscovery(TCPNetworkModule.PeerConnection source) {
        try {
            Message response = new Message(MessageType.PEER_ANNOUNCE, networkModule.getNodeId());
            response.addPayload("sharedFiles", sharedList.getSharedFiles());

            source.getOos().writeObject(response);
            source.getOos().flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Clase FileInfo
    public static class FileInfo {
        private final String filename;
        private final String owner;
        private final long expiration;

        public FileInfo(String filename, String owner, long expiration) {
            this.filename = filename;
            this.owner = owner;
            this.expiration = expiration;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiration;
        }

        public String getFilename() {
            return filename;
        }

        public String getOwner() {
            return owner;
        }

        public long getTimestamp() {
            return expiration;
        }
    }
}