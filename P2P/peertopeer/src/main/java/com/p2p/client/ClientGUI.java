package com.p2p.client;

import com.p2p.nameserver.NameServer;
import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.shared.SharedList;
import com.p2p.shared.LogRegistry;
import com.p2p.utils.FileUtils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ClientGUI extends JFrame {
    private final NameServer nameServer;
    private final TCPNetworkModule networkModule;
    private final SharedList sharedList;
    private final LogRegistry logRegistry;

    private DefaultListModel<String> localListModel;
    private DefaultListModel<String> remoteListModel;
    private DefaultListModel<String> peersListModel;

    // NUEVO: Label de estado para notificaciones
    private JLabel statusLabel;

    public ClientGUI(NameServer nameServer, TCPNetworkModule networkModule,
            SharedList sharedList, LogRegistry logRegistry) {
        this.nameServer = nameServer;
        this.networkModule = networkModule;
        this.sharedList = sharedList;
        this.logRegistry = logRegistry;

        initializeGUI();
        updateLists();

        // Timer para actualizar cada 3 segundos (respaldo)
        new Timer(3000, e -> updateLists()).start();

        // NUEVO: Registrar listener en NameServer para notificación inmediata
        // cuando un peer remoto comparte/actualiza sus archivos
        nameServer.setRemoteFilesListener((peerId, files) -> {
            // Ejecutar en el hilo de Swing (EDT)
            SwingUtilities.invokeLater(() -> {
                updateLists();
                showPeerNotification(peerId, files);
                // FIX: Registrar en log con el componente correcto
                logRegistry.info("GUI", "📡 Archivos recibidos de " + peerId + ": " + files);
            });
        });
    }

    private void initializeGUI() {
        setTitle("P2P File Sharing - " + networkModule.getNodeId());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(850, 550);
        setLocationRelativeTo(null);

        JTabbedPane tabbedPane = new JTabbedPane();

        // ── Panel de archivos ──────────────────────────────────────────────
        JPanel filesPanel = new JPanel(new BorderLayout(5, 5));
        filesPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel listsPanel = new JPanel(new GridLayout(1, 2, 5, 5));

        // Archivos locales
        JPanel localPanel = new JPanel(new BorderLayout());
        localPanel.setBorder(BorderFactory.createTitledBorder("📁 Mis Archivos"));
        localListModel = new DefaultListModel<>();
        JList<String> localList = new JList<>(localListModel);
        localList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        localPanel.add(new JScrollPane(localList), BorderLayout.CENTER);

        JButton shareBtn = new JButton("Compartir Archivo");
        shareBtn.addActionListener(e -> shareFile());
        localPanel.add(shareBtn, BorderLayout.SOUTH);

        // Archivos remotos — MEJORADO: muestra "archivo ← peer"
        JPanel remotePanel = new JPanel(new BorderLayout());
        remotePanel.setBorder(
                BorderFactory.createTitledBorder("🌐 Archivos de la Red  (archivo  ←  peer)"));
        remoteListModel = new DefaultListModel<>();
        JList<String> remoteList = new JList<>(remoteListModel);
        remoteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Resaltar entradas con color diferente para distinguir peers
        remoteList.setCellRenderer(new RemoteFileCellRenderer());
        remotePanel.add(new JScrollPane(remoteList), BorderLayout.CENTER);

        JButton refreshBtn = new JButton("Actualizar");
        refreshBtn.addActionListener(e -> {
            updateLists();
            logRegistry.info("GUI", "Lista actualizada manualmente");
        });
        remotePanel.add(refreshBtn, BorderLayout.SOUTH);

        listsPanel.add(localPanel);
        listsPanel.add(remotePanel);

        // NUEVO: Barra de estado en la parte inferior
        statusLabel = new JLabel("  ✅ Sistema P2P listo");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
        statusLabel.setForeground(new Color(0, 120, 0));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));

        filesPanel.add(listsPanel, BorderLayout.CENTER);
        filesPanel.add(statusLabel, BorderLayout.SOUTH);

        // ── Panel de peers ─────────────────────────────────────────────────
        JPanel peersPanel = new JPanel(new BorderLayout());
        peersPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        peersListModel = new DefaultListModel<>();
        JList<String> peersList = new JList<>(peersListModel);
        peersPanel.add(new JScrollPane(peersList), BorderLayout.CENTER);

        JButton connectBtn = new JButton("Conectar a Peer");
        connectBtn.addActionListener(e -> showConnectDialog());
        peersPanel.add(connectBtn, BorderLayout.SOUTH);

        tabbedPane.addTab("Archivos", filesPanel);
        tabbedPane.addTab("Peers", peersPanel);

        add(tabbedPane);
    }

    /** NUEVO: Muestra notificación temporal cuando un peer comparte archivos */
    private void showPeerNotification(String peerId, List<String> files) {
        String msg = "📡 " + peerId + " comparte: " + files.toString();
        statusLabel.setText("  " + msg);
        statusLabel.setForeground(new Color(0, 100, 200));

        // Volver al estado normal después de 4 segundos
        new Timer(4000, e -> {
            statusLabel.setText("  ✅ Sistema P2P listo");
            statusLabel.setForeground(new Color(0, 120, 0));
            ((Timer) e.getSource()).stop();
        }).start();
    }

    private void shareFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(new File("."));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                File dest = FileUtils.getSharedFile(file.getName());
                FileUtils.copyFile(file, dest);
                sharedList.addFile(file.getName());

                // FIX: Registrar en log (genera las entradas "GUI" que aparecen en el log)
                logRegistry.info("GUI", "✅ Archivo compartido: " + file.getName());

                // Anunciar a todos los peers
                broadcastSharedFiles();

                // FIX: Log del anuncio
                logRegistry.info("GUI",
                        "📢 Anunciando " + sharedList.getSharedFiles().size() +
                                " archivos compartidos a la red: " + sharedList.getSharedFiles());

                updateLists();
                statusLabel.setText("  ✅ Compartido: " + file.getName());
                statusLabel.setForeground(new Color(0, 120, 0));

            } catch (Exception ex) {
                logRegistry.error("GUI", "Error compartiendo archivo: " + ex.getMessage());
                JOptionPane.showMessageDialog(this,
                        "❌ Error: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void broadcastSharedFiles() {
        Message msg = new Message(MessageType.PEER_ANNOUNCE, networkModule.getNodeId());
        msg.addPayload("sharedFiles", new ArrayList<>(sharedList.getSharedFiles()));
        networkModule.broadcast(msg);
    }

    private void showConnectDialog() {
        String host = JOptionPane.showInputDialog(this,
                "Dirección IP del peer (ej: 192.168.1.100):");
        if (host != null && !host.trim().isEmpty()) {
            logRegistry.info("GUI", "Intentando conectar a peer: " + host.trim());
            networkModule.connectToPeer(host.trim());
        }
    }

    private void updateLists() {
        // FIX: Siempre actualizar en el EDT
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateLists);
            return;
        }

        // Actualizar archivos locales
        localListModel.clear();
        for (String file : sharedList.getSharedFiles()) {
            localListModel.addElement(file);
        }

        // MEJORADO: Archivos remotos con información del peer (archivo ← peer)
        remoteListModel.clear();
        for (String entry : nameServer.getRemoteFilesWithPeerInfo()) {
            remoteListModel.addElement(entry);
        }

        // Actualizar peers
        peersListModel.clear();
        for (String peerId : networkModule.getPeers().keySet()) {
            peersListModel.addElement("🔗 " + peerId);
        }

        setTitle("P2P File Sharing - " + networkModule.getNodeId() +
                " | Peers: " + networkModule.getPeerCount() +
                " | Locales: " + sharedList.getSharedFiles().size() +
                " | Remotos: " + nameServer.getRemoteFiles().size());
    }

    /**
     * NUEVO: Renderer personalizado para resaltar archivos remotos con colores
     * según el peer que los comparte.
     */
    private static class RemoteFileCellRenderer extends DefaultListCellRenderer {
        // Colores para distinguir visualmente entradas de distintos peers
        private static final Color[] PEER_COLORS = {
                new Color(230, 240, 255),
                new Color(230, 255, 240),
                new Color(255, 245, 220),
                new Color(250, 230, 255),
        };

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (!isSelected && value != null) {
                // Colorear por índice para diferenciar visualmente los peers
                setBackground(PEER_COLORS[index % PEER_COLORS.length]);
            }
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            return this;
        }
    }
}