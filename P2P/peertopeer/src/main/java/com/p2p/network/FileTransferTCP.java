package com.p2p.network;

import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor permanente de archivos en puerto 8889.
 *
 * PROTOCOLO (prefijo en primer mensaje UTF):
 *   "GET:<filename>"                   → descarga al cliente
 *   "PUT:<filename>:<origTimestamp>"   → subida del cliente; detecta colisiones
 *
 * Colisión: si el archivo local fue modificado DESPUÉS de origTimestamp,
 * la versión del editor se guarda como "<filename>.CONFLICT_<ip>_<ts>"
 * y el servidor responde "CONFLICT:<conflictFilename>".
 * Sin colisión: sobreescribe el original y responde "OK".
 */
public class FileTransferTCP {

    public static final int FILE_PORT   = 8889;
    public static final int BUFFER_SIZE = 65536;
    public static final int TIMEOUT_MS  = 30_000;

    private static volatile ServerSocket serverSocket;
    private static final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "file-xfer");
        t.setDaemon(true);
        return t;
    });

    // ── SERVIDOR ─────────────────────────────────────────────────────────

    public static void startServer(String sharedDir) {
        pool.submit(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(FILE_PORT));
                System.out.println("✓ Servidor de archivos escuchando en puerto " + FILE_PORT);

                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        pool.submit(() -> handleClient(client, sharedDir));
                    } catch (SocketException e) {
                        if (!serverSocket.isClosed()) e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                System.err.println("⚠ Error en servidor de archivos: " + e.getMessage());
            }
        });
    }

    public static void stopServer() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
    }

    private static void handleClient(Socket client, String sharedDir) {
        try {
            client.setSoTimeout(TIMEOUT_MS);
            DataInputStream  dis = new DataInputStream(new BufferedInputStream(client.getInputStream()));
            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()));

            String command = dis.readUTF();

            if (command.startsWith("PUT:")) {
                // PUT:<filename>:<origTimestamp>
                String rest  = command.substring(4);
                int    colon = rest.lastIndexOf(':');
                String filename      = (colon > 0) ? rest.substring(0, colon) : rest;
                long   origTimestamp = (colon > 0) ? Long.parseLong(rest.substring(colon + 1)) : 0L;
                String senderIp      = client.getInetAddress().getHostAddress();
                handleUpload(filename, origTimestamp, senderIp, sharedDir, dis, dos);
            } else {
                // GET:<filename>  o  compatibilidad (solo nombre)
                String filename = command.startsWith("GET:") ? command.substring(4) : command;
                handleDownload(filename, sharedDir, dos);
            }

        } catch (IOException e) {
            System.err.println("Error en transferencia: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private static void handleDownload(String filename, String sharedDir,
                                       DataOutputStream dos) throws IOException {
        System.out.println("→ GET: " + filename);
        File file = new File(sharedDir, filename);
        if (!file.exists()) {
            dos.writeLong(-1);
            dos.writeLong(0);
            dos.flush();
            System.out.println("⚠ No encontrado: " + filename);
            return;
        }
        dos.writeLong(file.length());
        dos.writeLong(file.lastModified());
        dos.flush();

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE]; int n;
            while ((n = fis.read(buf)) != -1) dos.write(buf, 0, n);
            dos.flush();
        }
        System.out.println("✓ Enviado: " + filename + " (" + file.length() + " bytes)");
    }

    private static void handleUpload(String filename, long origTimestamp,
                                     String senderIp, String sharedDir,
                                     DataInputStream dis, DataOutputStream dos) throws IOException {
        System.out.println("→ PUT: " + filename + " (origTs=" + origTimestamp + ")");

        long fileSize = dis.readLong();
        if (fileSize < 0) { dos.writeUTF("ERROR:tamaño inválido"); dos.flush(); return; }

        // Leer bytes recibidos
        byte[] receivedBytes = new byte[(int) fileSize];
        int totalRead = 0;
        while (totalRead < fileSize) {
            int n = dis.read(receivedBytes, totalRead, (int)(fileSize - totalRead));
            if (n == -1) break;
            totalRead += n;
        }

        new File(sharedDir).mkdirs();
        File localFile = new File(sharedDir, filename);

        // Detectar colisión: el dueño modificó el archivo DESPUÉS de que fue prestado
        boolean collision = localFile.exists() && localFile.lastModified() > origTimestamp;

        if (collision) {
            String conflictName = filename + ".CONFLICT_" + senderIp + "_" + System.currentTimeMillis();
            try (FileOutputStream fos = new FileOutputStream(new File(sharedDir, conflictName))) {
                fos.write(receivedBytes);
            }
            System.out.println("⚠ COLISIÓN en '" + filename + "' → conflicto: " + conflictName);
            dos.writeUTF("CONFLICT:" + conflictName);
        } else {
            try (FileOutputStream fos = new FileOutputStream(localFile)) {
                fos.write(receivedBytes);
            }
            localFile.setLastModified(System.currentTimeMillis());
            System.out.println("✓ Actualizado desde " + senderIp + ": " + filename);
            dos.writeUTF("OK");
        }
        dos.flush();
    }

    // ── CLIENTE: DESCARGA ────────────────────────────────────────────────

    /**
     * Descarga un archivo y devuelve el DownloadResult con el lastModified original.
     */
    public static DownloadResult downloadFile(String peerNodeId, String filename, String destDir)
            throws IOException {
        String host = peerNodeId.contains(":") ? peerNodeId.split(":")[0] : peerNodeId;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, FILE_PORT), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream  dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            dos.writeUTF("GET:" + filename);
            dos.flush();

            long fileSize     = dis.readLong();
            long lastModified = dis.readLong();
            if (fileSize < 0) throw new IOException("El peer no tiene el archivo: " + filename);

            new File(destDir).mkdirs();
            File dest = new File(destDir, filename);
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[BUFFER_SIZE]; long remaining = fileSize; int n;
                while (remaining > 0 &&
                       (n = dis.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                    fos.write(buf, 0, n); remaining -= n;
                }
            }
            System.out.println("✓ Recibido: " + filename + " (" + fileSize + " bytes), ts=" + lastModified);
            return new DownloadResult(dest, lastModified);
        }
    }

    // ── CLIENTE: SUBIDA ──────────────────────────────────────────────────

    /**
     * Sube el archivo editado al peer dueño.
     * @param origTimestamp  lastModified cuando fue descargado (para detectar colisión)
     * @return "OK", "CONFLICT:<nombre>", o "ERROR:..."
     */
    public static String uploadFile(String peerNodeId, String filename,
                                    File editedFile, long origTimestamp) throws IOException {
        String host = peerNodeId.contains(":") ? peerNodeId.split(":")[0] : peerNodeId;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, FILE_PORT), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream  dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            dos.writeUTF("PUT:" + filename + ":" + origTimestamp);
            dos.flush();

            dos.writeLong(editedFile.length());
            try (FileInputStream fis = new FileInputStream(editedFile)) {
                byte[] buf = new byte[BUFFER_SIZE]; int n;
                while ((n = fis.read(buf)) != -1) dos.write(buf, 0, n);
                dos.flush();
            }

            String response = dis.readUTF();
            System.out.println("↩ Respuesta PUT '" + filename + "': " + response);
            return response;
        }
    }

    // ── DTO ──────────────────────────────────────────────────────────────

    /** Resultado de una descarga: archivo local + lastModified del dueño. */
    public static class DownloadResult {
        public final File file;
        /** lastModified del archivo en el peer dueño en el momento de la descarga. */
        public final long originalLastModified;

        public DownloadResult(File file, long originalLastModified) {
            this.file                 = file;
            this.originalLastModified = originalLastModified;
        }
    }
}
