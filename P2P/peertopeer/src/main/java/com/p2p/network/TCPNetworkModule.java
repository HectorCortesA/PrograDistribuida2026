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

    public TCPNetworkModule(ThreadManager threadManager) {
        this.threadManager = threadManager;
        this.nodeId = generateNodeId();
        this.peers = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
        this.connectionStates = new ConcurrentHashMap<>();
        this.running = true;
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
            // ROL SERVIDOR: Todos escuchan
            serverSocket = new ServerSocket(PORT);
            serverSocket.setSoTimeout(1000);

            // Hilo para aceptar conexiones entrantes
            threadManager.executeTask(this::acceptConnections);

            System.out.println("✓ Modo servidor activo: Escuchando en puerto " + PORT);

            // Heartbeat periódico
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
                // Timeout normal, continuar
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleNewConnection(Socket socket) {
        try {
            socket.setSoTimeout(CONNECTION_TIMEOUT);

            // Leer mensaje inicial (SYN)
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            Message synMessage = (Message) ois.readObject();

            if (synMessage.getType() == MessageType.TCP_SYN) {
                String peerId = synMessage.getSenderId();

                // Estado: SYN_RECEIVED
                connectionStates.put(peerId, ConnectionState.SYN_RECEIVED);

                // Responder con SYN-ACK
                Message synAck = new Message(MessageType.TCP_SYN_ACK, nodeId);

                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                oos.writeObject(synAck);
                oos.flush();

                // Esperar ACK final
                Message ackMessage = (Message) ois.readObject();
                if (ackMessage.getType() == MessageType.TCP_ACK) {
                    // Conexión establecida
                    connectionStates.put(peerId, ConnectionState.ESTABLISHED);

                    PeerConnection conn = new PeerConnection(peerId, socket, ois, oos);
                    peers.put(peerId, conn);

                    System.out.println("✓ Nueva conexión establecida con peer: " + peerId);

                    // Notificar a listeners
                    for (MessageListener listener : listeners) {
                        listener.onPeerConnected(peerId);
                    }

                    // Iniciar hilo para escuchar mensajes
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

    // ROL CLIENTE: Conectarse a otros peers
    public void connectToPeer(String host) {
        if (peers.containsKey(host)) {
            return; // Ya conectado
        }

        System.out.println("→ Modo cliente: Conectando a peer " + host);

        try {
            // Estado: CLOSED -> SYN_SENT
            connectionStates.put(host, ConnectionState.SYN_SENT);

            Socket socket = new Socket(host, PORT);
            socket.setSoTimeout(CONNECTION_TIMEOUT);

            // Enviar SYN
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            Message syn = new Message(MessageType.TCP_SYN, nodeId);
            oos.writeObject(syn);
            oos.flush();

            // Recibir SYN-ACK
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            Message synAck = (Message) ois.readObject();

            if (synAck.getType() == MessageType.TCP_SYN_ACK) {
                // Estado: SYN_SENT -> ESTABLISHED
                connectionStates.put(host, ConnectionState.ESTABLISHED);

                // Enviar ACK final
                Message ack = new Message(MessageType.TCP_ACK, nodeId);
                oos.writeObject(ack);
                oos.flush();

                String peerId = synAck.getSenderId();
                PeerConnection conn = new PeerConnection(peerId, socket, ois, oos);
                peers.put(peerId, conn);

                System.out.println("✓ Conectado exitosamente a peer: " + peerId);

                // Notificar a listeners
                for (MessageListener listener : listeners) {
                    listener.onPeerConnected(peerId);
                }

                // Anunciar nuestros archivos compartidos
                announceSharedFiles(conn);

                // Iniciar hilo para escuchar
                threadManager.executeTask(() -> listenToPeer(conn));
            }

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("⚠ No se pudo conectar a " + host + ": " + e.getMessage());
            connectionStates.remove(host);
        }
    }

    private void announceSharedFiles(PeerConnection conn) {
        try {
            Message announce = new Message(MessageType.PEER_ANNOUNCE, nodeId);
            // Aquí se enviarían los archivos compartidos
            conn.getOos().writeObject(announce);
            conn.getOos().flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void listenToPeer(PeerConnection conn) {
        try {
            while (running && conn.isConnected()) {
                Message message = (Message) conn.getOis().readObject();
                conn.updateLastSeen();

                // Procesar mensaje
                processMessage(message, conn);

                // Manejar tipos especiales
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
            if (running) {
                System.out.println("Error en conexión con " + conn.getPeerId());
            }
        } finally {
            closeConnection(conn);
        }
    }

    private void processMessage(Message message, PeerConnection conn) {
        for (MessageListener listener : listeners) {
            listener.onMessage(message, conn);
        }
    }

    private void handleFin(PeerConnection conn, Message finMessage) {
        String peerId = conn.getPeerId();
        connectionStates.put(peerId, ConnectionState.CLOSE_WAIT);

        // Enviar FIN-ACK
        Message finAck = new Message(MessageType.TCP_FIN_ACK, nodeId);
        sendMessage(finAck, peerId);

        // Enviar nuestro propio FIN
        Message ourFin = new Message(MessageType.TCP_FIN, nodeId);
        sendMessage(ourFin, peerId);

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

        for (MessageListener listener : listeners) {
            listener.onPeerDisconnected(peerId);
        }
    }

    public void sendMessage(Message message, String targetPeerId) {
        PeerConnection conn = peers.get(targetPeerId);
        if (conn != null && conn.isConnected()) {
            try {
                conn.getOos().writeObject(message);
                conn.getOos().flush();
            } catch (IOException e) {
                closeConnection(conn);
            }
        }
    }

    public void broadcast(Message message) {
        for (PeerConnection conn : peers.values()) {
            if (conn.isConnected()) {
                try {
                    conn.getOos().writeObject(message);
                    conn.getOos().flush();
                } catch (IOException e) {
                    // Ignorar
                }
            }
        }
    }

    private void sendHeartbeats() {
        Message heartbeat = new Message(MessageType.HEARTBEAT, nodeId);
        broadcast(heartbeat);
    }

    public void discoverLocalPeers() {
        System.out.println("🔍 Descubriendo peers en red local...");
        try {
            // Broadcast para descubrimiento
            Message discovery = new Message(MessageType.PEER_DISCOVERY, nodeId);

            // Enviar a broadcast address
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            // Implementar broadcast UDP rápido para descubrimiento inicial
            // (mantenemos UDP solo para descubrimiento)
        } catch (Exception e) {
            // Ignorar
        }
    }

    public void closeConnection(PeerConnection conn) {
        if (conn == null)
            return;

        String peerId = conn.getPeerId();

        try {
            connectionStates.put(peerId, ConnectionState.FIN_WAIT_1);

            Message fin = new Message(MessageType.TCP_FIN, nodeId);
            conn.getOos().writeObject(fin);
            conn.getOos().flush();

        } catch (IOException e) {
            // Ignorar
        } finally {
            conn.close();
            peers.remove(peerId);
            connectionStates.remove(peerId);

            for (MessageListener listener : listeners) {
                listener.onPeerDisconnected(peerId);
            }
        }
    }

    public void shutdown() {
        running = false;

        // Cerrar todas las conexiones
        for (PeerConnection conn : new ArrayList<>(peers.values())) {
            closeConnection(conn);
        }

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
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

    public boolean isConnectedTo(String peerId) {
        return peers.containsKey(peerId);
    }

    public int getPeerCount() {
        return peers.size();
    }

    // Clase para mantener conexiones
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

        public void close() {
            try {
                if (ois != null)
                    ois.close();
                if (oos != null)
                    oos.close();
                if (socket != null)
                    socket.close();
            } catch (IOException e) {
                e.printStackTrace();
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

// Interface para listeners
interface MessageListener {
    void onMessage(Message message, TCPNetworkModule.PeerConnection source);

    default void onPeerConnected(String peerId) {
    }

    default void onPeerDisconnected(String peerId) {
    }
}
