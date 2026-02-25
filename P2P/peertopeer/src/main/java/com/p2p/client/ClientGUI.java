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

    public ClientGUI(NameServer nameServer, TCPNetworkModule networkModule,
            SharedList sharedList, LogRegistry logRegistry) {
        this.nameServer = nameServer;
        this.networkModule = networkModule;
        this.sharedList = sharedList;
        this.logRegistry = logRegistry;

        initializeGUI();
        updateLists();

        // Timer para actualizar cada 3 segundos
        new Timer(3000, e -> updateLists()).start();
    }

    private void initializeGUI() {
        setTitle("P2P File Sharing - " + networkModule.getNodeId());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 500);
        setLocationRelativeTo(null);

        JTabbedPane tabbedPane = new JTabbedPane();

        // Panel de archivos
        JPanel filesPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        filesPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Archivos locales
        JPanel localPanel = new JPanel(new BorderLayout());
        localPanel.setBorder(BorderFactory.createTitledBorder("📁 Mis Archivos"));
        localListModel = new DefaultListModel<>();
        JList<String> localList = new JList<>(localListModel);
        localPanel.add(new JScrollPane(localList), BorderLayout.CENTER);

        JButton shareBtn = new JButton("Compartir Archivo");
        shareBtn.addActionListener(e -> shareFile());
        localPanel.add(shareBtn, BorderLayout.SOUTH);

        // Archivos remotos
        JPanel remotePanel = new JPanel(new BorderLayout());
        remotePanel.setBorder(BorderFactory.createTitledBorder("🌐 Archivos de la Red"));
        remoteListModel = new DefaultListModel<>();
        JList<String> remoteList = new JList<>(remoteListModel);
        remotePanel.add(new JScrollPane(remoteList), BorderLayout.CENTER);

        JButton refreshBtn = new JButton("Actualizar");
        refreshBtn.addActionListener(e -> updateLists());
        remotePanel.add(refreshBtn, BorderLayout.SOUTH);

        filesPanel.add(localPanel);
        filesPanel.add(remotePanel);

        // Panel de peers
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

    private void shareFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(new File("."));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                File dest = FileUtils.getSharedFile(file.getName());
                FileUtils.copyFile(file, dest);
                sharedList.addFile(file.getName());

                // Anunciar a todos los peers
                broadcastSharedFiles();

                updateLists();
                JOptionPane.showMessageDialog(this, "✅ Archivo compartido: " + file.getName());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "❌ Error: " + ex.getMessage());
            }
        }
    }

    private void broadcastSharedFiles() {
        Message msg = new Message(MessageType.PEER_ANNOUNCE, networkModule.getNodeId());
        msg.addPayload("sharedFiles", new ArrayList<>(sharedList.getSharedFiles()));
        networkModule.broadcast(msg);
    }

    private void showConnectDialog() {
        String host = JOptionPane.showInputDialog(this, "Dirección IP del peer:");
        if (host != null && !host.trim().isEmpty()) {
            networkModule.connectToPeer(host.trim());
        }
    }

    private void updateLists() {
        // Actualizar archivos locales
        localListModel.clear();
        for (String file : sharedList.getSharedFiles()) {
            localListModel.addElement(file);
        }

        // Actualizar archivos remotos
        remoteListModel.clear();
        for (String file : nameServer.getRemoteFiles()) {
            remoteListModel.addElement(file);
        }

        // Actualizar peers
        peersListModel.clear();
        for (String peerId : networkModule.getPeers().keySet()) {
            peersListModel.addElement(peerId);
        }

        setTitle("P2P File Sharing - " + networkModule.getNodeId() +
                " | Peers: " + networkModule.getPeerCount() +
                " | Locales: " + sharedList.getSharedFiles().size() +
                " | Remotos: " + nameServer.getRemoteFiles().size());
    }
}