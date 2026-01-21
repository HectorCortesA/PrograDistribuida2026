package com.practica1;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class ClienteUDPConfiable {

    public static void transferirArchivo(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== CLIENTE UDP CONFIABLE ===");
        System.out.println("IP Origen: " + ipOrigen);
        System.out.println("IP Destino: " + ipDestino);
        System.out.println("Archivo: " + nombreArchivo);
        System.out.println("=============================\n");

        DatagramSocket socket = null;
        try {
            // Verificar que el archivo existe
            Path rutaArchivo = Paths.get(nombreArchivo);
            if (!Files.exists(rutaArchivo)) {
                System.err.println("ERROR: El archivo '" + nombreArchivo + "' no existe.");
                return;
            }

            // Configurar conexión
            InetAddress ipServidor = InetAddress.getByName(ipDestino);
            final int PUERTO_SERVIDOR = 22000;

            // Crear socket UDP
            if (ipOrigen.equals("0.0.0.0")) {
                socket = new DatagramSocket();
            } else {
                InetAddress ipLocal = InetAddress.getByName(ipOrigen);
                socket = new DatagramSocket(0, ipLocal);
            }

            socket.setSoTimeout(5000); // Timeout de 5 segundos

            // Mostrar información local
            System.out.println("Cliente iniciado en:");
            System.out.println("  IP: " + socket.getLocalAddress().getHostAddress());
            System.out.println("  Puerto: " + socket.getLocalPort());
            System.out.println();

            // ===== THREE-WAY HANDSHAKE =====
            System.out.println("[1] ESTABLECIENDO CONEXIÓN (Three-Way Handshake)...");

            // Paso 1: Enviar SYN
            long seqInicial = System.currentTimeMillis() % 10000;
            String synMsg = "SYN:" + nombreArchivo + ":" + seqInicial;
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, synMsg);
            System.out.println("  → SYN enviado: " + synMsg);

            // Paso 2: Recibir SYN-ACK
            String synAck = recibirPaqueteConTimeout(socket, 5000);
            if (synAck == null || !synAck.startsWith("SYN-ACK:")) {
                System.err.println("ERROR: No se recibió SYN-ACK válido");
                socket.close();
                return;
            }
            System.out.println("  ← SYN-ACK recibido: " + synAck);

            // Paso 3: Enviar ACK
            String ackMsg = "ACK:" + (seqInicial + 1);
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ackMsg);
            System.out.println("  → ACK enviado: " + ackMsg);
            System.out.println("✓ Conexión establecida con el servidor");

            // ===== ENVÍO DE ARCHIVO =====
            System.out.println("\n[2] ENVIANDO ARCHIVO AL SERVIDOR...");

            BufferedReader lector = Files.newBufferedReader(rutaArchivo);
            String linea;
            long numeroSecuencia = seqInicial + 2;
            int lineasEnviadas = 0;
            int totalLineas = (int) Files.lines(rutaArchivo).count();
            lector = Files.newBufferedReader(rutaArchivo); // Reset reader

            System.out.println("  Archivo tiene " + totalLineas + " líneas");

            while ((linea = lector.readLine()) != null) {
                // Ignorar líneas vacías
                if (linea.trim().isEmpty()) {
                    continue;
                }

                // Crear paquete DATA
                String dataPacket = "DATA:" + numeroSecuencia + ":" + linea.length() + ":" + linea;

                // Enviar con reintentos
                boolean ackRecibido = false;
                int intento = 1;

                while (!ackRecibido && intento <= 3) {
                    try {
                        enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, dataPacket);
                        System.out.println("  → Línea " + (lineasEnviadas + 1) +
                                " (seq=" + numeroSecuencia + ", intento=" + intento + ")");

                        // Esperar ACK
                        String ack = recibirPaqueteConTimeout(socket, 2000);
                        if (ack != null && ack.equals("ACK:" + numeroSecuencia)) {
                            ackRecibido = true;
                            lineasEnviadas++;
                            System.out.println("    ← ACK recibido para seq=" + numeroSecuencia);

                            // Mostrar progreso
                            double progreso = (lineasEnviadas * 100.0) / totalLineas;
                            System.out.printf("    Progreso: %.1f%%\n", progreso);
                        } else if (ack != null && ack.startsWith("ERROR:")) {
                            System.err.println("ERROR del servidor: " + ack);
                            break;
                        }
                    } catch (Exception e) {
                        System.out.println("    Timeout, reintentando...");
                    }
                    intento++;
                }

                if (!ackRecibido) {
                    System.err.println("ERROR: No se recibió ACK después de 3 intentos");
                    lector.close();
                    socket.close();
                    return;
                }

                numeroSecuencia++;
            }

            lector.close();
            System.out.println("✓ " + lineasEnviadas + " líneas enviadas correctamente");

            // ===== FINALIZAR ENVÍO =====
            System.out.println("\n[3] FINALIZANDO ENVÍO...");

            // Enviar paquete FIN-DATA
            String finData = "DATA:" + numeroSecuencia + ":0:FIN";
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, finData);
            System.out.println("  → FIN-DATA enviado");

            // Esperar ACK del FIN-DATA
            String ackFinData = recibirPaqueteConTimeout(socket, 3000);
            if (ackFinData != null && ackFinData.equals("ACK:" + numeroSecuencia)) {
                System.out.println("  ← ACK recibido para FIN-DATA");
            }

            numeroSecuencia++;

            // ===== RECIBIR COPIA DEL ARCHIVO =====
            System.out.println("\n[4] RECIBIENDO COPIA DEL ARCHIVO...");

            String nombreCopia = nombreArchivo.replace(".txt", "_copia.txt");
            FileWriter escritor = new FileWriter(nombreCopia);
            int lineasRecibidas = 0;
            boolean recepcionTerminada = false;
            socket.setSoTimeout(5000);

            while (!recepcionTerminada) {
                try {
                    String dataRx = recibirPaquete(socket);

                    if (dataRx.startsWith("DATA:")) {
                        String[] partes = dataRx.split(":", 4);
                        long seqRx = Long.parseLong(partes[1]);
                        int tamanio = Integer.parseInt(partes[2]);
                        String contenido = partes[3];

                        // Enviar ACK
                        String ack = "ACK:" + seqRx;
                        enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ack);

                        if (tamanio > 0) {
                            escritor.write(contenido + "\n");
                            lineasRecibidas++;
                            System.out.println("  ← Línea " + lineasRecibidas + " recibida");
                        } else if (tamanio == 0) {
                            // FIN de transferencia
                            System.out.println("  ← FIN recibido del servidor");
                            recepcionTerminada = true;
                        }
                    } else if (dataRx.startsWith("ERROR:")) {
                        System.err.println("ERROR: " + dataRx);
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("  Timeout esperando datos, continuando...");
                    recepcionTerminada = true;
                }
            }

            escritor.close();
            socket.setSoTimeout(0);
            System.out.println("✓ " + lineasRecibidas + " líneas recibidas en: " + nombreCopia);

            // ===== FOUR-WAY HANDSHAKE =====
            System.out.println("\n[5] CERRANDO CONEXIÓN (Four-Way Handshake)...");

            // Paso 1: Cliente envía FIN
            String fin = "FIN:" + numeroSecuencia;
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, fin);
            System.out.println("  → FIN enviado: " + fin);

            // Paso 2: Recibir ACK del servidor
            String ackFin = recibirPaqueteConTimeout(socket, 3000);
            if (ackFin != null && ackFin.startsWith("ACK:")) {
                System.out.println("  ← ACK recibido: " + ackFin);
            }

            // Paso 3: Recibir FIN del servidor
            String finServidor = recibirPaqueteConTimeout(socket, 3000);
            if (finServidor != null && finServidor.startsWith("FIN:")) {
                System.out.println("  ← FIN recibido: " + finServidor);

                // Paso 4: Enviar ACK final
                String[] partes = finServidor.split(":");
                long seqFin = Long.parseLong(partes[1]);
                String ackFinal = "ACK:" + seqFin;
                enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ackFinal);
                System.out.println("  → ACK final enviado: " + ackFinal);
            }

            // Cerrar conexión
            socket.close();

            System.out.println("\n✅ TRANSFERENCIA COMPLETADA EXITOSAMENTE!");
            System.out.println("==========================================");
            System.out.println("Archivo enviado: " + nombreArchivo);
            System.out.println("Líneas enviadas: " + lineasEnviadas);
            System.out.println("Copia guardada: " + nombreCopia);
            System.out.println("Líneas recibidas: " + lineasRecibidas);
            System.out.println("Servidor: " + ipDestino + ":22000");

        } catch (UnknownHostException e) {
            System.err.println("ERROR: IP no válida - " + e.getMessage());
        } catch (SocketException e) {
            System.err.println("ERROR: Problema con el socket - " + e.getMessage());
        } catch (IOException e) {
            System.err.println("ERROR: Problema de E/S - " + e.getMessage());
        } catch (Exception e) {
            System.err.println("ERROR inesperado: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // ===== MÉTODOS AUXILIARES =====

    private static void enviarPaquete(DatagramSocket socket, InetAddress ip, int puerto, String mensaje)
            throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static String recibirPaquete(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[1024];
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
        socket.receive(paquete);
        return new String(paquete.getData(), 0, paquete.getLength());
    }

    private static String recibirPaqueteConTimeout(DatagramSocket socket, int timeoutMs)
            throws IOException {
        socket.setSoTimeout(timeoutMs);
        try {
            return recibirPaquete(socket);
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(0);
        }
    }

    // ===== MÉTODO MAIN =====

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("=== USO DEL CLIENTE UDP CONFIABLE ===");
            System.out.println("java ClienteUDPConfiable <ip-origen> <ip-destino> <archivo>");
            System.out.println();
            System.out.println("Ejemplos:");
            System.out.println("  1. Localhost:");
            System.out.println("     java ClienteUDPConfiable 127.0.0.1 127.0.0.1 prueba.txt");
            System.out.println();
            System.out.println("  2. Red local (todas interfaces):");
            System.out.println("     java ClienteUDPConfiable 0.0.0.0 192.168.1.100 archivo.txt");
            System.out.println();
            System.out.println("  3. Interfaz específica:");
            System.out.println("     java ClienteUDPConfiable 192.168.1.50 192.168.1.100 datos.txt");
            return;
        }

        String ipOrigen = args[0];
        String ipDestino = args[1];
        String archivo = args[2];

        transferirArchivo(ipOrigen, ipDestino, archivo);
    }
}