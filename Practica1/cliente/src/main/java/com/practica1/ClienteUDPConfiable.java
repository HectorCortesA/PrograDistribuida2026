package com.practica1;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class ClienteUDPConfiable {

    public static void transferirArchivo(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== CLIENTE UDP CONFIABLE ===");
        System.out.println("IP Origen: " + ipOrigen);
        System.out.println("IP Destino: " + ipDestino);
        System.out.println("Archivo solicitado: " + nombreArchivo);
        System.out.println("=============================\n");

        DatagramSocket socket = null;
        try {
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

            socket.setSoTimeout(5000);

            // Mostrar información local
            System.out.println("Cliente iniciado en:");
            System.out.println("  IP: " + socket.getLocalAddress().getHostAddress());
            System.out.println("  Puerto: " + socket.getLocalPort());
            System.out.println();

            // ===== THREE-WAY HANDSHAKE =====
            System.out.println("[1] ESTABLECIENDO CONEXIÓN (Three-Way Handshake)...");

            // Paso 1: Enviar SYN con nombre de archivo solicitado
            long seqInicial = System.currentTimeMillis() % 10000;
            String synMsg = "SYN:" + nombreArchivo + ":" + seqInicial;
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, synMsg);
            System.out.println("  → SYN enviado: " + synMsg);

            // Paso 2: Recibir SYN-ACK
            String synAck = recibirPaqueteConTimeout(socket, 5000);
            if (synAck == null) {
                System.err.println("ERROR: Timeout esperando SYN-ACK");
                socket.close();
                return;
            }

            if (!synAck.startsWith("SYN-ACK:")) {
                if (synAck.startsWith("ERROR:")) {
                    System.err.println("ERROR del servidor: " + synAck);

                    // Si es error de archivo no encontrado, mostrar lista disponible
                    if (synAck.contains("no encontrado") || synAck.contains("no existe")) {
                        System.out.println("\nSolicitando lista de archivos disponibles...");
                        solicitarListaArchivos(socket, ipServidor, PUERTO_SERVIDOR);
                    }
                } else {
                    System.err.println("ERROR: No se recibió SYN-ACK válido. Recibido: " + synAck);
                }
                socket.close();
                return;
            }

            System.out.println("  ← SYN-ACK recibido: " + synAck);

            // Paso 3: Enviar ACK
            String ackMsg = "ACK:" + (seqInicial + 1);
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ackMsg);
            System.out.println("  → ACK enviado: " + ackMsg);
            System.out.println("✓ Conexión establecida con el servidor");

            // ===== RECIBIR ARCHIVO DEL SERVIDOR =====
            System.out.println("\n[2] RECIBIENDO ARCHIVO DEL SERVIDOR...");

            // Crear nombre para la copia local
            String nombreCopia;
            if (nombreArchivo.contains(".")) {
                nombreCopia = nombreArchivo.replaceFirst("\\.([^\\.]+)$", "_local.$1");
            } else {
                nombreCopia = nombreArchivo + "_local";
            }

            FileWriter escritor = new FileWriter(nombreCopia);
            int lineasRecibidas = 0;
            long secuenciaEsperada = seqInicial + 2;
            boolean recepcionTerminada = false;
            socket.setSoTimeout(5000);

            System.out.println("  Archivo local: " + nombreCopia);
            System.out.println("  Esperando datos del servidor...");

            while (!recepcionTerminada) {
                try {
                    String dataRx = recibirPaquete(socket);

                    if (dataRx.startsWith("DATA:")) {
                        String[] partes = dataRx.split(":", 4);
                        long seqRx = Long.parseLong(partes[1]);
                        int tamanio = Integer.parseInt(partes[2]);
                        String contenido = partes[3];

                        // Verificar secuencia
                        if (seqRx == secuenciaEsperada) {
                            // Enviar ACK
                            String ack = "ACK:" + seqRx;
                            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ack);

                            if (tamanio > 0) {
                                escritor.write(contenido + "\n");
                                lineasRecibidas++;
                                secuenciaEsperada++;

                                // Mostrar progreso cada 10 líneas
                                if (lineasRecibidas % 10 == 0 || lineasRecibidas == 1) {
                                    System.out.println("  ← Línea " + lineasRecibidas + " recibida");
                                }
                            } else if (tamanio == 0) {
                                // FIN de transferencia
                                System.out.println("  ← FIN-DATA recibido del servidor");
                                recepcionTerminada = true;
                            }
                        } else {
                            // Secuencia incorrecta, reenviar ACK del último correcto
                            System.out.println("  Secuencia incorrecta, reenviando ACK...");
                            String ack = "ACK:" + (secuenciaEsperada - 1);
                            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, ack);
                        }
                    } else if (dataRx.startsWith("ERROR:")) {
                        System.err.println("ERROR del servidor: " + dataRx);
                        break;
                    } else if (dataRx.startsWith("FIN:")) {
                        System.out.println("  FIN inesperado recibido");
                        recepcionTerminada = true;
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
            System.out.println("\n[3] CERRANDO CONEXIÓN (Four-Way Handshake)...");

            // Paso 1: Cliente envía FIN
            String fin = "FIN:" + secuenciaEsperada;
            enviarPaquete(socket, ipServidor, PUERTO_SERVIDOR, fin);
            System.out.println("  → FIN enviado: " + fin);

            // Paso 2: Recibir ACK del servidor
            String ackFin = recibirPaqueteConTimeout(socket, 3000);
            if (ackFin != null && ackFin.startsWith("ACK:")) {
                System.out.println("  ← ACK recibido: " + ackFin);
            } else {
                System.out.println("  No se recibió ACK para FIN");
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
            System.out.println("Archivo solicitado: " + nombreArchivo);
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

    // Método para solicitar lista de archivos disponibles
    private static void solicitarListaArchivos(DatagramSocket socket, InetAddress ipServidor, int puerto)
            throws IOException {
        System.out.println("\n=== ARCHIVOS DISPONIBLES EN SERVIDOR ===");

        // Enviar solicitud de lista
        String listaMsg = "LISTA";
        enviarPaquete(socket, ipServidor, puerto, listaMsg);

        // Recibir respuesta
        socket.setSoTimeout(3000);
        try {
            String respuesta = recibirPaquete(socket);
            System.out.println(respuesta);
        } catch (SocketTimeoutException e) {
            System.out.println("No se recibió respuesta del servidor");
        } finally {
            socket.setSoTimeout(0);
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
        byte[] buffer = new byte[4096];
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

    // ===== MÉTODO MAIN INTERACTIVO =====

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== CLIENTE UDP CONFIABLE ===");
        System.out.println("Sistema de transferencia de archivos");
        System.out.println("=====================================\n");

        // Configuración de red
        System.out.print("IP del servidor [localhost]: ");
        String ipDestino = scanner.nextLine().trim();
        if (ipDestino.isEmpty()) {
            ipDestino = "localhost";
        }

        System.out.print("IP origen [0.0.0.0 para todas]: ");
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
                // Ver lista de archivos
                try {
                    DatagramSocket socketTemp = new DatagramSocket();
                    InetAddress ipServidor = InetAddress.getByName(ipDestino);

                    System.out.println("\nSolicitando lista de archivos...");
                    enviarPaquete(socketTemp, ipServidor, 22000, "LISTA");

                    socketTemp.setSoTimeout(3000);
                    try {
                        String respuesta = recibirPaquete(socketTemp);
                        System.out.println("\n" + respuesta);
                    } catch (SocketTimeoutException e) {
                        System.out.println("Timeout: No hay respuesta del servidor");
                    }

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

                System.out.println("\nIniciando transferencia de: " + nombreArchivo);
                System.out.println("Servidor: " + ipDestino);
                System.out.println("========================================\n");

                transferirArchivo(ipOrigen, ipDestino, nombreArchivo);

                System.out.print("\nPresione Enter para continuar...");
                scanner.nextLine();
            } else {
                System.out.println("Opción no válida");
            }
        }

        scanner.close();
    }
}