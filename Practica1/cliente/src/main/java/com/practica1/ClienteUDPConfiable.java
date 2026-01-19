package com.practica1;

import java.io.*;
import java.net.*;
import java.nio.file.*;

public class ClienteUDPConfiable {

    // FUNCIÓN SOLICITADA POR EL ENUNCIADO
    public static void transferirArchivo(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== Iniciando transferencia de archivo ===");
        System.out.println("IP Origen: " + ipOrigen);
        System.out.println("IP Destino: " + ipDestino);
        System.out.println("Archivo: " + nombreArchivo);

        try {
            // Verificar que el archivo existe
            Path rutaArchivo = Paths.get(nombreArchivo);
            if (!Files.exists(rutaArchivo)) {
                System.err.println("ERROR: El archivo '" + nombreArchivo + "' no existe.");
                return;
            }

            // Configurar conexión
            InetAddress ipServidor = InetAddress.getByName(ipDestino);
            int puertoServidor = 5000;

            // Crear socket UDP
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(3000); // Timeout de 3 segundos

            // Obtener IP local (origen)
            String ipLocal = InetAddress.getLocalHost().getHostAddress();
            System.out.println("IP Local detectada: " + ipLocal);

            // ===== THREE-WAY HANDSHAKE =====
            System.out.println("\n[1] Estableciendo conexión (Three-Way Handshake)...");

            // Paso 1: Enviar SYN
            long seqInicial = System.currentTimeMillis() % 10000; // Número de secuencia inicial
            String syn = "SYN:" + nombreArchivo + ":" + seqInicial;
            enviarPaquete(socket, ipServidor, puertoServidor, syn);
            System.out.println("  SYN enviado: " + syn);

            // Paso 2: Recibir SYN-ACK
            DatagramPacket respuesta = recibirPaquete(socket);
            String synAck = new String(respuesta.getData(), 0, respuesta.getLength());

            if (!synAck.startsWith("SYN-ACK:")) {
                System.err.println("ERROR: No se recibió SYN-ACK válido");
                socket.close();
                return;
            }
            System.out.println("  SYN-ACK recibido: " + synAck);

            // Paso 3: Enviar ACK
            String ackConexion = "ACK:" + (seqInicial + 1);
            enviarPaquete(socket, ipServidor, puertoServidor, ackConexion);
            System.out.println("  ACK enviado: " + ackConexion);
            System.out.println("✓ Conexión establecida con el servidor");

            // ===== TRANSFERENCIA DEL ARCHIVO =====
            System.out.println("\n[2] Transfiriendo archivo línea por línea...");

            BufferedReader lector = Files.newBufferedReader(rutaArchivo);
            String linea;
            long numeroSecuencia = seqInicial + 2; // Continuar secuencia
            int lineasEnviadas = 0;

            while ((linea = lector.readLine()) != null) {
                // Crear paquete DATA
                String dataPacket = "DATA:" + numeroSecuencia + ":" + linea.length() + ":" + linea;

                // Enviar con reintentos
                boolean ackRecibido = false;
                for (int intento = 1; intento <= 3 && !ackRecibido; intento++) {
                    try {
                        enviarPaquete(socket, ipServidor, puertoServidor, dataPacket);
                        System.out.println("  Enviada línea " + (lineasEnviadas + 1) +
                                " (seq=" + numeroSecuencia + ", intento=" + intento + ")");

                        // Esperar ACK
                        DatagramPacket ackPacket = recibirPaqueteConTimeout(socket, 2000);
                        String ack = new String(ackPacket.getData(), 0, ackPacket.getLength());

                        if (ack.equals("ACK:" + numeroSecuencia)) {
                            ackRecibido = true;
                            lineasEnviadas++;
                            System.out.println("    ACK recibido para seq=" + numeroSecuencia);
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("    Timeout, reintentando...");
                    }
                }

                if (!ackRecibido) {
                    System.err.println("ERROR: No se recibió ACK después de 3 intentos para seq=" + numeroSecuencia);
                    lector.close();
                    socket.close();
                    return;
                }

                numeroSecuencia++;
            }

            lector.close();
            System.out.println("✓ " + lineasEnviadas + " líneas enviadas correctamente");

            // ===== FINALIZACIÓN DE TRANSFERENCIA =====
            System.out.println("\n[3] Finalizando transferencia...");

            // Enviar paquete FIN de datos (tamaño 0)
            String finData = "DATA:" + numeroSecuencia + ":0:FIN";
            enviarPaquete(socket, ipServidor, puertoServidor, finData);
            recibirPaqueteConTimeout(socket, 2000); // Esperar ACK

            // ===== FOUR-WAY HANDSHAKE =====
            System.out.println("\n[4] Cerrando conexión (Four-Way Handshake)...");

            // Paso 1: Cliente envía FIN
            String fin = "FIN:" + (numeroSecuencia + 1);
            enviarPaquete(socket, ipServidor, puertoServidor, fin);
            System.out.println("  FIN enviado: " + fin);

            // Paso 2: Recibir ACK del servidor
            DatagramPacket ackFinPacket = recibirPaqueteConTimeout(socket, 2000);
            String ackFin = new String(ackFinPacket.getData(), 0, ackFinPacket.getLength());

            if (!ackFin.startsWith("ACK:")) {
                System.err.println("ERROR: No se recibió ACK para FIN");
                socket.close();
                return;
            }
            System.out.println("  ACK recibido: " + ackFin);

            // Paso 3: Recibir FIN del servidor
            DatagramPacket finServidorPacket = recibirPaqueteConTimeout(socket, 2000);
            String finServidor = new String(finServidorPacket.getData(), 0, finServidorPacket.getLength());

            if (!finServidor.startsWith("FIN:")) {
                System.err.println("ERROR: No se recibió FIN del servidor");
                socket.close();
                return;
            }
            System.out.println("  FIN recibido: " + finServidor);

            // Paso 4: Enviar ACK final
            String[] partes = finServidor.split(":");
            long seqFin = Long.parseLong(partes[1]);
            String ackFinal = "ACK:" + seqFin;
            enviarPaquete(socket, ipServidor, puertoServidor, ackFinal);
            System.out.println("  ACK final enviado: " + ackFinal);

            // Cerrar conexión
            socket.close();

            System.out.println("\n✅ Transferencia completada exitosamente!");
            System.out.println("   Archivo: " + nombreArchivo);
            System.out.println("   Líneas enviadas: " + lineasEnviadas);
            System.out.println("   Destino: " + ipDestino + ":5000");

        } catch (UnknownHostException e) {
            System.err.println("ERROR: Dirección IP no válida - " + e.getMessage());
        } catch (SocketException e) {
            System.err.println("ERROR: Problema con el socket - " + e.getMessage());
        } catch (IOException e) {
            System.err.println("ERROR: Problema de E/S - " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("ERROR inesperado: " + e.getMessage());
        }
    }

    // ===== MÉTODOS AUXILIARES =====

    private static void enviarPaquete(DatagramSocket socket, InetAddress ip, int puerto, String mensaje)
            throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static DatagramPacket recibirPaquete(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[1024];
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
        socket.receive(paquete);
        return paquete;
    }

    private static DatagramPacket recibirPaqueteConTimeout(DatagramSocket socket, int timeoutMs)
            throws IOException {
        socket.setSoTimeout(timeoutMs);
        try {
            return recibirPaquete(socket);
        } finally {
            socket.setSoTimeout(0); // Restaurar timeout
        }
    }

    // ===== MÉTODO MAIN PARA PRUEBAS =====

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Uso: java ClienteUDPConfiable <ip-origen> <ip-destino> <archivo>");
            System.out.println("Ejemplo: java ClienteUDPConfiable 192.168.1.100 192.168.1.200 prueba.txt");
            System.out.println("\nPara pruebas locales:");
            System.out.println("  java ClienteUDPConfiable 127.0.0.1 127.0.0.1 prueba.txt");
            return;
        }

        String ipOrigen = args[0];
        String ipDestino = args[1];
        String archivo = args[2];

        transferirArchivo(ipOrigen, ipDestino, archivo);
    }
}