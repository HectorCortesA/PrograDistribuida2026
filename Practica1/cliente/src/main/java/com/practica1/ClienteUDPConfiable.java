package com.practica1;

import java.io.*;
import java.net.*;
import java.util.*;

public class ClienteUDPConfiable {

    public static void solicitarArchivo(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== CLIENTE UDP CONFIABLE ===");
        System.out.println("Solicitando archivo: " + nombreArchivo);
        System.out.println("Servidor: " + ipDestino + ":22000");
        System.out.println("=============================\n");

        DatagramSocket socket = null;
        try {
            // Configurar socket
            socket = new DatagramSocket();
            socket.setSoTimeout(10000); // Timeout general de 10 segundos

            InetAddress ipServidor = InetAddress.getByName(ipDestino);
            final int PUERTO_SERVIDOR = 22000;

            System.out.println("Cliente en puerto: " + socket.getLocalPort());
            System.out.println();

            // ===== 1. THREE-WAY HANDSHAKE =====
            System.out.println("[1] THREE-WAY HANDSHAKE");

            // SYN
            long seqInicial = System.currentTimeMillis() % 10000;
            String syn = "SYN:" + nombreArchivo + ":" + seqInicial;
            enviar(socket, ipServidor, PUERTO_SERVIDOR, syn);
            System.out.println("  → SYN (seq=" + seqInicial + ")");

            // SYN-ACK
            System.out.println("  Esperando SYN-ACK...");
            String synAck = recibirConTimeout(socket, 5000);

            if (synAck == null) {
                System.err.println("✗ Timeout: Servidor no responde");
                return;
            }

            if (!synAck.startsWith("SYN-ACK:")) {
                if (synAck.startsWith("ERROR:")) {
                    System.err.println("✗ Error servidor: " + synAck.substring(6));
                } else {
                    System.err.println("✗ Respuesta inesperada: " + synAck);
                }
                return;
            }

            String[] partes = synAck.split(":");
            long seqSynAck = Long.parseLong(partes[2]);
            System.out.println("  ← SYN-ACK (seq=" + seqSynAck + ")");

            // ACK
            String ack = "ACK:" + seqSynAck;
            enviar(socket, ipServidor, PUERTO_SERVIDOR, ack);
            System.out.println("  → ACK (ack=" + seqSynAck + ")");
            System.out.println("✓ Conexión establecida\n");

            // ===== 2. RECIBIR ARCHIVO =====
            System.out.println("[2] RECIBIENDO ARCHIVO");

            // Crear archivo local
            String nombreLocal = nombreArchivo.replace(".txt", "_recibido.txt");
            FileWriter escritor = new FileWriter(nombreLocal);
            int lineasRecibidas = 0;
            long secuenciaEsperada = seqSynAck + 1;
            boolean transferenciaCompleta = false;

            System.out.println("  Guardando en: " + nombreLocal);
            System.out.println("  Esperando datos...\n");

            // Aumentar timeout para recepción
            socket.setSoTimeout(15000);

            while (!transferenciaCompleta) {
                try {
                    String paquete = recibir(socket);

                    if (paquete.startsWith("DATA:")) {
                        String[] datos = paquete.split(":", 4);
                        long seq = Long.parseLong(datos[1]);
                        int tamaño = Integer.parseInt(datos[2]);
                        String contenido = datos[3];

                        System.out.println("  ← DATA (seq=" + seq + ", tamaño=" + tamaño + ")");

                        // Enviar ACK inmediatamente
                        String ackData = "ACK:" + seq;
                        enviar(socket, ipServidor, PUERTO_SERVIDOR, ackData);
                        System.out.println("  → ACK (ack=" + seq + ")");

                        if (tamaño > 0) {
                            // Guardar línea
                            escritor.write(contenido + "\n");
                            lineasRecibidas++;

                            if (lineasRecibidas % 5 == 0 || lineasRecibidas == 1) {
                                System.out.println("    Líneas recibidas: " + lineasRecibidas);
                            }
                        } else if (tamaño == 0 && contenido.equals("FIN-DATA")) {
                            System.out.println("  ← FIN-DATA recibido");
                            transferenciaCompleta = true;
                        }

                    } else if (paquete.startsWith("FIN:")) {
                        System.out.println("  ← FIN recibido");
                        // Responder con ACK
                        String[] finPartes = paquete.split(":");
                        long seqFin = Long.parseLong(finPartes[1]);
                        String ackFin = "ACK:" + seqFin;
                        enviar(socket, ipServidor, PUERTO_SERVIDOR, ackFin);
                        System.out.println("  → ACK para FIN");
                        break;
                    }

                } catch (SocketTimeoutException e) {
                    System.out.println("  ⏱ Timeout: No más datos");
                    transferenciaCompleta = true;
                }
            }

            escritor.close();
            System.out.println("\n✓ Archivo recibido: " + lineasRecibidas + " líneas");

            // ===== 3. CERRAR CONEXIÓN =====
            System.out.println("\n[3] CERRANDO CONEXIÓN");

            // Enviar FIN
            String fin = "FIN:" + secuenciaEsperada;
            enviar(socket, ipServidor, PUERTO_SERVIDOR, fin);
            System.out.println("  → FIN enviado");

            // Esperar ACK breve tiempo
            socket.setSoTimeout(2000);
            try {
                String respuesta = recibir(socket);
                if (respuesta.startsWith("ACK:")) {
                    System.out.println("  ← ACK recibido");
                }
            } catch (SocketTimeoutException e) {
                // Timeout OK, servidor puede haber cerrado
            }

            socket.close();

            // ===== RESUMEN =====
            System.out.println("\n✅ TRANSFERENCIA COMPLETADA");
            System.out.println("============================");
            System.out.println("Archivo: " + nombreArchivo);
            System.out.println("Guardado como: " + nombreLocal);
            System.out.println("Líneas: " + lineasRecibidas);
            System.out.println("Servidor: " + ipDestino);

        } catch (Exception e) {
            System.err.println("\n✗ ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // ===== MÉTODOS AUXILIARES =====

    private static void enviar(DatagramSocket socket, InetAddress ip, int puerto, String mensaje)
            throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static String recibir(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[65507];
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
        socket.receive(paquete);
        return new String(paquete.getData(), 0, paquete.getLength());
    }

    private static String recibirConTimeout(DatagramSocket socket, int timeoutMs)
            throws IOException {
        socket.setSoTimeout(timeoutMs);
        try {
            return recibir(socket);
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    // ===== MÉTODO MAIN SIMPLIFICADO =====

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== CLIENTE UDP CONFIABLE ===");
        System.out.println("Solicita archivos de un servidor");
        System.out.println("Puerto servidor: 22000\n");

        System.out.print("IP del servidor: ");
        String ipServidor = scanner.nextLine().trim();
        if (ipServidor.isEmpty()) {
            ipServidor = "localhost";
        }

        System.out.print("Archivo a solicitar: ");
        String nombreArchivo = scanner.nextLine().trim();

        if (nombreArchivo.isEmpty()) {
            System.out.println("Debe especificar un archivo");
            scanner.close();
            return;
        }

        scanner.close();

        // Solicitar archivo
        solicitarArchivo("0.0.0.0", ipServidor, nombreArchivo);
    }
}