package com.p2p.network;

import com.p2p.utils.ThreadManager;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TCPNetworkModule {
    private static final int PORT = 8888;
    private static final int CONNECTION_TIMEOUT = 10000;

    private final ThreadManager threadManager;
    private final String nodeId;
    private final Map<String, PeerConnection> peers;
    private final List<MessageListener> listeners;
    private final Map<String, ConnectionState> connectionStates;

    private ServerSocket serverSocket;
    private boolean running;

    public enum ConnectionState {
        CLOSED, LISTEN, SYN_SENT, SYN_RECEIVED, ESTABLISHED,
        FIN_WAIT_1, FIN_WAIT_2, TIME_WAIT, CLOSE_WAIT, LAST_ACK
    }

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
        this.listeners = new ArrayList<>();
        this.connectionStates = new ConcurrentHashMap<>();
        this.running = true;
    }

    private String generateNodeId() {
        // InetAddress.getLocalHost() devuelve 127.0.0.1 en macOS/Linux.
        // Iterar las interfaces de red para encontrar la IP real de la LAN.
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                // Ignorar loopback, inactivas y virtuales
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                java.util.Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    // Solo IPv4, no loopback, no link-local (169.254.x.x)
                    if (addr instanceof java.net.Inet4Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress() + ":" + PORT;
                    }
                }
            }
        } catch (java.net.SocketException e) {
            // fallback
        }
        // Último recurso: getLocalHost
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
            System.out.println("✓ Modo servidor activo: Escuchando en puerto " + PORT);
            threadManager.getScheduler().scheduleAtFixedRate(
                    this::sendHeartbeats, 5, 5, TimeUnit.SECONDS);
        } catch (IOException e) {
            System.err.println("⚠ Error al iniciar servidor en puerto " + PORT + ": " + e.getMessage());
        }
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                handleNewConnection(clientSocket);
            } catch (SocketTimeoutException e) {
                // normal
            } catch (IOException e) {
                if (running)
                    e.printStackTrace();
            }
        }
    }

    private void handleNewConnection(Socket socket) {
        try {
            socket.setSoTimeout(CONNECTION_TIMEOUT);
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            Message synMessage = (Message) ois.readObject();

            if (synMessage.getType() == MessageType.TCP_SYN) {
                String peerId = synMessage.getSenderId();
                connectionStates.put(peerId, ConnectionState.SYN_RECEIVED);

                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                Message synAck = new Message(MessageType.TCP_SYN_ACK, nodeId);
                oos.writeObject(synAck);
                oos.flush();

                Message ackMessage = (Message) ois.readObject();
                if (ackMessage.getType() == MessageType.TCP_ACK) {
                    connectionStates.put(peerId, ConnectionState.ESTABLISHED);
                    PeerConnection conn = new PeerConnection(peerId, socket, ois, oos);
                    peers.put(peerId, conn);
                    System.out.println("✓ Nueva conexión establecida con peer: " + peerId);
                    for (MessageListener listener : listeners)
                        listener.onPeerConnected(peerId);
                    threadManager.executeTask(() -> listenToPeer(conn));
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            try {
                socket.close();
            } catch (IOException ex) {
            }
        }
    }

    public void connectToPeer(String host) {
        // Evitar conectar a nosotros mismos
        if (host.equals(nodeId) || host.equals(nodeId.split(":")[0]))
            return;
        // Evitar duplicados (revisar por IP también)
        for (String peerId : peers.keySet()) {
            if (peerId.startsWith(host + ":") || peerId.equals(host))
                return;
        }

        System.out.println("→ Modo cliente: Conectando a peer " + host);
        try {
            connectionStates.put(host, ConnectionState.SYN_SENT);
            Socket socket = new Socket(host, PORT);
            socket.setSoTimeout(CONNECTION_TIMEOUT);

            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            Message syn = new Message(MessageType.TCP_SYN, nodeId);
            oos.writeObject(syn);
            oos.flush();

            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            Message synAck = (Message) ois.readObject();

            if (synAck.getType() == MessageType.TCP_SYN_ACK) {
                connectionStates.put(host, ConnectionState.ESTABLISHED);
                Message ack = new Message(MessageType.TCP_ACK, nodeId);
                oos.writeObject(ack);
                oos.flush();

                String peerId = synAck.getSenderId();
                PeerConnection conn = new PeerConnection(peerId, socket, ois, oos);
                peers.put(peerId, conn);
                System.out.println("✓ Conectado exitosamente a peer: " + peerId);
                for (MessageListener listener : listeners)
                    listener.onPeerConnected(peerId);
                threadManager.executeTask(() -> listenToPeer(conn));
            }
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("⚠ No se pudo conectar a " + host + ": " + e.getMessage());
            connectionStates.remove(host);
        }
    }

    private void listenToPeer(PeerConnection conn) {
        try {
            while (running && conn.isConnected()) {
                Message message = (Message) conn.getOis().readObject();
                conn.updateLastSeen();
                processMessage(message, conn);

                switch (message.getType()) {
                    case TCP_FIN:
                        handleFin(conn, message);
                        break;
                    case TCP_FIN_ACK:
                        handleFinAck(conn, message);
                        break;
                    case PEER_LEAVE:
                        handlePeerLeave(conn.getPeerId());
                        return;
                }
            }
        } catch (EOFException e) {
            System.out.println("Peer cerró conexión: " + conn.getPeerId());
        } catch (IOException | ClassNotFoundException e) {
            if (running)
                System.out.println("Error en conexión con " + conn.getPeerId());
        } finally {
            // Limpiar y notificar desconexión
            String peerId = conn.getPeerId();
            conn.close();
            peers.remove(peerId);
            connectionStates.remove(peerId);
            for (MessageListener listener : listeners)
                listener.onPeerDisconnected(peerId);
        }
    }

    private void processMessage(Message message, PeerConnection conn) {
        for (MessageListener listener : listeners)
            listener.onMessage(message, conn);
    }

    private void handleFin(PeerConnection conn, Message finMessage) {
        String peerId = conn.getPeerId();
        connectionStates.put(peerId, ConnectionState.CLOSE_WAIT);
        sendMessage(new Message(MessageType.TCP_FIN_ACK, nodeId), peerId);
        sendMessage(new Message(MessageType.TCP_FIN, nodeId), peerId);
        connectionStates.put(peerId, ConnectionState.LAST_ACK);
    }

    private void handleFinAck(PeerConnection conn, Message finAckMessage) {
        String peerId = conn.getPeerId();
        connectionStates.put(peerId, ConnectionState.TIME_WAIT);
        threadManager.getScheduler().schedule(() -> {
            connectionStates.remove(peerId);
            peers.remove(peerId);
        }, 2, TimeUnit.SECONDS);
    }

    private void handlePeerLeave(String peerId) {
        peers.remove(peerId);
        connectionStates.remove(peerId);
        System.out.println("Peer desconectado: " + peerId);
        for (MessageListener listener : listeners)
            listener.onPeerDisconnected(peerId);
    }

    /**
     * Envía un mensaje a un peer de forma thread-safe.
     * Sincronizado sobre la conexión para evitar que múltiples hilos
     * (heartbeat, listener, descarga) corrompan el ObjectOutputStream.
     */
    public void sendMessage(Message message, String targetPeerId) {
        PeerConnection conn = peers.get(targetPeerId);
        if (conn != null && conn.isConnected()) {
            conn.send(message);
        }
    }

    public void broadcast(Message message) {
        for (PeerConnection conn : peers.values()) {
            if (conn.isConnected())
                conn.send(message);
        }
    }

    private void sendHeartbeats() {
        broadcast(new Message(MessageType.HEARTBEAT, nodeId));
    }

    public void discoverLocalPeers() {
        System.out.println("🔍 Descubriendo peers en red local...");
    }

    public void closeConnection(PeerConnection conn) {
        if (conn == null)
            return;
        String peerId = conn.getPeerId();
        connectionStates.put(peerId, ConnectionState.FIN_WAIT_1);
        conn.send(new Message(MessageType.TCP_FIN, nodeId));
        conn.close();
        peers.remove(peerId);
        connectionStates.remove(peerId);
        for (MessageListener listener : listeners)
            listener.onPeerDisconnected(peerId);
    }

    public void shutdown() {
        running = false;
        for (PeerConnection conn : new ArrayList<>(peers.values())) {
            conn.send(new Message(MessageType.TCP_FIN, nodeId));
            conn.close();
        }
        peers.clear();
        try {
            if (serverSocket != null)
                serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void addListener(MessageListener listener) {
        listeners.add(listener);
    }

    public ThreadManager getThreadManager() {
        return threadManager;
    }

    public String getNodeId() {
        return nodeId;
    }

    public Map<String, PeerConnection> getPeers() {
        return peers;
    }

    public boolean isConnectedTo(String peerId) {
        return peers.containsKey(peerId);
    }

    public int getPeerCount() {
        return peers.size();
    }

    // ─────────────────────────────────────────────────────────────────────
    public static class PeerConnection {
        private final String peerId;
        private final Socket socket;
        private final ObjectInputStream ois;
        private final ObjectOutputStream oos;
        private long lastSeen;

        public PeerConnection(String peerId, Socket socket,
                ObjectInputStream ois, ObjectOutputStream oos) {
            this.peerId = peerId;
            this.socket = socket;
            this.ois = ois;
            this.oos = oos;
            this.lastSeen = System.currentTimeMillis();
        }

        public boolean isConnected() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        public void updateLastSeen() {
            this.lastSeen = System.currentTimeMillis();
        }

        /**
         * Thread-safe: sincronizado para que solo un hilo a la vez
         * escriba en el ObjectOutputStream. Sin esto, múltiples hilos
         * (heartbeat, FILE_REQUEST, PEER_ANNOUNCE) corrompen el stream
         * y el peer se desconecta.
         */
        public synchronized void send(Message message) {
            if (!isConnected())
                return;
            try {
                oos.writeObject(message);
                oos.flush();
                oos.reset(); // Evitar que ObjectOutputStream cachée referencias antiguas
            } catch (IOException e) {
                // No lanzar; el caller (listenToPeer) detectará el fallo en el siguiente read
            }
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
                // ignorar
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

        public long getLastSeen() {
            return lastSeen;
        }
    }
}