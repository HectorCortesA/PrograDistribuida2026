package com.p2p.nameserver;

import com.p2p.conflict.ConflictRegistry;
import com.p2p.cache.LocalCache;
import com.p2p.metadata.FileMetadata;
import com.p2p.metadata.MetadataStore;
import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.repository.CopyRepository;
import com.p2p.shared.ActiveCopies;
import com.p2p.shared.LogRegistry;
import com.p2p.shared.SharedList;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class NameServer implements TCPNetworkModule.MessageListener {

    private final TCPNetworkModule   networkModule;
    private final SharedList         sharedList;
    private final MetadataStore      metadataStore;
    private final LocalCache         localCache;
    private final ConflictRegistry   conflictRegistry;
    private final ActiveCopies       activeCopies;   // ← registro de copias activas
    private final CopyRepository     copyRepository; // ← repositorio Unit-of-Work
    private final LogRegistry        logRegistry;

    // filename → Set de peerIds que tienen ese archivo
    private final ConcurrentHashMap<String, Set<String>> fileLocations = new ConcurrentHashMap<>();

    private final RequestManager requestManager;
    private final List<FileListListener> fileListListeners = new CopyOnWriteArrayList<>();

    public interface FileListListener { void onFileListUpdated(); }

    public void addFileListListener(FileListListener l) { fileListListeners.add(l); }
    private void notifyFileListChanged() { for (FileListListener l : fileListListeners) l.onFileListUpdated(); }

    // ── Getters ──────────────────────────────────────────────────────────
    public Map<String, Set<String>> getFileLocations() { return fileLocations; }
    public LocalCache      getLocalCache()             { return localCache; }
    public TCPNetworkModule getNetworkModule()         { return networkModule; }
    public SharedList      getSharedListRef()          { return sharedList; }
    public CopyRepository  getCopyRepository()         { return copyRepository; }
    public ActiveCopies    getActiveCopies()           { return activeCopies; }

    // ── Constructor ──────────────────────────────────────────────────────
    public NameServer(TCPNetworkModule networkModule, SharedList sharedList,
            MetadataStore metadataStore, LocalCache localCache,
            ConflictRegistry conflictRegistry, ActiveCopies activeCopies,
            CopyRepository copyRepository, LogRegistry logRegistry) {
        this.networkModule  = networkModule;
        this.sharedList     = sharedList;
        this.metadataStore  = metadataStore;
        this.localCache     = localCache;
        this.conflictRegistry = conflictRegistry;
        this.activeCopies   = activeCopies;
        this.copyRepository = copyRepository;
        this.logRegistry    = logRegistry;
        this.requestManager = new RequestManager(this);
    }

    public void start() {
        requestManager.start();
        logRegistry.info("NameServer", "Iniciado en nodo: " + networkModule.getNodeId());
    }

    // ── MessageListener ──────────────────────────────────────────────────
    @Override
    public void onMessage(Message message, TCPNetworkModule.PeerConnection source) {
        switch (message.getType()) {
            case FILE_REQUEST:   handleFileRequest(message, source);    break;
            case PEER_ANNOUNCE:  handlePeerAnnounce(message);           break;
            case PEER_DISCOVERY: handlePeerDiscovery(source);           break;
            case NAME_QUERY:     handleNameQuery(message, source);      break;
            // ← NUEVO: enrutar respuestas al RequestManager
            case NAME_RESPONSE:  requestManager.handleResponse(message, source); break;
            case NACK_RESPONSE:  requestManager.handleNack(message);    break;
            // ← NUEVO: archivo devuelto por el editor al dueño
            case FILE_RETURNED:  handleFileReturned(message);           break;
            // ← NUEVO: visor de logs distribuido
            case LOG_REQUEST:    handleLogRequest(source);              break;
            default: break;
        }
    }

    @Override
    public void onPeerConnected(String peerId) {
        logRegistry.info("NameServer", "Peer conectado: " + peerId);
        Message announce = new Message(MessageType.PEER_ANNOUNCE, networkModule.getNodeId());
        announce.addPayload("sharedFiles", sharedList.getSharedFiles());
        networkModule.sendMessage(announce, peerId);
        Message discovery = new Message(MessageType.PEER_DISCOVERY, networkModule.getNodeId());
        networkModule.sendMessage(discovery, peerId);
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        logRegistry.info("NameServer", "Peer desconectado: " + peerId);
        fileLocations.entrySet().removeIf(entry -> {
            entry.getValue().remove(peerId);
            return entry.getValue().isEmpty();
        });
        notifyFileListChanged();
    }

    // ── Handlers ─────────────────────────────────────────────────────────

    private void handlePeerAnnounce(Message message) {
        String peerId = message.getSenderId();
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) message.getPayload("sharedFiles");
        if (files == null) return;

        fileLocations.forEach((file, owners) -> owners.remove(peerId));
        fileLocations.entrySet().removeIf(e -> e.getValue().isEmpty());

        for (String f : files) {
            fileLocations.computeIfAbsent(f, k -> ConcurrentHashMap.newKeySet()).add(peerId);
        }
        logRegistry.info("NameServer", "Peer " + peerId + " comparte " + files.size() + " archivos");
        notifyFileListChanged();
    }

    private void handlePeerDiscovery(TCPNetworkModule.PeerConnection source) {
        Message response = new Message(MessageType.PEER_ANNOUNCE, networkModule.getNodeId());
        response.addPayload("sharedFiles", sharedList.getSharedFiles());
        source.send(response);
    }

    private void handleFileRequest(Message message, TCPNetworkModule.PeerConnection source) {
        String filename  = (String) message.getPayload("filename");
        String requester = (String) message.getPayload("requester");
        logRegistry.info("NameServer", "Solicitud de archivo: " + filename + " de " + requester);

        if (!sharedList.isShared(filename)) {
            logRegistry.info("NameServer", "Archivo no disponible: " + filename);
            Message notAvail = new Message(MessageType.FILE_RESPONSE, networkModule.getNodeId());
            notAvail.addPayload("filename", filename);
            notAvail.addPayload("available", false);
            source.send(notAvail);
            return;
        }

        java.io.File file = com.p2p.utils.FileUtils.getSharedFile(filename);
        if (!file.exists()) {
            logRegistry.info("NameServer", "Archivo no encontrado en disco: " + filename);
            return;
        }

        logRegistry.info("NameServer", "Archivo listo para descarga: " + filename);
        Message ready = new Message(MessageType.FILE_RESPONSE, networkModule.getNodeId());
        ready.addPayload("filename", filename);
        ready.addPayload("available", true);
        source.send(ready);

        // Registrar préstamo en ConflictRegistry y ActiveCopies
        conflictRegistry.registerBorrow(filename, requester);
        activeCopies.registerCopy(filename, requester, requester);
        logRegistry.info("NameServer", "Copia registrada para: " + requester + " → " + filename);
    }

    /** Respuesta autoritativa o no-autoritativa a una consulta de nombre.
     *  Incluye ahora TODOS los atributos del archivo según el enunciado. */
    private void handleNameQuery(Message message, TCPNetworkModule.PeerConnection source) {
        String filename  = (String) message.getPayload("filename");
        String requestId = (String) message.getPayload("requestId");

        // ── CASO 1: SOMOS EL DUEÑO → respuesta autoritativa con atributos completos ──
        if (sharedList.isShared(filename)) {
            FileMetadata meta = metadataStore.getMetadata(filename);
            Message resp = new Message(MessageType.NAME_RESPONSE, networkModule.getNodeId());
            resp.addPayload("filename",     filename);
            resp.addPayload("owner",        networkModule.getNodeId());
            resp.addPayload("authoritative", true);
            resp.addPayload("requestId",    requestId);
            resp.addPayload("timestamp",    System.currentTimeMillis());
            if (meta != null) {
                // Atributos completos según el enunciado
                resp.addPayload("extension",    meta.getFileType());
                resp.addPayload("size",         meta.getSize());
                resp.addPayload("creationDate", meta.getCreationDate());
                resp.addPayload("lastModified", meta.getLastModified());
                resp.addPayload("ttl",          meta.getExpiration() == FileMetadata.TTL_FOREVER
                                                ? 0L : meta.getExpiration() - System.currentTimeMillis());
            }
            source.send(resp);
            logRegistry.info("NameServer", "Respuesta AUTORITATIVA enviada para: " + filename);
            return;
        }

        // ── CASO 2: CONOCEMOS AL DUEÑO → respuesta NO autoritativa con IP ──
        Set<String> owners = fileLocations.get(filename);
        if (owners != null && !owners.isEmpty()) {
            String ownerPeer = owners.iterator().next();
            Message resp = new Message(MessageType.NAME_RESPONSE, networkModule.getNodeId());
            resp.addPayload("filename",      filename);
            resp.addPayload("owner",         ownerPeer);
            resp.addPayload("authoritative", false);
            resp.addPayload("requestId",     requestId);
            resp.addPayload("timestamp",     System.currentTimeMillis());
            source.send(resp);
            logRegistry.info("NameServer", "Respuesta NO-AUTORITATIVA enviada para: " + filename
                    + " → dueño conocido: " + ownerPeer);
            return;
        }

        // ── CASO 3: NO CONOCEMOS EL ARCHIVO → NACK ──
        Message nack = new Message(MessageType.NACK_RESPONSE, networkModule.getNodeId());
        nack.addPayload("filename",  filename);
        nack.addPayload("requestId", requestId);
        nack.addPayload("reason",    "Archivo no encontrado en este servidor");
        source.send(nack);
        logRegistry.info("NameServer", "NACK enviado para: " + filename);
    }

    /** El editor notifica que terminó de usar el archivo y lo devolvió. */
    private void handleFileReturned(Message message) {
        String filename  = (String) message.getPayload("filename");
        String borrower  = (String) message.getPayload("borrower");
        boolean changed  = Boolean.TRUE.equals(message.getPayload("changed"));

        conflictRegistry.returnBorrow(filename, borrower);
        activeCopies.unregisterCopy(filename, borrower);

        logRegistry.info("NameServer", "Archivo devuelto: " + filename
                + " por " + borrower + (changed ? " (con cambios)" : " (sin cambios)"));
    }

    /** Responde con los logs recientes de este nodo para el visor distribuido. */
    private void handleLogRequest(TCPNetworkModule.PeerConnection source) {
        List<LogRegistry.LogEntry> entries = logRegistry.getRecentLogs();
        // Serializar como lista de strings para evitar problemas de serialización
        List<String> lines = new ArrayList<>();
        for (LogRegistry.LogEntry e : entries) {
            lines.add(String.format("[%s] %s %-15s: %s", e.timestamp, e.level, e.component, e.message));
        }
        Message resp = new Message(MessageType.LOG_RESPONSE, networkModule.getNodeId());
        resp.addPayload("nodeId", networkModule.getNodeId());
        resp.addPayload("logs",   (Serializable) new ArrayList<>(lines));
        source.send(resp);
        logRegistry.info("NameServer", "Logs enviados a: " + source.getPeerId());
    }

    // ── FileInfo DTO ──────────────────────────────────────────────────────
    public static class FileInfo {
        private final String filename, owner;
        private final long expiration;
        public FileInfo(String filename, String owner, long expiration) {
            this.filename = filename; this.owner = owner; this.expiration = expiration;
        }
        public boolean isExpired()  { return System.currentTimeMillis() > expiration; }
        public String getFilename() { return filename; }
        public String getOwner()    { return owner; }
        public long getTimestamp()  { return expiration; }
    }
}
