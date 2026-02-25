package com.p2p.client;

import com.p2p.nameserver.NameServer;
import com.p2p.utils.FileUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;

public class FileEditor extends JFrame {
    private final JFrame parent;
    private final String filename;
    private final NameServer nameServer;

    private JTextArea textArea;
    private JButton saveButton;
    private JButton closeButton;
    private JLabel statusLabel;
    private JCheckBox readOnlyCheckBox;
    private File currentFile;
    private boolean isModified = false;

    public FileEditor(JFrame parent, String filename, NameServer nameServer) {
        this.parent = parent;
        this.filename = filename;
        this.nameServer = nameServer;

        initializeEditor();
        loadFileContent();
        setupListeners();
    }

    private void initializeEditor() {
        setTitle("Editor P2P - " + filename);
        setSize(700, 500);
        setLocationRelativeTo(parent);

        // Panel principal
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Barra de herramientas
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        JButton newButton = new JButton("Nuevo");
        newButton.addActionListener(e -> newFile());

        JButton openButton = new JButton("Abrir");
        openButton.addActionListener(e -> openFile());

        JButton saveButton = new JButton("Guardar");
        saveButton.addActionListener(e -> saveFile());

        JButton saveAsButton = new JButton("Guardar Como");
        saveAsButton.addActionListener(e -> saveFileAs());

        toolBar.add(newButton);
        toolBar.add(openButton);
        toolBar.add(saveButton);
        toolBar.add(saveAsButton);
        toolBar.addSeparator();

        readOnlyCheckBox = new JCheckBox("Solo Lectura");
        readOnlyCheckBox.addActionListener(e -> toggleReadOnly());
        toolBar.add(readOnlyCheckBox);

        // Área de texto
        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setTabSize(4);
        JScrollPane scrollPane = new JScrollPane(textArea);

        // Panel inferior
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        statusLabel = new JLabel("Archivo cargado localmente");
        statusLabel.setForeground(Color.BLUE);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        this.saveButton = new JButton("Guardar");
        this.saveButton.addActionListener(e -> saveFile());

        closeButton = new JButton("Cerrar");
        closeButton.addActionListener(e -> closeEditor());

        buttonPanel.add(this.saveButton);
        buttonPanel.add(closeButton);

        bottomPanel.add(statusLabel, BorderLayout.WEST);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        // Ensamblar
        mainPanel.add(toolBar, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        add(mainPanel);

        // Atajo de teclado para guardar
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ctrl S"), "save");
        getRootPane().getActionMap().put("save", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveFile();
            }
        });
    }

    private void setupListeners() {
        textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                isModified = true;
                updateTitle();
            }

            public void removeUpdate(javswing.event.DocumentEvent e) {
                isModified = true;
                updateTitle();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                isModified = true;
                updateTitle();
            }
        });
    }

    private void updateTitle() {
        String title = "Editor P2P - " + filename;
        if (isModified) {
            title += " *";
        }
        setTitle(title);
    }

    private void loadFileContent() {
        // Intentar cargar desde archivo local primero
        currentFile = FileUtils.getLocalFile(filename);

        if (currentFile.exists()) {
            loadFromFile(currentFile);
            statusLabel.setText("Archivo cargado desde caché local");
        } else {
            // Buscar en shared
            currentFile = FileUtils.getSharedFile(filename);
            if (currentFile.exists()) {
                loadFromFile(currentFile);
                statusLabel.setText("Archivo cargado desde directorio compartido");
            } else {
                textArea.setText("// Archivo no encontrado localmente\n" +
                        "// Puede descargarlo desde la red P2P\n" +
                        "// o crear un nuevo archivo.");
                statusLabel.setText("Archivo no disponible localmente");
                readOnlyCheckBox.setSelected(true);
                textArea.setEditable(false);
            }
        }
    }

    private void loadFromFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            textArea.setText(content.toString());
            textArea.setCaretPosition(0);
            isModified = false;
            updateTitle();
        } catch (IOException e) {
            e.printStackTrace();
            statusLabel.setText("Error cargando archivo: " + e.getMessage());
        }
    }

    private void saveFile() {
        if (readOnlyCheckBox.isSelected()) {
            JOptionPane.showMessageDialog(this,
                    "El archivo está en modo solo lectura",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (currentFile == null) {
            saveFileAs();
            return;
        }

        performSave(currentFile);
    }

    private void saveFileAs() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new File(filename));

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentFile = fileChooser.getSelectedFile();
            performSave(currentFile);
        }
    }

    private void performSave(File file) {
        try {
            // Asegurar que el directorio existe
            file.getParentFile().mkdirs();

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write(textArea.getText());
            }

            isModified = false;
            updateTitle();
            statusLabel.setText("Archivo guardado: " + file.getName());

            // Si guardamos en shared, actualizar metadatos
            if (file.getPath().contains("shared")) {
                JOptionPane.showMessageDialog(this,
                        "Archivo guardado en directorio compartido.\n" +
                                "Otros peers podrán verlo después de la sincronización.",
                        "Guardado Exitoso",
                        JOptionPane.INFORMATION_MESSAGE);
            }

        } catch (IOException e) {
            e.printStackTrace();
            statusLabel.setText("Error guardando archivo: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Error guardando archivo: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void newFile() {
        int result = JOptionPane.showConfirmDialog(this,
                "¿Crear nuevo archivo? Se perderán los cambios no guardados.",
                "Nuevo Archivo",
                JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            textArea.setText("");
            currentFile = null;
            isModified = false;
            updateTitle();
            statusLabel.setText("Nuevo archivo");
        }
    }

    private void openFile() {
        if (isModified) {
            int result = JOptionPane.showConfirmDialog(this,
                    "Hay cambios no guardados. ¿Abrir otro archivo?",
                    "Confirmar",
                    JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File("."));

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentFile = fileChooser.getSelectedFile();
            loadFromFile(currentFile);
            filename = currentFile.getName();
            statusLabel.setText("Archivo abierto: " + currentFile.getName());
        }
    }

    private void toggleReadOnly() {
        textArea.setEditable(!readOnlyCheckBox.isSelected());
        saveButton.setEnabled(!readOnlyCheckBox.isSelected());

        if (readOnlyCheckBox.isSelected()) {
            statusLabel.setText("Modo solo lectura");
        } else {
            statusLabel.setText("Modo edición");
        }
    }

    private void closeEditor() {
        if (isModified) {
            int result = JOptionPane.showConfirmDialog(this,
                    "Hay cambios no guardados. ¿Salir sin guardar?",
                    "Confirmar",
                    JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                dispose();
            }
        } else {
            dispose();
        }
    }
}
