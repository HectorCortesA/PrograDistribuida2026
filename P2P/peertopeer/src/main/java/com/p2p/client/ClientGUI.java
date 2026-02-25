package com.p2p.client;

import com.p2p.nameserver.NameServer;
import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.shared.SharedList;
import com.p2p.metadata.FileMetadata;
import com.p2p.metadata.MetadataStore;
import com.p2p.shared.LogRegistry;
import com.p2p.utils.FileUtils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

public class ClientGUI extends JFrame {
    private final NameServer nameServer;
    private final TCPNetworkModule networkModule;
    private final SharedList sharedList;
    private final MetadataStore metadataStore;
    private final LogRegistry logRegistry;

    private DefaultListModel<String> sharedListModel;
    private DefaultListModel<String> peersListModel;
    private JList<String> sharedFilesList;
    private JList<String> peersList;
    private JTextArea logArea;
    private JLabel statusLabel;

    public ClientGUI(NameServer nameServer, TCPNetworkModule networkModule,
            SharedList sharedList, MetadataStore metadataStore,
            LogRegistry logRegistry) {
        this.nameServer = nameServer;
        this.networkModule = networkModule;
        this.sharedList = sharedList;
        this.metadataStore = metadataStore;
        this.logRegistry = logRegistry;

        initializeGUI();
        updateSharedFilesList();
        updatePeersList();

        // Timer para actualizar lista de peers
        new Timer(5000, e -> updatePeersList()).start();
    }

    private void initializeGUI() {
        setTitle("P2P File Sharing - Nodo: " + networkModule.getNodeId());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);

        // Panel principal
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Panel superior con información del nodo
        JPanel topPanel = createTopPanel();

        // Panel central con split
        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        centerSplit.setResizeWeight(0.6);

        // Panel izquierdo - Archivos compartidos
        JPanel leftPanel = createLeftPanel();

        // Panel derecho - Peers conectados
        JPanel rightPanel = createRightPanel();

        centerSplit.setLeftComponent(leftPanel);
        centerSplit.setRightComponent(rightPanel);

        // Panel inferior - Logs
        JPanel bottomPanel = createBottomPanel();

        // Ensamblar
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(centerSplit, BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        add(mainPanel);

        // Crear menú
        createMenuBar();
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEtchedBorder());

        statusLabel = new JLabel("Conectado | Peers: 0 | Archivos compartidos: 0");
        statusLabel.setFont(new Font("Arial", Font.BOLD, 12));

        JButton refreshBtn = new JButton("Actualizar");
        refreshBtn.addActionListener(e -> {
            updateSharedFilesList();
            updatePeersList();
        });

        JButton connectBtn = new JButton("Conectar a Peer");
        connectBtn.addActionListener(e -> showConnectDialog());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(connectBtn);
        buttonPanel.add(refreshBtn);

        panel.add(statusLabel, BorderLayout.WEST);
        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("📁 Archivos Compartidos en la Red"));

        sharedListModel = new DefaultListModel<>();
        sharedFilesList = new JList<>(sharedListModel);
        sharedFilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scrollPane = new JScrollPane(sharedFilesList);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton downloadBtn = new JButton("Descargar");
        downloadBtn.addActionListener(e -> downloadSelectedFile());

        JButton shareBtn = new JButton("Compartir Archivo");
        shareBtn.addActionListener(e -> shareFile());

        JButton viewBtn = new JButton("Ver/Editar");
        viewBtn.addActionListener(e -> viewSelectedFile());

        buttonPanel.add(downloadBtn);
        buttonPanel.add(shareBtn);
        buttonPanel.add(viewBtn);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("🖧 Peers Conectados"));

        peersListModel = new DefaultListModel<>();
        peersList = new JList<>(peersListModel);

        JScrollPane scrollPane = new JScrollPane(peersList);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton infoBtn = new JButton("Info Peer");
        infoBtn.addActionListener(e -> showPeerInfo());

        JButton disconnectBtn = new JButton("Desconectar");
        disconnectBtn.addActionListener(e -> disconnectSelectedPeer());

        buttonPanel.add(infoBtn);
        buttonPanel.add(disconnectBtn);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("📋 Registro de Actividad"));

        logArea = new JTextArea(8, 80);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));

        JScrollPane scrollPane = new JScrollPane(logArea);

        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("Archivo");
        JMenuItem exitItem = new JMenuItem("Salir");
        exitItem.addActionListener(e -> {
            networkModule.shutdown();
            System.exit(0);
        });
        fileMenu.add(exitItem);

        JMenu toolsMenu = new JMenu("Herramientas");
        JMenuItem conflictsItem = new JMenuItem("Ver Conflictos");
        conflictsItem.addActionListener(e -> showConflicts());
        toolsMenu.add(conflictsItem);

        JMenuItem cacheItem = new JMenuItem("Ver Caché Local");
        cacheItem.addActionListener(e -> showCache());
        toolsMenu.add(cacheItem);

        JMenuItem metadataItem = new JMenuItem("Ver Metadatos");
        metadataItem.addActionListener(e -> showMetadata());
        toolsMenu.add(metadataItem);

        JMenu helpMenu = new JMenu("Ayuda");
        JMenuItem aboutItem = new JMenuItem("Acerca de");
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(toolsMenu);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);
    }

    private void showConnectDialog() {
        String peerAddress = JOptionPane.showInputDialog(this,
                "Dirección IP del peer:",
                "Conectar a Peer",
                JOptionPane.QUESTION_MESSAGE);

        if (peerAddress != null && !peerAddress.trim().isEmpty()) {
            networkModule.connectToPeer(peerAddress.trim());
            log("Conectando a peer: " + peerAddress);
        }
    }

    private void downloadSelectedFile() {
        String selected = sharedFilesList.getSelectedValue();
        if (selected != null) {
            log("Iniciando descarga de: " + selected);
            // Aquí implementar lógica de descarga
        }
    }

    private void shareFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File("."));

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String filename = file.getName();

            // Copiar a directorio compartido
            File dest = FileUtils.getSharedFile(filename);
            try {
                FileUtils.copyFile(file, dest);
                sharedList.addFile(filename);
                metadataStore.addMetadata(FileMetadata.fromFile(dest));
                updateSharedFilesList();
                log("Archivo compartido: " + filename);

                // Anunciar a otros peers
                broadcastSharedFiles();

            } catch (Exception e) {
                log("Error compartiendo archivo: " + e.getMessage());
            }
        }
    }

    private void viewSelectedFile() {
        String selected = sharedFilesList.getSelectedValue();
        if (selected != null) {
            FileEditor editor = new FileEditor(this, selected, nameServer);
            editor.setVisible(true);
        }
    }

    private void showPeerInfo() {
        String selected = peersList.getSelectedValue();
        if (selected != null) {
            JOptionPane.showMessageDialog(this,
                    "Peer: " + selected + "\n" +
                            "Estado: Conectado\n" +
                            "Tiempo de conexión: Activo",
                    "Información del Peer",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void disconnectSelectedPeer() {
        String selected = peersList.getSelectedValue();
        if (selected != null) {
            // Extraer peerId del string mostrado
            String peerId = selected.split(" - ")[0];
            // networkModule.closeConnection(peerId);
            log("Desconectando peer: " + peerId);
        }
    }

    private void showConflicts() {
        JOptionPane.showMessageDialog(this,
                "Registro de Conflictos:\n- No hay conflictos activos",
                "Conflictos",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showCache() {
        JOptionPane.showMessageDialog(this,
                "Caché Local:\n- No hay elementos en caché",
                "Caché Local",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showMetadata() {
        StringBuilder sb = new StringBuilder("Metadatos:\n");
        for (String file : sharedList.getSharedFiles()) {
            sb.append("- ").append(file).append("\n");
        }
        JOptionPane.showMessageDialog(this, sb.toString(), "Metadatos", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "P2P File Sharing System\nVersión 2.0 - TCP Puro\n\n" +
                        "Sistema peer-to-peer verdaderamente distribuido\n" +
                        "Todos los nodos son iguales (servidor/cliente)\n\n" +
                        "Características:\n" +
                        "✓ TCP puro con handshake completo\n" +
                        "✓ Detección de conflictos\n" +
                        "✓ Caché local con TTL\n" +
                        "✓ Consenso distribuido\n" +
                        "✓ Sincronización automática",
                "Acerca de",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateSharedFilesList() {
        sharedListModel.clear();
        List<String> files = sharedList.getSharedFiles();
        for (String file : files) {
            sharedListModel.addElement(file);
        }
        updateStatusLabel();
    }

    private void updatePeersList() {
        peersListModel.clear();
        var peers = networkModule.getPeers();
        for (String peerId : peers.keySet()) {
            String status = networkModule.isConnectedTo(peerId) ? "✓" : "✗";
            peersListModel.addElement(peerId + " - " + status);
        }
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        int peerCount = networkModule.getPeerCount();
        int fileCount = sharedList.getSharedFiles().size();
        statusLabel.setText("🟢 Conectado | Peers: " + peerCount + " | Archivos locales: " + fileCount);
    }

    private void broadcastSharedFiles() {
        Message announce = new Message(MessageType.PEER_ANNOUNCE, networkModule.getNodeId());
        announce.addPayload("sharedFiles", sharedList.getSharedFiles());
        networkModule.broadcast(announce);
    }

    private void log(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        logArea.append("[" + timestamp + "] " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        logRegistry.info("GUI", message);
    }
}
