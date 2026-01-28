package com.practica2;

import java.io.*;
import java.net.*;
import java.util.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.util.Base64;
import javax.crypto.Cipher;

public class ClienteUDPConfiable {

    private static class EstadoTransferencia {
        PublicKey publicaServidor;
        PrivateKey privateaCliente;
        PublicKey publicaCliente;
        String archivoOriginal;
        String archivoCifrado;
        String archivoDescifrado;
        int lineasProcesadas;

        EstadoTransferencia(String nombreArchivo) {
            this.archivoOriginal = nombreArchivo;
            this.archivoCifrado = nombreArchivo.replace(".txt", "_CIFRADO.txt");
            this.archivoDescifrado = nombreArchivo.replace(".txt", "_DESCIFRADO.txt");
            this.lineasProcesadas = 0;
        }
    }

    public static void solicitarArchivo(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== CLIENTE UDP CONFIABLE CON CIFRADO RSA ===");
        System.out.println("Solicitando archivo: " + nombreArchivo);
        System.out.println("Servidor: " + ipDestino + ":22000");
        System.out.println();

        DatagramSocket socket = null;
        EstadoTransferencia estado = new EstadoTransferencia(nombreArchivo);

        try {
            // Configurar socket
            socket = new DatagramSocket();
            socket.setSoTimeout(10000);

            InetAddress ipServidor = InetAddress.getByName(ipDestino);
            final int PUERTO_SERVIDOR = 22000;

            System.out.println("Cliente en puerto: " + socket.getLocalPort());
            System.out.println();

            // Generar claves RSA del cliente
            System.out.println("Generando claves RSA (2048 bits)...");
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            KeyPair parClaves = generador.generateKeyPair();
            estado.publicaCliente = parClaves.getPublic();
            estado.privateaCliente = parClaves.getPrivate();
            System.out.println("✓ Claves del cliente generadas\n");

            // ===== 1. THREE-WAY HANDSHAKE =====
            System.out.println("[1] THREE-WAY HANDSHAKE");

            // SYN
            long seqInicial = System.currentTimeMillis() % 10000;
            String syn = "SYN:" + nombreArchivo + ":" + seqInicial;
            enviar(socket, ipServidor, PUERTO_SERVIDOR, syn);
            System.out.println("  [SYN] Solicitando archivo (seq=" + seqInicial + ")");

            // SYN-ACK con clave pública del servidor
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

            // Parsear SYN-ACK y extraer clave pública del servidor
            String[] partes = synAck.split(":");
            long seqSynAck = Long.parseLong(partes[2]);
            String clavePublicaBase64 = partes[3];

            try {
                estado.publicaServidor = reconstructPublicKey(clavePublicaBase64);
                System.out.println("  [SYN-ACK] Clave pública del servidor recibida (seq=" + seqSynAck + ")");
            } catch (Exception e) {
                System.err.println("✗ Error al procesar clave pública: " + e.getMessage());
                return;
            }

            // ACK + enviar clave pública del cliente
            String ack = "ACK:" + seqSynAck;
            String publicKeyMsg = "PUBKEY:" + Base64.getEncoder().encodeToString(estado.publicaCliente.getEncoded());

            enviar(socket, ipServidor, PUERTO_SERVIDOR, ack);
            System.out.println("  [ACK] Confirmando conexión y enviando clave pública");

            // Esperar un poco antes de enviar la clave
            Thread.sleep(100);
            enviar(socket, ipServidor, PUERTO_SERVIDOR, publicKeyMsg);

            System.out.println("  ✓ Conexión establecida\n");

            // ===== 2. RECIBIR ARCHIVO CIFRADO =====
            System.out.println("[2] RECIBIENDO ARCHIVO CIFRADO");
            System.out.println("  Guardando archivo cifrado en: " + estado.archivoCifrado);
            System.out.println("  Guardando archivo descifrado en: " + estado.archivoDescifrado);
            System.out.println("  Esperando datos...\n");

            // Crear escritores para archivos cifrado y descifrado
            FileWriter escritorCifrado = new FileWriter(estado.archivoCifrado);
            FileWriter escritorDescifrado = new FileWriter(estado.archivoDescifrado);

            long secuenciaEsperada = seqSynAck + 1;
            boolean transferenciaCompleta = false;

            socket.setSoTimeout(15000);

            while (!transferenciaCompleta) {
                try {
                    String paquete = recibir(socket);

                    if (paquete.startsWith("DATA:")) {
                        String[] datos = paquete.split(":", 4);
                        long seq = Long.parseLong(datos[1]);
                        int tamaño = Integer.parseInt(datos[2]);
                        String contenido = datos[3];

                        // Enviar ACK inmediatamente
                        String ackData = "ACK:" + seq;
                        enviar(socket, ipServidor, PUERTO_SERVIDOR, ackData);

                        if (tamaño > 0) {
                            // Guardar línea cifrada tal como viene
                            escritorCifrado.write(contenido + "\n");
                            estado.lineasProcesadas++;

                            // Descifrar con nuestra clave privada
                            try {
                                String lineaDescifrada = descifrarConRSA(contenido, estado.privateaCliente);
                                escritorDescifrado.write(lineaDescifrada + "\n");

                                if (estado.lineasProcesadas % 5 == 0 || estado.lineasProcesadas == 1) {
                                    System.out.println("  ✓ Línea " + estado.lineasProcesadas +
                                            " recibida, descifrada y guardada");
                                }
                            } catch (Exception e) {
                                System.err.println("  ⚠ Error descifrando línea " + estado.lineasProcesadas +
                                        ": " + e.getMessage());
                                escritorDescifrado.write("[ERROR AL DESCIFRAR]\n");
                            }

                        } else if (tamaño == 0 && contenido.equals("FIN-DATA")) {
                            System.out.println("\n  ← FIN-DATA recibido");
                            transferenciaCompleta = true;
                        }

                        System.out.println("  → ACK (seq=" + seq + ")");

                    } else if (paquete.startsWith("FIN:")) {
                        System.out.println("  ← FIN recibido");
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

            escritorCifrado.close();
            escritorDescifrado.close();

            System.out.println("\n✓ Archivos guardados:");
            System.out.println("  • Cifrado: " + estado.archivoCifrado + " (" + estado.lineasProcesadas + " líneas)");
            System.out.println(
                    "  • Descifrado: " + estado.archivoDescifrado + " (" + estado.lineasProcesadas + " líneas)");

            // ===== 3. CERRAR CONEXIÓN =====
            System.out.println("\n[3] CERRANDO CONEXIÓN");

            String fin = "FIN:" + secuenciaEsperada;
            enviar(socket, ipServidor, PUERTO_SERVIDOR, fin);
            System.out.println("  → FIN enviado");

            socket.setSoTimeout(2000);
            try {
                String respuesta = recibir(socket);
                if (respuesta.startsWith("ACK:")) {
                    System.out.println("  ← ACK recibido");
                }
            } catch (SocketTimeoutException e) {
                // Timeout OK
            }

            socket.close();

            // ===== RESUMEN =====
            System.out.println("\n=== TRANSFERENCIA COMPLETADA ===");
            System.out.println("Archivo solicitado: " + nombreArchivo);
            System.out.println("Líneas transferidas: " + estado.lineasProcesadas);
            System.out.println("\nArchivos generados:");
            System.out.println("  1. " + estado.archivoCifrado + " (contenido cifrado con RSA)");
            System.out.println("  2. " + estado.archivoDescifrado + " (contenido descifrado)");
            System.out.println("\nServidor: " + ipDestino);

        } catch (Exception e) {
            System.err.println("\n✗ ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // ===== CRIPTOGRAFÍA RSA =====

    private static String cifrarConRSA(String texto, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        byte[] textoCifrado = cipher.doFinal(texto.getBytes());
        return Base64.getEncoder().encodeToString(textoCifrado);
    }

    private static String descifrarConRSA(String textoCifrado, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

        byte[] texto = Base64.getDecoder().decode(textoCifrado);
        byte[] textoDescifrado = cipher.doFinal(texto);
        return new String(textoDescifrado);
    }

    private static PublicKey reconstructPublicKey(String base64PublicKey) throws Exception {
        byte[] decodedKey = Base64.getDecoder().decode(base64PublicKey);
        java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(decodedKey);
        java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }

    // ===== MÉTODOS AUXILIARES =====

    private static void enviar(DatagramSocket socket, InetAddress ip, int puerto, String mensaje)
            throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static void enviar(DatagramSocket socket, InetAddress ip, int puerto, byte[] buffer)
            throws IOException {
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

    // ===== MÉTODO MAIN =====

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║  CLIENTE UDP CONFIABLE - CIFRADO RSA   ║");
        System.out.println("║  Descarga archivos cifrados del servidor║");
        System.out.println("╚════════════════════════════════════════╝\n");

        System.out.print("IP del servidor (default: localhost): ");
        String ipServidor = scanner.nextLine().trim();
        if (ipServidor.isEmpty()) {
            ipServidor = "localhost";
        }

        System.out.print("Archivo a solicitar: ");
        String nombreArchivo = scanner.nextLine().trim();

        if (nombreArchivo.isEmpty()) {
            System.out.println("✗ Debe especificar un archivo");
            scanner.close();
            return;
        }

        scanner.close();

        // Solicitar archivo
        solicitarArchivo("0.0.0.0", ipServidor, nombreArchivo);
    }
}