package com.p2p.client;

import com.p2p.nameserver.NameServer;
import com.p2p.network.FileTransferTCP;
import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.shared.LogRegistry;
import com.p2p.shared.SharedList;
import com.p2p.metadata.FileMetadata;
import com.p2p.metadata.MetadataStore;
import com.p2p.utils.FileUtils;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class ClientGUI extends JFrame {

    private final NameServer       nameServer;
    private final TCPNetworkModule networkModule;
    private final SharedList       sharedList;
    private final MetadataStore    metadataStore;
    private final LogRegistry      logRegistry;

    private DefaultListModel<String> sharedListModel;
    private DefaultListModel<String> peersListModel;
    private JList<String>  sharedFilesList;
    private JList<String>  peersList;
    private JTextArea      logArea;
    private JLabel         statusLabel;

    // ── Visor de logs distribuido ──────────────────────────────────────
    private JTextArea      distLogArea;
    private JTextField     distLogFilter;
    /** Logs remotos recibidos de otros nodos (peerId → líneas). */
    private final Map<String, List<String>> remoteLogCache = new LinkedHashMap<>();

    public ClientGUI(NameServer nameServer, TCPNetworkModule networkModule,
            SharedList sharedList, MetadataStore metadataStore, LogRegistry logRegistry) {
        this.nameServer    = nameServer;
        this.networkModule = networkModule;
        this.sharedList    = sharedList;
        this.metadataStore = metadataStore;
        this.logRegistry   = logRegistry;

        // Registrar listener para recibir respuestas de logs remotos
        networkModule.addListener(new RemoteLogListener());

        initializeGUI();
        refreshAll();

        nameServer.addFileListListener(() -> SwingUtilities.invokeLater(this::refreshAll));

        // Actualizar lista cada 3 s y logs locales cada 2 s
        new Timer(3000, e -> SwingUtilities.invokeLater(this::refreshAll)).start();
        new Timer(2000, e -> SwingUtilities.invokeLater(this::refreshLocalLog)).start();
    }

    // ── Parseo ───────────────────────────────────────────────────────────

    private String parseFilename(String item) {
        int i = item.lastIndexOf("] ");
        return (i >= 0) ? item.substring(i + 2).trim() : item.trim();
    }

    private String parsePeerOwner(String item) {
        int s = item.indexOf('[') + 1;
        int e = item.indexOf(']');
        if (s > 0 && e > s) return item.substring(s, e).split(",")[0].trim();
        return null;
    }

    private boolean isRemoteItem(String item) {
        return item.startsWith("🌐");
    }

    // ── Refresco ─────────────────────────────────────────────────────────

    private void refreshAll() {
        peersListModel.clear();
        networkModule.getPeers().forEach((peerId, conn) ->
                peersListModel.addElement(peerId + (conn.isConnected() ? "  ✓" : "  ✗")));

        sharedListModel.clear();
        for (String f : sharedList.getSharedFiles())
            sharedListModel.addElement("📄 [Local] " + f);

        Set<String> connectedPeers = networkModule.getPeers().keySet();
        nameServer.getFileLocations().forEach((filename, owners) -> {
            List<String> active = new ArrayList<>();
            for (String owner : owners)
                if (connectedPeers.contains(owner)) active.add(owner);
            if (!active.isEmpty())
                sharedListModel.addElement("🌐 [" + String.join(", ", active) + "] " + filename);
        });

        statusLabel.setText("🟢 Peers: " + networkModule.getPeerCount()
                + " | Archivos locales: " + sharedList.getSharedFiles().size());
    }

    /** Actualiza el área de log local con las entradas nuevas. */
    private void refreshLocalLog() {
        List<LogRegistry.LogEntry> entries = logRegistry.getRecentLogs();
        if (entries.isEmpty()) return;
        // Solo mostrar las últimas 200 líneas para no sobrecargar
        int start = Math.max(0, entries.size() - 200);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < entries.size(); i++) {
            LogRegistry.LogEntry e = entries.get(i);
            sb.append(String.format("[%s] %s %-15s: %s%n",
                    e.timestamp, e.level, e.component, e.message));
        }
        String content = sb.toString();
        // Solo actualizar si hay contenido nuevo
        if (!logArea.getText().endsWith(entries.get(entries.size()-1).message + "\n")) {
            logArea.setText(content);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    // ── Descargar ─────────────────────────────────────────────────────────

    private void downloadSelectedFile() {
        String selected = sharedFilesList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Selecciona un archivo.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!isRemoteItem(selected)) {
            JOptionPane.showMessageDialog(this, "Ese archivo ya está en tu nodo.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String filename    = parseFilename(selected);
        String ownerPeerId = parsePeerOwner(selected);
        if (ownerPeerId == null) { log("No se pudo determinar el peer de: " + filename); return; }

        log("⬇ Descargando '" + filename + "' de " + ownerPeerId + "...");
        new Thread(() -> {
            try {
                FileTransferTCP.DownloadResult dlResult =
                        FileTransferTCP.downloadFile(ownerPeerId, filename, "shared");
                File dest = dlResult.file;
                sharedList.addFile(dest.getName());
                metadataStore.addMetadata(FileMetadata.fromFile(dest));
                SwingUtilities.invokeLater(() -> { log("✅ Descargado: " + dest.getName()); refreshAll(); });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> log("❌ Error descargando: " + ex.getMessage()));
            }
        }).start();
    }

    // ── Ver / Editar ──────────────────────────────────────────────────────

    private void viewSelectedFile() {
        String selected = sharedFilesList.getSelectedValue();
        if (selected == null) {
            JOptionPane.showMessageDialog(this, "Selecciona un archivo.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String filename    = parseFilename(selected);
        boolean remote     = isRemoteItem(selected);
        String ownerPeerId = remote ? parsePeerOwner(selected) : null;
        new FileEditor(this, filename, nameServer, remote, ownerPeerId, networkModule).setVisible(true);
    }

    // ── Compartir ─────────────────────────────────────────────────────────

    private void shareFile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();
        File dest = FileUtils.getSharedFile(file.getName());
        try {
            FileUtils.copyFile(file, dest);
            sharedList.addFile(file.getName());
            metadataStore.addMetadata(FileMetadata.fromFile(dest));
            Message ann = new Message(MessageType.PEER_ANNOUNCE, networkModule.getNodeId());
            ann.addPayload("sharedFiles", sharedList.getSharedFiles());
            networkModule.broadcast(ann);
            log("📤 Compartido: " + file.getName());
            refreshAll();
        } catch (Exception e) {
            log("Error compartiendo: " + e.getMessage());
        }
    }

    // ── Construcción GUI ──────────────────────────────────────────────────

    private void initializeGUI() {
        setTitle("P2P File Sharing - Nodo: " + networkModule.getNodeId());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(980, 680);
        setLocationRelativeTo(null);

        JPanel main = new JPanel(new BorderLayout(10, 10));
        main.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        main.add(buildTopPanel(),    BorderLayout.NORTH);
        main.add(buildCenterTabs(),  BorderLayout.CENTER);
        add(main);
        buildMenuBar();
    }

    private JPanel buildTopPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setBorder(BorderFactory.createEtchedBorder());
        statusLabel = new JLabel("Iniciando...");
        statusLabel.setFont(new Font("Arial", Font.BOLD, 12));
        JButton refresh = new JButton("🔄 Actualizar");
        refresh.addActionListener(e -> refreshAll());
        JButton connect = new JButton("🔗 Conectar a Peer");
        connect.addActionListener(e -> showConnectDialog());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btns.add(connect); btns.add(refresh);
        p.add(statusLabel, BorderLayout.WEST);
        p.add(btns, BorderLayout.EAST);
        return p;
    }

    /**
     * Panel central con pestañas:
     *   1. Archivos + Peers + Log local (vista principal)
     *   2. Visor de logs distribuido
     */
    private JTabbedPane buildCenterTabs() {
        JTabbedPane tabs = new JTabbedPane();

        // ── Pestaña 1: Vista principal ──
        JPanel main = new JPanel(new BorderLayout(6, 6));
        JSplitPane topSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        topSplit.setResizeWeight(0.65);
        topSplit.setLeftComponent(buildFilesPanel());
        topSplit.setRightComponent(buildPeersPanel());

        JSplitPane fullSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        fullSplit.setResizeWeight(0.6);
        fullSplit.setTopComponent(topSplit);
        fullSplit.setBottomComponent(buildLocalLogPanel());

        main.add(fullSplit, BorderLayout.CENTER);
        tabs.addTab("📁 Archivos y Red", main);

        // ── Pestaña 2: Logs distribuidos ──
        tabs.addTab("📡 Logs Distribuidos", buildDistributedLogPanel());

        return tabs;
    }

    private JPanel buildFilesPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setBorder(BorderFactory.createTitledBorder("📁 Archivos en la Red"));
        sharedListModel = new DefaultListModel<>();
        sharedFilesList = new JList<>(sharedListModel);
        sharedFilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        p.add(new JScrollPane(sharedFilesList), BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout());
        JButton dl    = new JButton("⬇ Descargar");  dl.addActionListener(e -> downloadSelectedFile());
        JButton view  = new JButton("👁 Ver/Editar"); view.addActionListener(e -> viewSelectedFile());
        JButton share = new JButton("📤 Compartir");  share.addActionListener(e -> shareFile());
        btns.add(dl); btns.add(view); btns.add(share);
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildPeersPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setBorder(BorderFactory.createTitledBorder("🖧 Peers Conectados"));
        peersListModel = new DefaultListModel<>();
        peersList      = new JList<>(peersListModel);
        p.add(new JScrollPane(peersList), BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout());
        JButton disco = new JButton("Desconectar");
        disco.addActionListener(e -> {
            String sel = peersList.getSelectedValue();
            if (sel != null) {
                String peerId = sel.split("  ")[0];
                TCPNetworkModule.PeerConnection conn = networkModule.getPeers().get(peerId);
                if (conn != null) networkModule.closeConnection(conn);
                refreshAll();
            }
        });
        btns.add(disco);
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildLocalLogPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("📋 Registro de Actividad Local"));
        logArea = new JTextArea(8, 80);
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        p.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return p;
    }

    /**
     * Pestaña 2 — Visor de logs distribuido.
     *
     * Permite:
     *  - Solicitar logs de TODOS los peers conectados.
     *  - Combinar los logs locales y remotos en una sola vista ordenada por tiempo.
     *  - Filtrar por texto (ej: nombre de archivo, componente) para trazar una operación.
     */
    private JPanel buildDistributedLogPanel() {
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // ── Barra de controles ──
        JPanel controls = new JPanel(new BorderLayout(5, 5));
        controls.setBorder(BorderFactory.createTitledBorder("Filtrar y Actualizar"));

        JPanel filterRow = new JPanel(new BorderLayout(5, 0));
        filterRow.add(new JLabel("🔍 Filtrar por texto: "), BorderLayout.WEST);
        distLogFilter = new JTextField();
        filterRow.add(distLogFilter, BorderLayout.CENTER);
        JButton applyFilter = new JButton("Aplicar");
        applyFilter.addActionListener(e -> renderDistLog());
        distLogFilter.addActionListener(e -> renderDistLog());
        filterRow.add(applyFilter, BorderLayout.EAST);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton fetchAll = new JButton("📡 Obtener logs de todos los peers");
        fetchAll.addActionListener(e -> fetchRemoteLogs());
        JButton clear = new JButton("🗑 Limpiar");
        clear.addActionListener(e -> { remoteLogCache.clear(); renderDistLog(); });
        btnRow.add(fetchAll); btnRow.add(clear);

        controls.add(filterRow, BorderLayout.CENTER);
        controls.add(btnRow, BorderLayout.SOUTH);

        // ── Área de logs ──
        distLogArea = new JTextArea();
        distLogArea.setEditable(false);
        distLogArea.setFont(new Font("Monospaced", Font.PLAIN, 11));

        // ── Info ──
        JLabel info = new JLabel(
            "<html><i>Los logs muestran la traza de operaciones de todos los nodos. " +
            "Filtra por un nombre de archivo para ver todos los eventos relacionados.</i></html>");
        info.setForeground(Color.GRAY);
        info.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        p.add(controls,              BorderLayout.NORTH);
        p.add(new JScrollPane(distLogArea), BorderLayout.CENTER);
        p.add(info,                  BorderLayout.SOUTH);
        return p;
    }

    /**
     * Solicita los logs recientes a todos los peers conectados.
     * Las respuestas llegan de forma asíncrona a través de RemoteLogListener.
     */
    private void fetchRemoteLogs() {
        int peers = networkModule.getPeerCount();
        if (peers == 0) {
            JOptionPane.showMessageDialog(this,
                "No hay peers conectados.", "Aviso", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Message req = new Message(MessageType.LOG_REQUEST, networkModule.getNodeId());
        networkModule.broadcast(req);
        log("📡 Solicitando logs a " + peers + " peers...");

        // Incluir logs locales inmediatamente
        List<String> localLines = new ArrayList<>();
        for (LogRegistry.LogEntry e : logRegistry.getRecentLogs())
            localLines.add(String.format("[%s] %s %-15s: %s",
                    e.timestamp, e.level, e.component, e.message));
        remoteLogCache.put("LOCAL (" + networkModule.getNodeId() + ")", localLines);
        renderDistLog();
    }

    /**
     * Renderiza el visor distribuido combinando logs locales y remotos,
     * aplicando el filtro si está definido.
     */
    private void renderDistLog() {
        String filter = distLogFilter.getText().trim().toLowerCase();
        List<String> allLines = new ArrayList<>();

        // Logs locales siempre incluidos
        String localKey = "LOCAL (" + networkModule.getNodeId() + ")";
        if (!remoteLogCache.containsKey(localKey)) {
            List<String> localLines = new ArrayList<>();
            for (LogRegistry.LogEntry e : logRegistry.getRecentLogs())
                localLines.add(String.format("[%s] %s %-15s: %s",
                        e.timestamp, e.level, e.component, e.message));
            remoteLogCache.put(localKey, localLines);
        }

        // Combinar todos los logs con encabezado por nodo
        for (Map.Entry<String, List<String>> entry : remoteLogCache.entrySet()) {
            allLines.add("═══ NODO: " + entry.getKey() + " ═══");
            for (String line : entry.getValue()) {
                if (filter.isEmpty() || line.toLowerCase().contains(filter))
                    allLines.add(line);
            }
            allLines.add("");
        }

        StringBuilder sb = new StringBuilder();
        if (allLines.isEmpty()) {
            sb.append("Sin entradas. Usa '📡 Obtener logs' para cargar logs de los peers.");
        } else {
            for (String line : allLines) sb.append(line).append("\n");
        }
        distLogArea.setText(sb.toString());
        distLogArea.setCaretPosition(0);
    }

    // ── Menú y diálogos ──────────────────────────────────────────────────

    private void buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("Archivo");
        JMenuItem exit = new JMenuItem("Salir");
        exit.addActionListener(e -> { networkModule.shutdown(); System.exit(0); });
        file.add(exit);
        JMenu help = new JMenu("Ayuda");
        JMenuItem about = new JMenuItem("Acerca de");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "P2P File Sharing v2.0\nNodo: " + networkModule.getNodeId(), "Info",
                JOptionPane.INFORMATION_MESSAGE));
        help.add(about);
        bar.add(file); bar.add(help);
        setJMenuBar(bar);
    }

    private void showConnectDialog() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 5, 5));
        panel.add(new JLabel("IP del peer (solo IP, sin puerto):"));
        JTextField ip = new JTextField(); ip.selectAll();
        panel.add(ip);
        panel.add(new JLabel("El puerto 8888 se usa automáticamente"));

        if (JOptionPane.showConfirmDialog(this, panel, "Conectar a Peer",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            String addr = ip.getText().trim().replaceAll(":.*", "");
            if (!addr.isEmpty()) {
                log("Conectando a: " + addr + "...");
                new Thread(() -> {
                    networkModule.connectToPeer(addr);
                    SwingUtilities.invokeLater(() -> { log("Conexión procesada: " + addr); refreshAll(); });
                }, "connect-thread").start();
            }
        }
    }

    private void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + ts + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
        logRegistry.info("GUI", msg);
    }

    // ── Listener para respuestas de logs remotos ──────────────────────────

    /**
     * Escucha mensajes LOG_RESPONSE de peers remotos y los almacena para
     * mostrarlos en el visor distribuido.
     */
    private class RemoteLogListener implements TCPNetworkModule.MessageListener {
        @Override
        public void onMessage(Message message, TCPNetworkModule.PeerConnection source) {
            if (message.getType() != MessageType.LOG_RESPONSE) return;

            String nodeId = (String) message.getPayload("nodeId");
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) message.getPayload("logs");
            if (nodeId == null || lines == null) return;

            SwingUtilities.invokeLater(() -> {
                remoteLogCache.put("REMOTO (" + nodeId + ")", lines);
                renderDistLog();
                log("📡 Logs recibidos de: " + nodeId + " (" + lines.size() + " entradas)");
            });
        }

        @Override public void onPeerConnected(String peerId) {}
        @Override public void onPeerDisconnected(String peerId) {
            SwingUtilities.invokeLater(() -> {
                remoteLogCache.remove("REMOTO (" + peerId + ")");
                renderDistLog();
            });
        }
    }
}
