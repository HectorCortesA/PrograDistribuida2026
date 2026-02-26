package com.p2p.client;

import com.p2p.nameserver.NameServer;
import com.p2p.network.FileTransferTCP;
import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.repository.CopyRepository;
import com.p2p.utils.FileUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Editor/Visor de archivos P2P.
 *
 * Flujo para archivos REMOTOS (Unit of Work):
 *   1. Descarga → CopyRepository.createCopy() registra la copia local.
 *   2. Edición  → modificaciones en memoria.
 *   3. Guardar  → detecta si hubo cambios (checksum). Si no cambió, no sube nada.
 *                  Si cambió, sube al dueño con uploadFile().
 *   4. Cerrar   → si fue remoto, ELIMINA la copia local de shared/ y notifica
 *                  al dueño con FILE_RETURNED para limpiar sus registros.
 */
public class FileEditor extends JFrame {

    private static final Set<String> IMG_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif"));

    private final JFrame           parent;
    private       String           filename;
    private final NameServer       nameServer;
    private final boolean          isRemote;
    private final String           ownerPeerId;
    private final TCPNetworkModule networkModule;
    private final CopyRepository   copyRepository;

    private JTextArea textArea;
    private JButton   saveButton;
    private JCheckBox readOnlyBox;
    private JLabel    statusLabel;
    private JPanel    contentPanel;
    private File      currentFile;
    private boolean   modified    = false;

    /** lastModified del archivo cuando fue descargado del dueño. */
    private long   originalLastModified = 0L;
    /** Checksum del contenido ORIGINAL al cargar (para detectar si realmente cambió). */
    private String originalChecksum     = null;
    /** ID de la copia en CopyRepository (si es remoto). */
    private String copyId               = null;
    /** true = el upload al dueño fue exitoso en esta sesión. */
    private boolean syncedWithOwner = false;

    // ── Constructores ─────────────────────────────────────────────────────

    /** Archivos locales */
    public FileEditor(JFrame parent, String filename, NameServer ns) {
        this(parent, filename, ns, false, null, ns.getNetworkModule());
    }

    /** Archivos locales o remotos */
    public FileEditor(JFrame parent, String filename, NameServer ns,
                      boolean isRemote, String ownerPeerId, TCPNetworkModule nm) {
        this.parent         = parent;
        this.filename       = filename;
        this.nameServer     = ns;
        this.isRemote       = isRemote;
        this.ownerPeerId    = ownerPeerId;
        this.networkModule  = nm;
        this.copyRepository = ns.getCopyRepository();
        buildUI();
        loadContent();
    }

    private boolean isImage(String name) {
        int d = name.lastIndexOf('.');
        return d >= 0 && IMG_EXT.contains(name.substring(d + 1).toLowerCase());
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void buildUI() {
        setTitle("Visor P2P - " + filename);
        setSize(860, 640);
        setLocationRelativeTo(parent);

        JPanel main = new JPanel(new BorderLayout(5, 5));
        main.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        if (!isImage(filename)) {
            JToolBar tb = new JToolBar(); tb.setFloatable(false);
            saveButton = new JButton("💾 Guardar"); saveButton.addActionListener(e -> saveFile());
            JButton saveAs = new JButton("Guardar Como"); saveAs.addActionListener(e -> saveFileAs());
            readOnlyBox = new JCheckBox("Solo Lectura"); readOnlyBox.addActionListener(e -> toggleRO());
            tb.add(saveButton); tb.add(saveAs); tb.addSeparator(); tb.add(readOnlyBox);
            if (isRemote) {
                JLabel lbl = new JLabel("  🌐 Archivo remoto — Guardar sincroniza con el dueño");
                lbl.setForeground(new Color(0, 100, 200));
                tb.add(lbl);
            }
            main.add(tb, BorderLayout.NORTH);
        }

        contentPanel = new JPanel(new BorderLayout());
        main.add(contentPanel, BorderLayout.CENTER);

        statusLabel = new JLabel("Cargando...");
        statusLabel.setForeground(Color.BLUE);
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Cerrar"); close.addActionListener(e -> close());
        btnRow.add(close);
        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(btnRow, BorderLayout.EAST);
        main.add(bottom, BorderLayout.SOUTH);

        add(main);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ctrl S"), "save");
        getRootPane().getActionMap().put("save", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { saveFile(); }
        });
    }

    // ── Carga ─────────────────────────────────────────────────────────────

    private void loadContent() {
        File f = FileUtils.getSharedFile(filename);
        if (!f.exists()) f = FileUtils.getLocalFile(filename);

        if (f.exists()) {
            currentFile          = f;
            originalLastModified = f.lastModified();
            show(f);
            return;
        }

        if (isRemote && ownerPeerId != null) {
            setStatus("⏳ Descargando desde " + ownerPeerId + "...", Color.ORANGE);
            showSpinner();
            downloadAndShow();
        } else {
            showPlaceholder("Archivo no encontrado localmente.\n" +
                    "Usa '⬇ Descargar' en la ventana principal.");
        }
    }

    private void downloadAndShow() {
        new Thread(() -> {
            try {
                FileTransferTCP.DownloadResult result =
                        FileTransferTCP.downloadFile(ownerPeerId, filename, "shared");
                nameServer.getSharedListRef().addFile(result.file.getName());

                // ── Registrar copia en CopyRepository (Unit of Work) ──
                try {
                    copyRepository.createCopy(filename, result.file.getPath(), ownerPeerId);
                    // Buscar el copyId que se acaba de crear
                    CopyRepository.FileCopy latestCopy = copyRepository.getLatestCopy(filename);
                    if (latestCopy != null) copyId = latestCopy.getCopyId();
                } catch (Exception ex) {
                    System.err.println("⚠ No se pudo registrar copia: " + ex.getMessage());
                }

                final long origTs = result.originalLastModified;

                SwingUtilities.invokeLater(() -> {
                    currentFile          = result.file;
                    originalLastModified = origTs;
                    show(result.file);
                    setStatus("✅ Archivo cargado: " + result.file.getName(), new Color(0, 128, 0));
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        setStatus("❌ Error al descargar: " + ex.getMessage(), Color.RED));
            }
        }).start();
    }

    private void show(File f) {
        if (isImage(f.getName())) showImage(f);
        else                      showText(f);
    }

    private void showImage(File f) {
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) { setStatus("Formato de imagen no soportado", Color.RED); return; }
            int maxW = 800, maxH = 560;
            double ratio = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
            Image scaled = (ratio < 1.0)
                    ? img.getScaledInstance((int)(img.getWidth()*ratio), (int)(img.getHeight()*ratio), Image.SCALE_SMOOTH)
                    : img;
            JLabel lbl = new JLabel(new ImageIcon(scaled));
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            contentPanel.removeAll();
            contentPanel.add(new JScrollPane(lbl), BorderLayout.CENTER);
            contentPanel.revalidate(); contentPanel.repaint();
            setStatus("🖼 " + f.getName() + " (" + img.getWidth() + "×" + img.getHeight() + " px)",
                    new Color(0, 128, 0));
        } catch (IOException e) {
            setStatus("Error mostrando imagen: " + e.getMessage(), Color.RED);
        }
    }

    private void showText(File f) {
        if (textArea == null) {
            textArea = new JTextArea();
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            textArea.setTabSize(4);
            textArea.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e)  { markMod(); }
                public void removeUpdate(DocumentEvent e)  { markMod(); }
                public void changedUpdate(DocumentEvent e) { markMod(); }
            });
            contentPanel.removeAll();
            contentPanel.add(new JScrollPane(textArea), BorderLayout.CENTER);
            contentPanel.revalidate();
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            String content = sb.toString();
            textArea.setText(content);
            textArea.setCaretPosition(0);
            // Guardar checksum del contenido original para detectar cambios reales
            originalChecksum = computeChecksum(content);
            modified = false; updateTitle();
            setStatus("📄 " + f.getName(), Color.BLUE);
        } catch (IOException e) {
            setStatus("Error leyendo: " + e.getMessage(), Color.RED);
        }
    }

    private void showSpinner() {
        JLabel lbl = new JLabel("⏳ Descargando...", SwingConstants.CENTER);
        lbl.setFont(new Font("Arial", Font.BOLD, 16));
        contentPanel.removeAll();
        contentPanel.add(lbl, BorderLayout.CENTER);
        contentPanel.revalidate();
    }

    private void showPlaceholder(String msg) {
        if (textArea == null) {
            textArea = new JTextArea();
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            textArea.setEditable(false);
            contentPanel.removeAll();
            contentPanel.add(new JScrollPane(textArea), BorderLayout.CENTER);
            contentPanel.revalidate();
        }
        textArea.setText("// " + msg.replace("\n", "\n// "));
        setStatus("Archivo no disponible", Color.RED);
    }

    // ── Acciones ──────────────────────────────────────────────────────────

    private void markMod()     { modified = true; updateTitle(); }
    private void updateTitle() { setTitle("Visor P2P - " + filename + (modified ? " *" : "")); }
    private void setStatus(String msg, Color c) { statusLabel.setText(msg); statusLabel.setForeground(c); }

    private void saveFile() {
        if (textArea == null) return;
        if (readOnlyBox != null && readOnlyBox.isSelected()) {
            JOptionPane.showMessageDialog(this, "Modo solo lectura.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (currentFile == null) { saveFileAs(); return; }
        doSave(currentFile);
    }

    private void saveFileAs() {
        if (textArea == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(filename));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentFile = fc.getSelectedFile();
            filename    = currentFile.getName();
            doSave(currentFile);
            setTitle("Visor P2P - " + filename);
        }
    }

    /**
     * Guarda localmente y, si es remoto:
     *   - Compara checksum para saber si realmente cambió.
     *   - Si cambió → sube al dueño (uploadFile).
     *   - Si NO cambió → no sube nada (optimización del enunciado).
     */
    private void doSave(File f) {
        // 1. Guardar localmente
        try {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
                w.write(textArea.getText());
            }
            modified = false; updateTitle();
            setStatus("✅ Guardado localmente: " + f.getName(), new Color(0, 128, 0));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Error al guardar: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 2. Si NO es remoto, terminamos aquí
        if (!isRemote || ownerPeerId == null) return;

        // 3. Comparar checksum para detectar si realmente cambió
        String currentChecksum = computeChecksum(textArea.getText());
        if (currentChecksum != null && currentChecksum.equals(originalChecksum)) {
            setStatus("ℹ Sin cambios respecto al original — no se sincroniza.", Color.GRAY);
            syncedWithOwner = true; // "sincronizado" porque no hay nada que enviar
            return;
        }

        // 4. Hay cambios reales → subir al dueño
        setStatus("⏳ Sincronizando con el dueño (" + ownerPeerId + ")...", Color.ORANGE);
        final long snapOrigTs = originalLastModified;
        final File snapFile   = f;

        new Thread(() -> {
            try {
                String response = FileTransferTCP.uploadFile(
                        ownerPeerId, filename, snapFile, snapOrigTs);

                SwingUtilities.invokeLater(() -> {
                    if ("OK".equals(response)) {
                        syncedWithOwner  = true;
                        originalChecksum = currentChecksum; // actualizar base para próximos guardados
                        setStatus("✅ Sincronizado con " + ownerPeerId, new Color(0, 128, 0));
                    } else if (response.startsWith("CONFLICT:")) {
                        String conflictFile = response.substring(9);
                        syncedWithOwner = true; // se subió; el dueño resolverá la colisión
                        setStatus("⚠ Colisión — el dueño debe resolver", Color.RED);
                        JOptionPane.showMessageDialog(FileEditor.this,
                                "<html><b>Colisión de edición detectada.</b><br><br>" +
                                "El dueño (<b>" + ownerPeerId + "</b>) también modificó el archivo.<br><br>" +
                                "Tu versión fue guardada en el dueño como:<br>" +
                                "<code>" + conflictFile + "</code><br><br>" +
                                "El dueño debe comparar ambas versiones y elegir cuál conservar.</html>",
                                "⚠ Colisión – Resolución requerida",
                                JOptionPane.WARNING_MESSAGE);
                    } else {
                        setStatus("❌ Error al sincronizar: " + response, Color.RED);
                    }
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() ->
                        setStatus("❌ Sin conexión con " + ownerPeerId +
                                  " — cambios guardados solo localmente", Color.RED));
            }
        }).start();
    }

    private void toggleRO() {
        if (textArea == null) return;
        boolean ro = readOnlyBox.isSelected();
        textArea.setEditable(!ro);
        if (saveButton != null) saveButton.setEnabled(!ro);
        setStatus(ro ? "Solo lectura" : "Modo edición", Color.BLUE);
    }

    /**
     * Cierra el editor.
     *
     * Para archivos REMOTOS:
     *  - Notifica al dueño con FILE_RETURNED.
     *  - Elimina la copia local de shared/ (Unit of Work: limpieza al finalizar).
     */
    private void close() {
        if (modified && !syncedWithOwner) {
            int opt = JOptionPane.showConfirmDialog(this,
                    "Hay cambios sin guardar/sincronizar. ¿Cerrar de todas formas?",
                    "Cerrar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (opt != JOptionPane.YES_OPTION) return;
        }

        if (isRemote && ownerPeerId != null) {
            boolean hadChanges = !syncedWithOwner || modified;

            // Notificar al dueño que terminamos de usar el archivo
            notifyFileReturned(hadChanges);

            // ── Eliminar copia local del sistema (Unit of Work: cleanup) ──
            if (currentFile != null && currentFile.exists()) {
                boolean deleted = currentFile.delete();
                if (deleted) {
                    nameServer.getSharedListRef().removeFromList(filename);
                    System.out.println("🗑 Copia local eliminada: " + currentFile.getPath());
                }
            }
            // Limpiar del CopyRepository
            if (copyId != null) {
                try { copyRepository.deleteCopy(copyId); } catch (Exception ignored) {}
            }
        }

        dispose();
    }

    /** Envía FILE_RETURNED al dueño para que limpie sus registros. */
    private void notifyFileReturned(boolean changed) {
        try {
            Message msg = new Message(MessageType.FILE_RETURNED, networkModule.getNodeId());
            msg.addPayload("filename", filename);
            msg.addPayload("borrower", networkModule.getNodeId());
            msg.addPayload("changed",  changed);
            networkModule.sendMessage(msg, ownerPeerId);
        } catch (Exception e) {
            System.err.println("⚠ No se pudo notificar FILE_RETURNED: " + e.getMessage());
        }
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    /** Calcula un checksum MD5 del texto para detectar cambios reales. */
    private static String computeChecksum(String text) {
        if (text == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
