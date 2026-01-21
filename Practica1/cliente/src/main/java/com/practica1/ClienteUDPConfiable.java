package com.practica1;

import java.io.*;
import java.net.*;
import java.nio.file.*;

public class ClienteUDPConfiable {

    // FUNCIÓN SOLICITADA POR EL ENUNCIADO
    public static void transferirArchivo(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("Iniciando transferencia de archivo");
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
            socket.setSoTimeout(5000); // Timeout de 5 segundos

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
            DatagramPacket respuesta = recibirPaqueteConTimeout(socket, 5000);
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

            // ===== TRANSFERENCIA DEL ARCHIVO (CLIENTE -> SERVIDOR) =====
            System.out.println("\n[2] Transfiriendo archivo al servidor (línea por línea)...");

            BufferedReader lector = Files.newBufferedReader(rutaArchivo);
            String linea;
            long numeroSecuencia = seqInicial + 2; // Continuar secuencia
            int lineasEnviadas = 0;

            while ((linea = lector.readLine()) != null) {
                // Ignorar líneas vacías
                if (linea.trim().isEmpty()) {
                    continue;
                }

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
                        } else if (ack.startsWith("ERROR:")) {
                            System.err.println("ERROR del servidor: " + ack);
                            break;
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
            System.out.println(lineasEnviadas + " líneas enviadas correctamente al servidor");

            // ===== FINALIZACIÓN DE TRANSFERENCIA CLIENTE -> SERVIDOR =====
            System.out.println("\n[2b] Finalizando envío al servidor...");

            // Enviar paquete FIN de datos (tamaño 0)
            String finData = "DATA:" + numeroSecuencia + ":0:FIN";
            enviarPaquete(socket, ipServidor, puertoServidor, finData);
            System.out.println("  FIN-DATA enviado: " + finData);

            // Esperar ACK del FIN-DATA
            try {
                DatagramPacket ackFinData = recibirPaqueteConTimeout(socket, 3000);
                String ack = new String(ackFinData.getData(), 0, ackFinData.getLength());
                if (ack.equals("ACK:" + numeroSecuencia)) {
                    System.out.println("  ACK recibido para FIN-DATA");
                }
            } catch (SocketTimeoutException e) {
                System.out.println("  No se recibió ACK para FIN-DATA, continuando...");
            }

            numeroSecuencia++;

            // ===== RECIBIENDO ARCHIVO DEL SERVIDOR =====
            System.out.println("\n[3] Recibiendo archivo del servidor...");

            // Crear nombre diferente para el archivo recibido
            String nombreArchivoRecibido = nombreArchivo.replace(".txt", "_recibido.txt");
            FileWriter escritor = new FileWriter(nombreArchivoRecibido);
            int lineasRecibidas = 0;
            boolean recepcionTerminada = false;
            socket.setSoTimeout(5000); // Aumentar timeout para recepción

            while (!recepcionTerminada) {
                try {
                    DatagramPacket dataRx = recibirPaquete(socket);
                    String dataMsg = new String(dataRx.getData(), 0, dataRx.getLength());

                    System.out.println("  Paquete recibido: " + dataMsg); // DEBUG

                    if (dataMsg.startsWith("DATA:")) {
                        String[] partes = dataMsg.split(":", 4);
                        if (partes.length >= 4) {
                            long seqRx = Long.parseLong(partes[1]);
                            int tamanio = Integer.parseInt(partes[2]);
                            String lineaRx = partes[3];

                            // Enviar ACK inmediatamente
                            String ack = "ACK:" + seqRx;
                            enviarPaquete(socket, ipServidor, puertoServidor, ack);
                            System.out.println("    ACK enviado: " + ack);

                            if (tamanio > 0 && !lineaRx.equals("FIN")) {
                                escritor.write(lineaRx + "\n");
                                lineasRecibidas++;
                                System.out.println("  Línea " + lineasRecibidas + " recibida: '" + lineaRx + "'");
                            } else if (tamanio == 0) {
                                // FIN de transferencia desde servidor
                                System.out.println("  FIN recibido del servidor, transferencia completada");
                                recepcionTerminada = true;
                            }
                        }
                    } else if (dataMsg.startsWith("ERROR:")) {
                        System.err.println("ERROR del servidor: " + dataMsg);
                        break;
                    } else if (dataMsg.startsWith("FIN:")) {
                        // El servidor inició el cierre
                        System.out.println("  FIN recibido, iniciando cierre...");
                        recepcionTerminada = true;
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("INFO: Timeout esperando más datos del servidor, continuando con cierre...");
                    recepcionTerminada = true;
                } catch (Exception e) {
                    System.err.println("ERROR procesando paquete: " + e.getMessage());
                    break;
                }
            }

            escritor.close();
            socket.setSoTimeout(0); // Restaurar timeout a infinito
            System.out.println(lineasRecibidas + " líneas recibidas y guardadas en: " + nombreArchivoRecibido);

            // ===== FOUR-WAY HANDSHAKE =====
            System.out.println("\n[4] Cerrando conexión (Four-Way Handshake)...");

            // Paso 1: Cliente envía FIN
            String fin = "FIN:" + numeroSecuencia;
            enviarPaquete(socket, ipServidor, puertoServidor, fin);
            System.out.println("  FIN enviado: " + fin);

            // Paso 2: Recibir ACK del servidor
            DatagramPacket ackFinPacket = recibirPaqueteConTimeout(socket, 3000);
            if (ackFinPacket != null) {
                String ackFinMsg = new String(ackFinPacket.getData(), 0, ackFinPacket.getLength());

                if (!ackFinMsg.startsWith("ACK:")) {
                    System.err.println("ERROR: No se recibió ACK válido para FIN");
                } else {
                    System.out.println("  ACK recibido: " + ackFinMsg);
                }
            } else {
                System.out.println("  No se recibió ACK para FIN, continuando...");
            }

            // Paso 3: Recibir FIN del servidor
            DatagramPacket finServidorPacket = recibirPaqueteConTimeout(socket, 3000);
            if (finServidorPacket != null) {
                String finServidor = new String(finServidorPacket.getData(), 0, finServidorPacket.getLength());

                if (!finServidor.startsWith("FIN:")) {
                    System.err.println("ERROR: No se recibió FIN del servidor");
                } else {
                    System.out.println("  FIN recibido: " + finServidor);

                    // Paso 4: Enviar ACK final
                    String[] partes = finServidor.split(":");
                    long seqFin = Long.parseLong(partes[1]);
                    String ackFinal = "ACK:" + seqFin;
                    enviarPaquete(socket, ipServidor, puertoServidor, ackFinal);
                    System.out.println("  ACK final enviado: " + ackFinal);
                }
            }

            // Cerrar conexión
            socket.close();

            System.out.println("\n✅ Transferencia completada exitosamente!");
            System.out.println("   Archivo original enviado: " + nombreArchivo);
            System.out.println("   Líneas enviadas al servidor: " + lineasEnviadas);
            System.out.println("   Copia recibida y guardada: " + nombreArchivoRecibido);
            System.out.println("   Líneas recibidas de servidor: " + lineasRecibidas);
            System.out.println("   Servidor: " + ipDestino + ":5000");

        } catch (UnknownHostException e) {
            System.err.println("ERROR: Dirección IP no válida - " + e.getMessage());
        } catch (SocketException e) {
            System.err.println("ERROR: Problema con el socket - " + e.getMessage());
        } catch (IOException e) {
            System.err.println("ERROR: Problema de E/S - " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("ERROR inesperado: " + e.getMessage());
            e.printStackTrace();
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
            byte[] buffer = new byte[1024];
            DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
            socket.receive(paquete);
            return paquete;
        } catch (SocketTimeoutException e) {
            return null;
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