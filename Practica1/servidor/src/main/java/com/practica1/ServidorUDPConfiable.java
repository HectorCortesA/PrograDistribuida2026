package com.practica1;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ServidorUDPConfiable {
    private static final int PUERTO = 22000;
    private static final String DIRECTORIO_ARCHIVOS = "/Users/hectorcortes/Downloads";
    private static final Map<String, HiloCliente> clientesActivos = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("=== SERVIDOR UDP CONFIABLE ===");
        System.out.println("Puerto: " + PUERTO);
        System.out.println("Directorio archivos: " + DIRECTORIO_ARCHIVOS);
        System.out.println("================================\n");

        // Verificar directorio
        File directorio = new File(DIRECTORIO_ARCHIVOS);
        if (!directorio.exists()) {
            System.out.println("Creando directorio...");
            directorio.mkdirs();
        }

        // Mostrar archivos disponibles
        System.out.println("Archivos disponibles:");
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
            socket.setSoTimeout(1000); // Timeout para no bloquear indefinidamente

            System.out.println("✓ Servidor iniciado en puerto " + PUERTO);
            System.out.println("✓ Esperando conexiones...\n");

            while (true) {
                try {
                    byte[] buffer = new byte[65507];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);

                    // Procesar paquete inmediatamente
                    procesarPaquete(paquete, socket);

                } catch (SocketTimeoutException e) {
                    // Timeout normal, continuar bucle
                    continue;
                } catch (IOException e) {
                    System.err.println("Error recibiendo paquete: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("Error en servidor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void procesarPaquete(DatagramPacket paquete, DatagramSocket socket) {
        String mensaje = new String(paquete.getData(), 0, paquete.getLength());
        InetAddress ipCliente = paquete.getAddress();
        int puertoCliente = paquete.getPort();
        String claveCliente = ipCliente.getHostAddress() + ":" + puertoCliente;

        System.out.println("\n[+] Paquete de " + claveCliente);
        System.out.println("  Mensaje: " + mensaje);

        try {
            if (mensaje.startsWith("SYN:")) {
                manejarSYN(ipCliente, puertoCliente, mensaje, socket);
            } else if (mensaje.equals("LISTA")) {
                manejarLISTA(ipCliente, puertoCliente, socket);
            } else if (mensaje.startsWith("ACK:")) {
                // Enviar ACK al hilo del cliente correspondiente
                HiloCliente hilo = clientesActivos.get(claveCliente);
                if (hilo != null) {
                    hilo.procesarACK(mensaje);
                }
            } else if (mensaje.startsWith("FIN:")) {
                // Limpiar cliente
                clientesActivos.remove(claveCliente);
                System.out.println("  Cliente " + claveCliente + " desconectado");
            }

        } catch (Exception e) {
            System.err.println("Error procesando paquete: " + e.getMessage());
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

        System.out.println("  Archivo solicitado: " + nombreArchivo);

        // Verificar si el archivo existe
        File archivo = new File(DIRECTORIO_ARCHIVOS, nombreArchivo);
        if (!archivo.exists() || !archivo.isFile()) {
            System.out.println("  ✗ Archivo no encontrado");
            enviarError(ip, puerto, "Archivo '" + nombreArchivo + "' no existe", socket);
            return;
        }

        System.out.println("  ✓ Archivo encontrado (" + archivo.length() + " bytes)");

        // Enviar SYN-ACK
        long seqSynAck = seqCliente + 1;
        String synAck = "SYN-ACK:" + nombreArchivo + ":" + seqSynAck;
        enviarMensaje(ip, puerto, synAck, socket);
        System.out.println("  SYN-ACK enviado (seq=" + seqSynAck + ")");

        // Crear y ejecutar hilo para este cliente
        HiloCliente hiloCliente = new HiloCliente(ip, puerto, archivo, socket);
        clientesActivos.put(claveCliente, hiloCliente);
        new Thread(hiloCliente).start();
    }

    private static void manejarLISTA(InetAddress ip, int puerto, DatagramSocket socket)
            throws IOException {
        System.out.println("  Solicitud de lista de archivos");

        StringBuilder lista = new StringBuilder();
        lista.append("=== ARCHIVOS DISPONIBLES ===\n\n");

        File directorio = new File(DIRECTORIO_ARCHIVOS);
        File[] archivos = directorio.listFiles();

        if (archivos == null || archivos.length == 0) {
            lista.append("No hay archivos en el servidor.\n");
        } else {
            int contador = 0;
            for (File archivo : archivos) {
                if (archivo.isFile()) {
                    contador++;
                    lista.append(String.format("%d. %s (%d bytes)\n",
                            contador, archivo.getName(), archivo.length()));
                }
            }
            lista.append("\nTotal: ").append(contador).append(" archivos\n");
        }

        lista.append("\nPara solicitar: SYN:nombre_archivo:secuencia");
        enviarMensaje(ip, puerto, lista.toString(), socket);
        System.out.println("  Lista enviada");
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

        public HiloCliente(InetAddress ip, int puerto, File archivo, DatagramSocket socket) {
            this.ip = ip;
            this.puerto = puerto;
            this.archivo = archivo;
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                System.out.println("  [HILO] Iniciando envío del archivo: " + archivo.getName());

                // Leer archivo
                List<String> lineas = Files.readAllLines(archivo.toPath());
                System.out.println("  [HILO] Archivo tiene " + lineas.size() + " líneas");

                // Enviar cada línea
                for (int i = 0; i < lineas.size(); i++) {
                    String linea = lineas.get(i);

                    boolean ackRecibido = false;
                    int reintentos = 0;

                    while (!ackRecibido && reintentos < 3) {
                        synchronized (lock) {
                            // Enviar DATA
                            String dataMsg = "DATA:" + secuenciaActual + ":" + linea.length() + ":" + linea;
                            enviarMensaje(ip, puerto, dataMsg, socket);
                            esperandoACK = true;
                            ultimoSeqEnviado = secuenciaActual;

                            if (reintentos == 0) {
                                System.out.println(
                                        "    [HILO] Enviando línea " + (i + 1) + " (seq=" + secuenciaActual + ")");
                            } else {
                                System.out.println("    [HILO] Reintento " + reintentos + " para línea " + (i + 1));
                            }
                        }

                        // Esperar ACK por 2 segundos
                        long inicioEspera = System.currentTimeMillis();
                        while (esperandoACK && (System.currentTimeMillis() - inicioEspera) < 2000) {
                            Thread.sleep(100); // Pequeña pausa para no consumir CPU
                        }

                        synchronized (lock) {
                            if (!esperandoACK) {
                                ackRecibido = true;
                                secuenciaActual++;
                                System.out.println("    [HILO] ✓ ACK recibido para línea " + (i + 1));
                            }
                        }

                        reintentos++;
                    }

                    if (!ackRecibido) {
                        System.out.println("    [HILO] ✗ No se pudo enviar línea " + (i + 1));
                        return;
                    }
                }

                // Enviar FIN-DATA
                String finData = "DATA:" + secuenciaActual + ":0:FIN-DATA";
                enviarMensaje(ip, puerto, finData, socket);
                System.out.println("  [HILO] ✓ FIN-DATA enviado");

                // Enviar FIN para cerrar conexión
                Thread.sleep(1000);
                String fin = "FIN:" + (secuenciaActual + 1);
                enviarMensaje(ip, puerto, fin, socket);
                System.out.println("  [HILO] ✓ FIN enviado, conexión cerrada");

                System.out.println("  [HILO] ✓ Archivo enviado completamente: " + lineas.size() + " líneas");

            } catch (Exception e) {
                System.err.println("  [HILO] Error: " + e.getMessage());
            }
        }

        public void procesarACK(String ackMsg) {
            synchronized (lock) {
                if (ackMsg.startsWith("ACK:")) {
                    String[] partes = ackMsg.split(":");
                    long ackSeq = Long.parseLong(partes[1]);

                    if (ackSeq == ultimoSeqEnviado && esperandoACK) {
                        esperandoACK = false;
                        System.out.println("    [HILO] ACK procesado para seq=" + ackSeq);
                    }
                }
            }
        }

        private void enviarMensaje(InetAddress ip, int puerto, String mensaje, DatagramSocket socket)
                throws IOException {
            byte[] buffer = mensaje.getBytes();
            DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
            socket.send(paquete);
        }
    }
}