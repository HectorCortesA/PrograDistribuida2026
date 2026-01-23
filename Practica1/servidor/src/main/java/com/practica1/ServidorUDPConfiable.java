package com.practica1;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class ServidorUDPConfiable {
    private static final int PUERTO = 22000;
    private static final String DIRECTORIO_ARCHIVOS = "/Users/hectorcortes/Downloads";
    private static final Map<String, HiloCliente> clientesActivos = new ConcurrentHashMap<>();
    private static FileWriter logWriter;
    private static final String LOG_FILE = "servidor_udp.log";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        // Inicializar archivo de log
        try {
            logWriter = new FileWriter(LOG_FILE, true);
            escribirLog("SERVIDOR INICIADO");
        } catch (IOException e) {
            System.err.println("Error creando log: " + e.getMessage());
            return;
        }

        System.out.println("SERVIDOR UDP CONFIABLE");
        System.out.println("Puerto: " + PUERTO);
        System.out.println("Directorio: " + DIRECTORIO_ARCHIVOS);
        System.out.println("Log file: " + LOG_FILE);

        // Verificar directorio
        File directorio = new File(DIRECTORIO_ARCHIVOS);
        if (!directorio.exists()) {
            System.out.println("Creando directorio...");
            directorio.mkdirs();
        }

        // Mostrar archivos disponibles
        System.out.println("\nArchivos disponibles:");
        File[] archivos = directorio.listFiles();
        if (archivos != null) {
            for (File archivo : archivos) {
                if (archivo.isFile()) {
                    System.out.println("  - " + archivo.getName() + " (" + archivo.length() + " bytes)");
                }
            }
        }
        System.out.println();

        try (DatagramSocket socket = new DatagramSocket(PUERTO)) {
            socket.setSoTimeout(1000);

            System.out.println("✓ Servidor iniciado en puerto " + PUERTO);
            System.out.println("Esperando conexiones...\n");
            escribirLog("Servidor escuchando en puerto " + PUERTO);

            while (true) {
                try {
                    byte[] buffer = new byte[65507];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);

                    procesarPaquete(paquete, socket);

                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    escribirLog("[ERROR] Recibiendo paquete: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (logWriter != null) {
                    escribirLog("=== SERVIDOR DETENIDO ===\n");
                    logWriter.close();
                }
            } catch (IOException e) {
                System.err.println("Error cerrando log: " + e.getMessage());
            }
        }
    }

    private static void procesarPaquete(DatagramPacket paquete, DatagramSocket socket) {
        String mensaje = new String(paquete.getData(), 0, paquete.getLength());
        InetAddress ipCliente = paquete.getAddress();
        int puertoCliente = paquete.getPort();
        String claveCliente = ipCliente.getHostAddress() + ":" + puertoCliente;

        try {
            if (mensaje.startsWith("SYN:")) {
                manejarSYN(ipCliente, puertoCliente, mensaje, socket);
            } else if (mensaje.equals("LISTA")) {
                manejarLISTA(ipCliente, puertoCliente, socket);
            } else if (mensaje.startsWith("ACK:")) {
                HiloCliente hilo = clientesActivos.get(claveCliente);
                if (hilo != null) {
                    hilo.procesarACK(mensaje);
                    // NO mostrar ACK en consola, solo guardar en log
                    escribirLog("[ACK] " + mensaje);
                }
            } else if (mensaje.startsWith("FIN:")) {
                clientesActivos.remove(claveCliente);
                System.out.println("Cliente " + claveCliente + " desconectado");
                escribirLog("Cliente desconectado: " + claveCliente);
            }

        } catch (Exception e) {
            escribirLog("[ERROR] Procesando paquete: " + e.getMessage());
        }
    }

    private static void manejarSYN(InetAddress ip, int puerto, String mensaje, DatagramSocket socket)
            throws IOException {
        String[] partes = mensaje.split(":");
        if (partes.length < 3) {
            enviarError(ip, puerto, "SYN inválido", socket);
            return;
        }

        String nombreArchivo = partes[1];
        long seqCliente = Long.parseLong(partes[2]);
        String claveCliente = ip.getHostAddress() + ":" + puerto;

        System.out.println("\n[CONEXIÓN] " + claveCliente);
        System.out.println("  Archivo: " + nombreArchivo);

        // Verificar archivo
        File archivo = new File(DIRECTORIO_ARCHIVOS, nombreArchivo);
        if (!archivo.exists() || !archivo.isFile()) {
            System.out.println("  ✗ No encontrado");
            enviarError(ip, puerto, "Archivo no existe", socket);
            escribirLog("[ERROR] Archivo no encontrado: " + nombreArchivo);
            return;
        }

        long tamanio = archivo.length();
        System.out.println("  ✓ Encontrado (" + formatearTamanio(tamanio) + ")");

        // Enviar SYN-ACK
        long seqSynAck = seqCliente + 1;
        String synAck = "SYN-ACK:" + nombreArchivo + ":" + seqSynAck;
        enviarMensaje(ip, puerto, synAck, socket);

        // Crear hilo
        HiloCliente hiloCliente = new HiloCliente(ip, puerto, archivo, socket, claveCliente);
        clientesActivos.put(claveCliente, hiloCliente);
        new Thread(hiloCliente).start();

        escribirLog("[CLIENTE] Conectado: " + claveCliente + " - Archivo: " + nombreArchivo);
    }

    private static void manejarLISTA(InetAddress ip, int puerto, DatagramSocket socket)
            throws IOException {
        StringBuilder lista = new StringBuilder();
        lista.append("ARCHIVOS DISPONIBLES\n\n");

        File directorio = new File(DIRECTORIO_ARCHIVOS);
        File[] archivos = directorio.listFiles();

        if (archivos == null || archivos.length == 0) {
            lista.append("No hay archivos\n");
        } else {
            int contador = 0;
            for (File archivo : archivos) {
                if (archivo.isFile()) {
                    contador++;
                    lista.append(String.format("%d. %s (%s)\n",
                            contador, archivo.getName(), formatearTamanio(archivo.length())));
                }
            }
            lista.append("\nTotal: ").append(contador).append(" archivos");
        }

        enviarMensaje(ip, puerto, lista.toString(), socket);
    }

    private static void enviarMensaje(InetAddress ip, int puerto, String mensaje, DatagramSocket socket)
            throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static void enviarError(InetAddress ip, int puerto, String error, DatagramSocket socket)
            throws IOException {
        enviarMensaje(ip, puerto, "ERROR:" + error, socket);
    }

    // ===== MÉTODOS DE LOGGING =====
    private static void escribirLog(String mensaje) {
        String timestamp = LocalDateTime.now().format(formatter);
        String logLine = "[" + timestamp + "] " + mensaje;

        try {
            if (logWriter != null) {
                logWriter.write(logLine + "\n");
                logWriter.flush();
            }
        } catch (IOException e) {
            System.err.println("Error en log: " + e.getMessage());
        }
    }

    private static String formatearTamanio(long bytes) {
        if (bytes <= 0)
            return "0 B";
        final String[] unidades = { "B", "KB", "MB", "GB" };
        int indice = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, indice), unidades[indice]);
    }

    // ===== HILO PARA MANEJAR CADA CLIENTE =====
    static class HiloCliente implements Runnable {
        private InetAddress ip;
        private int puerto;
        private File archivo;
        private DatagramSocket socket;
        private long secuenciaActual = 1000;
        private boolean esperandoACK = false;
        private long ultimoSeqEnviado = 0;
        private Object lock = new Object();
        private String claveCliente;

        public HiloCliente(InetAddress ip, int puerto, File archivo, DatagramSocket socket, String claveCliente) {
            this.ip = ip;
            this.puerto = puerto;
            this.archivo = archivo;
            this.socket = socket;
            this.claveCliente = claveCliente;
        }

        @Override
        public void run() {
            try {
                // Leer archivo
                List<String> lineas = Files.readAllLines(archivo.toPath());
                int totalLineas = lineas.size();

                System.out.println("  Iniciando envío: " + totalLineas + " líneas\n");

                // Enviar cada línea con barra de progreso
                for (int i = 0; i < totalLineas; i++) {
                    String linea = lineas.get(i);

                    boolean ackRecibido = false;
                    int reintentos = 0;

                    while (!ackRecibido && reintentos < 3) {
                        synchronized (lock) {
                            String dataMsg = "DATA:" + secuenciaActual + ":" + linea.length() + ":" + linea;
                            enviarMensaje(ip, puerto, dataMsg, socket);
                            esperandoACK = true;
                            ultimoSeqEnviado = secuenciaActual;
                        }

                        // Esperar ACK
                        long inicioEspera = System.currentTimeMillis();
                        while (esperandoACK && (System.currentTimeMillis() - inicioEspera) < 2000) {
                            Thread.sleep(100);
                        }

                        synchronized (lock) {
                            if (!esperandoACK) {
                                ackRecibido = true;
                                secuenciaActual++;
                            }
                        }

                        reintentos++;
                    }

                    if (!ackRecibido) {
                        System.out.println("✗ Error: No se pudo enviar línea " + (i + 1));
                        escribirLog("[ERROR] Línea " + (i + 1) + " no enviada");
                        return;
                    }

                    // Mostrar barra de progreso cada línea
                    mostrarProgreso(i + 1, totalLineas);
                }

                // Enviar FIN-DATA
                String finData = "DATA:" + secuenciaActual + ":0:FIN-DATA";
                enviarMensaje(ip, puerto, finData, socket);

                // Enviar FIN
                Thread.sleep(500);
                String fin = "FIN:" + (secuenciaActual + 1);
                enviarMensaje(ip, puerto, fin, socket);

                System.out.println("\nTransferencia completada: " + totalLineas + " líneas");
                escribirLog("[TRANSFERENCIA] Completada - " + totalLineas + " líneas - Cliente: " + claveCliente);

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                escribirLog("[ERROR] " + e.getMessage());
            }
        }

        public void procesarACK(String ackMsg) {
            synchronized (lock) {
                if (ackMsg.startsWith("ACK:")) {
                    String[] partes = ackMsg.split(":");
                    long ackSeq = Long.parseLong(partes[1]);

                    if (ackSeq == ultimoSeqEnviado && esperandoACK) {
                        esperandoACK = false;
                    }
                }
            }
        }

        private void mostrarProgreso(int actual, int total) {
            int ancho = 40;
            int relleno = (int) ((float) actual / total * ancho);

            System.out.print("\r  [");
            for (int i = 0; i < ancho; i++) {
                System.out.print(i < relleno ? "=" : " ");
            }
            System.out.printf("] %d/%d (%.1f%%)", actual, total, ((float) actual / total * 100));
            System.out.flush();
        }

        private void enviarMensaje(InetAddress ip, int puerto, String mensaje, DatagramSocket socket)
                throws IOException {
            byte[] buffer = mensaje.getBytes();
            DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
            socket.send(paquete);
        }
    }
}