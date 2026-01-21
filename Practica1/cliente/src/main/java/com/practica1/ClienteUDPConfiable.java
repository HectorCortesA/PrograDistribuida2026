package com.hector;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class ClienteUDPConfiable {

    // Método para SOLICITAR un archivo del servidor
    public static void solicitarArchivo(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== CLIENTE UDP CONFIABLE ===");
        System.out.println("IP Origen: " + ipOrigen);
        System.out.println("IP Destino: " + ipDestino);
        System.out.println("Archivo SOLICITADO: " + nombreArchivo);
        System.out.println("=============================\n");

        DatagramSocket socket = null;
        try {
            // ===== CONFIGURAR CONEXIÓN UDP =====
            InetAddress ipServidor = InetAddress.getByName(ipDestino);
            final int PUERTO_SERVIDOR = 22000;

            // Crear socket UDP
            socket = new DatagramSocket();
            socket.setSoTimeout(10000); // Timeout de 10 segundos

            System.out.println("Cliente UDP iniciado en:");
            System.out.println("  Puerto local: " + socket.getLocalPort());
            System.out.println("  Conectando a: " + ipDestino + ":" + PUERTO_SERVIDOR);
            System.out.println();

            // ===== 1. THREE-WAY HANDSHAKE =====
            System.out.println("[1] THREE-WAY HANDSHAKE (Cliente → Servidor)...");

            // Paso 1: Cliente envía SYN con solicitud de archivo
            long seqInicial = System.currentTimeMillis() % 10000;
            String synMsg = "SYN:" + nombreArchivo + ":" + seqInicial;
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, synMsg);
            System.out.println("  CLIENTE → SERVIDOR: SYN, seq=" + seqInicial);
            System.out.println("    Solicitud archivo: " + nombreArchivo);

            // Paso 2: Esperar SYN-ACK del servidor
            System.out.println("  Esperando SYN-ACK del servidor...");
            String synAck = recibirPaqueteConTimeout(socket, 5000);

            if (synAck == null) {
                System.err.println("ERROR: Timeout - Servidor no responde");
                socket.close();
                return;
            }

            if (!synAck.startsWith("SYN-ACK:")) {
                if (synAck.startsWith("ERROR:")) {
                    String errorMsg = synAck.substring(6);
                    System.err.println("ERROR del servidor: " + errorMsg);

                    // Si el archivo no existe, mostrar ayuda
                    if (errorMsg.contains("no existe") || errorMsg.contains("no encontrado")) {
                        System.out.println("\n¿Quieres ver los archivos disponibles en el servidor? (s/n): ");
                        Scanner scanner = new Scanner(System.in);
                        if (scanner.nextLine().trim().equalsIgnoreCase("s")) {
                            solicitarListaArchivos(socket, ipServidor, PUERTO_SERVIDOR);
                        }
                        scanner.close();
                    }
                } else {
                    System.err.println("ERROR: Se esperaba SYN-ACK pero se recibió: " + synAck);
                }
                socket.close();
                return;
            }

            // Extraer información del SYN-ACK
            String[] partesSynAck = synAck.split(":");
            String nombreArchivoConfirmado = partesSynAck[1];
            long seqSynAck = Long.parseLong(partesSynAck[2]);

            System.out.println("  SERVIDOR → CLIENTE: SYN-ACK, seq=" + seqSynAck);
            System.out.println("    Archivo confirmado: " + nombreArchivoConfirmado);

            // Verificar que seqSynAck = seqInicial + 1
            if (seqSynAck != seqInicial + 1) {
                System.err.println("ERROR: Número de secuencia incorrecto en SYN-ACK");
                socket.close();
                return;
            }

            // Paso 3: Cliente envía ACK para completar handshake
            String ackMsg = "ACK:" + seqSynAck;
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ackMsg);
            System.out.println("  CLIENTE → SERVIDOR: ACK, ack=" + seqSynAck);
            System.out.println("✓ CONEXIÓN ESTABLECIDA - Three-way handshake completado");

            // ===== 2. ESPERAR RECEPCIÓN DEL ARCHIVO =====
            System.out.println("\n[2] ESPERANDO ARCHIVO DEL SERVIDOR...");

            // Crear archivo local para guardar lo recibido
            String nombreLocal = nombreArchivo;
            if (nombreArchivo.contains(".")) {
                nombreLocal = nombreArchivo.replaceFirst("\\.([^\\.]+)$", "_recibido.$1");
            } else {
                nombreLocal = nombreArchivo + "_recibido";
            }

            FileWriter escritor = new FileWriter(nombreLocal);
            int lineasRecibidas = 0;
            long secuenciaEsperada = seqSynAck + 1;
            boolean finTransferencia = false;

            System.out.println("  Archivo local: " + nombreLocal);
            System.out.println("  Esperando datos del servidor...");

            while (!finTransferencia) {
                socket.setSoTimeout(15000); // Timeout de 15 segundos para transferencia

                try {
                    String paqueteRecibido = recibirPaquete(socket);

                    if (paqueteRecibido.startsWith("DATA:")) {
                        // Procesar paquete DATA
                        String[] partes = paqueteRecibido.split(":", 4);
                        long seqRecibido = Long.parseLong(partes[1]);
                        int tamaño = Integer.parseInt(partes[2]);
                        String contenido = partes[3];

                        System.out.println("  SERVIDOR → CLIENTE: DATA, seq=" + seqRecibido);

                        // Verificar secuencia
                        if (seqRecibido == secuenciaEsperada) {
                            // Enviar ACK de confirmación
                            String ackData = "ACK:" + seqRecibido;
                            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ackData);
                            System.out.println("  CLIENTE → SERVIDOR: ACK, ack=" + seqRecibido);

                            if (tamaño > 0) {
                                // Guardar línea recibida
                                escritor.write(contenido + "\n");
                                lineasRecibidas++;
                                secuenciaEsperada++;

                                if (lineasRecibidas % 10 == 0 || lineasRecibidas == 1) {
                                    System.out.println("    Líneas recibidas: " + lineasRecibidas);
                                }
                            } else if (tamaño == 0 && contenido.equals("FIN-DATA")) {
                                // Fin de la transferencia
                                System.out.println("  ✓ FIN-DATA recibido - Transferencia completada");
                                finTransferencia = true;
                            }
                        } else {
                            // Secuencia incorrecta, reenviar ACK anterior
                            System.out.println("  Secuencia incorrecta, reenviando último ACK...");
                            String ackAnterior = "ACK:" + (secuenciaEsperada - 1);
                            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ackAnterior);
                        }

                    } else if (paqueteRecibido.startsWith("ERROR:")) {
                        System.err.println("ERROR del servidor: " + paqueteRecibido.substring(6));
                        break;
                    } else if (paqueteRecibido.startsWith("FIN:")) {
                        System.out.println("  FIN inesperado recibido");
                        break;
                    }

                } catch (SocketTimeoutException e) {
                    System.out.println("  Timeout: No más datos del servidor");
                    finTransferencia = true;
                }
            }

            escritor.close();
            socket.setSoTimeout(0);
            System.out.println("✓ " + lineasRecibidas + " líneas recibidas en: " + nombreLocal);

            // ===== 3. FOUR-WAY HANDSHAKE =====
            System.out.println("\n[3] FOUR-WAY HANDSHAKE - CERRANDO CONEXIÓN...");

            // Paso 1: Cliente envía FIN
            String finMsg = "FIN:" + secuenciaEsperada;
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, finMsg);
            System.out.println("  CLIENTE → SERVIDOR: FIN, seq=" + secuenciaEsperada);

            // Paso 2: Esperar ACK del servidor (con timeout)
            socket.setSoTimeout(3000);
            try {
                String ackFin = recibirPaquete(socket);
                if (ackFin.startsWith("ACK:")) {
                    System.out.println("  SERVIDOR → CLIENTE: ACK para FIN");
                }
            } catch (SocketTimeoutException e) {
                System.out.println("  Timeout esperando ACK para FIN");
            }

            // Paso 3: Esperar FIN del servidor
            socket.setSoTimeout(3000);
            try {
                String finServidor = recibirPaquete(socket);
                if (finServidor.startsWith("FIN:")) {
                    System.out.println("  SERVIDOR → CLIENTE: FIN");

                    // Paso 4: Enviar ACK final
                    String[] partesFin = finServidor.split(":");
                    long seqFin = Long.parseLong(partesFin[1]);
                    String ackFinal = "ACK:" + seqFin;
                    enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ackFinal);
                    System.out.println("  CLIENTE → SERVIDOR: ACK final");
                }
            } catch (SocketTimeoutException e) {
                System.out.println("  Timeout esperando FIN del servidor");
            } finally {
                socket.setSoTimeout(0);
            }

            // Cerrar socket
            socket.close();

            // ===== RESUMEN =====
            System.out.println("\n✅ TRANSFERENCIA COMPLETADA!");
            System.out.println("================================");
            System.out.println("Archivo solicitado: " + nombreArchivo);
            System.out.println("Copia local: " + nombreLocal);
            System.out.println("Líneas recibidas: " + lineasRecibidas);
            System.out.println("Servidor: " + ipDestino + ":22000");

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // Método para solicitar lista de archivos
    private static void solicitarListaArchivos(DatagramSocket socket, InetAddress ipServidor, int puerto)
            throws IOException {
        System.out.println("\n=== SOLICITANDO LISTA DE ARCHIVOS ===");

        // Enviar solicitud de lista
        enviarPaquete(socket, ipServidor, puerto, "LISTA");
        System.out.println("Solicitud enviada...");

        // Recibir respuesta con timeout
        socket.setSoTimeout(5000);
        try {
            String respuesta = recibirPaquete(socket);
            System.out.println("\n" + respuesta);
        } catch (SocketTimeoutException e) {
            System.out.println("No se recibió respuesta del servidor");
        } finally {
            socket.setSoTimeout(0);
        }
    }

    // ===== MÉTODOS AUXILIARES UDP =====

    private static void enviarPaquete(DatagramSocket socket, InetAddress ip, int puerto, String mensaje)
            throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static String recibirPaquete(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[65507]; // Tamaño máximo UDP
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
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== CLIENTE UDP CONFIABLE ===");
        System.out.println("Solicita archivos de un servidor remoto");
        System.out.println("Protocolo: UDP con confiabilidad TCP-like");
        System.out.println("========================================\n");

        // Configuración
        System.out.print("IP del servidor [localhost]: ");
        String ipDestino = scanner.nextLine().trim();
        if (ipDestino.isEmpty()) {
            ipDestino = "localhost";
        }

        System.out.print("IP origen [0.0.0.0]: ");
        String ipOrigen = scanner.nextLine().trim();
        if (ipOrigen.isEmpty()) {
            ipOrigen = "0.0.0.0";
        }

        // Menú de opciones
        while (true) {
            System.out.println("\n=== MENÚ PRINCIPAL ===");
            System.out.println("1. Solicitar archivo específico");
            System.out.println("2. Ver lista de archivos disponibles");
            System.out.println("3. Salir");
            System.out.print("\nSeleccione opción (1-3): ");

            String opcion = scanner.nextLine().trim();

            if (opcion.equals("3")) {
                System.out.println("Saliendo...");
                break;
            }

            if (opcion.equals("2")) {
                // Solicitar lista de archivos
                try {
                    DatagramSocket socketTemp = new DatagramSocket();
                    InetAddress ipServidor = InetAddress.getByName(ipDestino);

                    System.out.println("\nSolicitando lista de archivos...");
                    solicitarListaArchivos(socketTemp, ipServidor, 22000);

                    socketTemp.close();
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }
                continue;
            }

            if (opcion.equals("1")) {
                // Solicitar archivo específico
                System.out.print("\nNombre del archivo a solicitar: ");
                String nombreArchivo = scanner.nextLine().trim();

                if (nombreArchivo.isEmpty()) {
                    System.out.println("Debe especificar un nombre de archivo");
                    continue;
                }

                System.out.println("\nIniciando solicitud de: " + nombreArchivo);
                System.out.println("Servidor: " + ipDestino);
                System.out.println("========================================\n");

                solicitarArchivo(ipOrigen, ipDestino, nombreArchivo);

                System.out.print("\nPresione Enter para continuar...");
                scanner.nextLine();
            } else {
                System.out.println("Opción no válida");
            }
        }

        scanner.close();
    }
}