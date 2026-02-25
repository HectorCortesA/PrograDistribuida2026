package com.p2p.network;

import com.p2p.utils.ThreadManager;
import com.p2p.shared.SharedList;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class TCPNetworkModule {
    private static final int PORT = 8888;
    private static final int CONNECTION_TIMEOUT = 10000;

    private final ThreadManager threadManager;
    private final String nodeId;
    private final Map<String, PeerConnection> peers;

    // FIX: CopyOnWriteArrayList es thread-safe para iteración concurrente
    // (antes era ArrayList, causaba ConcurrentModificationException)
    private final List<MessageListener> listeners;

    private SharedList sharedList;

    private ServerSocket serverSocket;
    private boolean running;

    public interface MessageListener {
        void onMessage(Message message, PeerConnection source);

        default void onPeerConnected(String peerId) {
        }

        default void onPeerDisconnected(String peerId) {
        }
    }

    public TCPNetworkModule(ThreadManager threadManager) {
        this.threadManager = threadManager;
        this.nodeId = generateNodeId();
        this.peers = new ConcurrentHashMap<>();
        // FIX: thread-safe
        this.listeners = new CopyOnWriteArrayList<>();
        this.running = true;
    }

    public void setSharedList(SharedList sharedList) {
        this.sharedList = sharedList;
    }

    public ThreadManager getThreadManager() {
        return threadManager;
    }

    private String generateNodeId() {
        try {
            return InetAddress.getLocalHost().getHostAddress() + ":" + PORT;
        } catch (UnknownHostException e) {
            return "node-" + System.currentTimeMillis();
        }
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setSoTimeout(1000);
            threadManager.executeTask(this::acceptConnections);
            System.out.println("✓ Servidor TCP escuchando en puerto " + PORT);
        } catch (IOException e) {
            System.err.println("❌ Error al iniciar servidor: " + e.getMessage());
        }
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                handleNewConnection(clientSocket);
            } catch (SocketTimeoutException e) {
                // Timeout normal, continuar esperando
            } catch (IOException e) {
                if (running)
                    e.printStackTrace();
            }
        }
    }

    private void handleNewConnection(Socket socket) {
        try {
            socket.setSoTimeout(CONNECTION_TIMEOUT);

            // FIX: Crear OOS ANTES que OIS para evitar deadlock en handshake
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

            Message synMessage = (Message) ois.readObject();

            if (synMessage.getType() == MessageType.TCP_SYN) {
                String peerId = synMessage.getSenderId();

                // FIX: Evitar conexión duplicada
                if (peers.containsKey(peerId)) {
                    socket.close();
                    return;
                }

                // Enviar SYN-ACK
                Message synAck = new Message(MessageType.TCP_SYN_ACK, nodeId);
                oos.writeObject(synAck);
                oos.flush();

                // Esperar ACK
                Message ackMessage = (Message) ois.readObject();
                if (ackMessage.getType() == MessageType.TCP_ACK) {
                    PeerConnection conn = new PeerConnection(peerId, socket, ois, oos);
                    peers.put(peerId, conn);

                    System.out.println("✅ Nueva conexión establecida con peer: " + peerId);

                    // Notificar a listeners (CopyOnWriteArrayList: seguro en concurrencia)
                    for (MessageListener listener : listeners) {
                        listener.onPeerConnected(peerId);
                    }

                    // Anunciar nuestros archivos al nuevo peer
                    announceSharedFiles(conn);

                    threadManager.executeTask(() -> listenToPeer(conn));
                }
            }
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException ex) {
                // ignorar
            }
        }
    }

    public void connectToPeer(String host) {
        // FIX: Evitar conectar si ya existe la conexión (por IP:puerto o por IP sola)
        if (peers.containsKey(host) || peers.containsKey(host + ":" + PORT)) {
            System.out.println("⚠ Ya conectado a: " + host);
            return;
        }

        System.out.println("🔌 Conectando a peer: " + host);

        try {
            Socket socket = new Socket(host, PORT);
            socket.setSoTimeout(CONNECTION_TIMEOUT);

            // FIX: OOS antes que OIS (mismo orden que el servidor)
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();

            Message syn = new Message(MessageType.TCP_SYN, nodeId);
            oos.writeObject(syn);
            oos.flush();

            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            Message synAck = (Message) ois.readObject();

            if (synAck.getType() == MessageType.TCP_SYN_ACK) {
                Message ack = new Message(MessageType.TCP_ACK, nodeId);
                oos.writeObject(ack);
                oos.flush();

                String peerId = synAck.getSenderId();
                PeerConnection conn = new PeerConnection(peerId, socket, ois, oos);
                peers.put(peerId, conn);

                System.out.println("✅ Conectado a peer: " + peerId);

                for (MessageListener listener : listeners) {
                    listener.onPeerConnected(peerId);
                }

                // Anunciar nuestros archivos al peer recién conectado
                announceSharedFiles(conn);

                threadManager.executeTask(() -> listenToPeer(conn));
            }
        } catch (Exception e) {
            System.err.println("❌ Error conectando a " + host + ": " + e.getMessage());
        }
    }

    private void announceSharedFiles(PeerConnection conn) {
        try {
            if (sharedList != null) {
                List<String> files = new ArrayList<>(sharedList.getSharedFiles());
                System.out.println("📢 Anunciando " + files.size() +
                        " archivos a " + conn.getPeerId() + ": " + files);

                Message announce = new Message(MessageType.PEER_ANNOUNCE, nodeId);
                announce.addPayload("sharedFiles", files);
                conn.getOos().writeObject(announce);
                conn.getOos().flush();
            }
        } catch (IOException e) {
            System.err.println("❌ Error anunciando archivos: " + e.getMessage());
        }
    }

    private void listenToPeer(PeerConnection conn) {
        try {
            while (running && conn.isConnected()) {
                Message message = (Message) conn.getOis().readObject();

                // Distribuir a listeners (CopyOnWriteArrayList: seguro)
                for (MessageListener listener : listeners) {
                    listener.onMessage(message, conn);
                }

                // Responder a heartbeat
                if (message.getType() == MessageType.HEARTBEAT) {
                    Message pong = new Message(MessageType.HEARTBEAT, nodeId);
                    sendMessage(pong, conn.getPeerId());
                }
            }
        } catch (Exception e) {
            // Conexión cerrada o error de red
        } finally {
            closeConnection(conn);
        }
    }

    public void sendMessage(Message message, String targetPeerId) {
        PeerConnection conn = peers.get(targetPeerId);
        if (conn != null && conn.isConnected()) {
            try {
                synchronized (conn.getOos()) {
                    // FIX: Sincronizar escritura para evitar interleaving de mensajes
                    conn.getOos().writeObject(message);
                    conn.getOos().flush();
                }
            } catch (IOException e) {
                closeConnection(conn);
            }
        }
    }

    public void broadcast(Message message) {
        // FIX: Iterar sobre copia para evitar ConcurrentModificationException
        for (PeerConnection conn : new ArrayList<>(peers.values())) {
            sendMessage(message, conn.getPeerId());
        }
    }

    public void closeConnection(PeerConnection conn) {
        if (conn == null)
            return;
        peers.remove(conn.getPeerId());
        conn.close();

        for (MessageListener listener : listeners) {
            listener.onPeerDisconnected(conn.getPeerId());
        }
    }

    public void shutdown() {
        running = false;
        for (PeerConnection conn : new ArrayList<>(peers.values())) {
            closeConnection(conn);
        }
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (Exception e) {
            // ignorar
        }
    }

    public void addListener(MessageListener listener) {
        listeners.add(listener);
    }

    public String getNodeId() {
        return nodeId;
    }

    public Map<String, PeerConnection> getPeers() {
        return peers;
    }

    public int getPeerCount() {
        return peers.size();
    }

    // ── PeerConnection ─────────────────────────────────────────────────────
    public static class PeerConnection {
        private final String peerId;
        private final Socket socket;
        private final ObjectInputStream ois;
        private final ObjectOutputStream oos;

        public PeerConnection(String peerId, Socket socket,
                ObjectInputStream ois, ObjectOutputStream oos) {
            this.peerId = peerId;
            this.socket = socket;
            this.ois = ois;
            this.oos = oos;
        }

        public boolean isConnected() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        public void close() {
            try {
                if (ois != null)
                    ois.close();
                if (oos != null)
                    oos.close();
                if (socket != null)
                    socket.close();
            } catch (IOException e) {
                // ignorar al cerrar
            }
        }

        public String getPeerId() {
            return peerId;
        }

        public ObjectInputStream getOis() {
            return ois;
        }

        public ObjectOutputStream getOos() {
            return oos;
        }
    }
}