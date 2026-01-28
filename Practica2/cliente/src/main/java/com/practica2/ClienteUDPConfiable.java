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

    static class EstadoTransferencia {
        PublicKey publicaServidor;
        PrivateKey privateaCliente;
        PublicKey publicaCliente;
        String archivoCifrado;
        String archivoDescifrado;
        int lineasProcesadas;
        FileWriter escritorCifrado;
        FileWriter escritorDescifrado;

        EstadoTransferencia(String nombreArchivo) throws IOException {
            this.archivoCifrado = nombreArchivo.replace(".txt", "_CIFRADO.txt");
            this.archivoDescifrado = nombreArchivo.replace(".txt", "_DESCIFRADO.txt");
            this.lineasProcesadas = 0;
            this.escritorCifrado = new FileWriter(this.archivoCifrado);
            this.escritorDescifrado = new FileWriter(this.archivoDescifrado);
        }

        public void cerrar() throws IOException {
            if (escritorCifrado != null)
                escritorCifrado.close();
            if (escritorDescifrado != null)
                escritorDescifrado.close();
        }
    }

    public static void solicitarArchivo(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("\n╔════════════════════════════════════════╗");
        System.out.println("║  CLIENTE UDP CONFIABLE - CIFRADO RSA   ║");
        System.out.println("╚════════════════════════════════════════╝");
        System.out.println("Solicitando archivo: " + nombreArchivo);
        System.out.println("Servidor: " + ipDestino + ":22000\n");

        DatagramSocket socket = null;
        EstadoTransferencia estado = null;

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(10000);

            InetAddress ipServidor = InetAddress.getByName(ipDestino);
            final int PUERTO_SERVIDOR = 22000;

            System.out.println("Cliente en puerto: " + socket.getLocalPort() + "\n");

            // Generar claves RSA del cliente
            System.out.println("[INICIO] Generando claves RSA (2048 bits)...");
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            KeyPair parClaves = generador.generateKeyPair();
            System.out.println("✓ Claves generadas\n");

            // Crear estado de transferencia
            estado = new EstadoTransferencia(nombreArchivo);
            estado.publicaCliente = parClaves.getPublic();
            estado.privateaCliente = parClaves.getPrivate();

            // ===== 1. THREE-WAY HANDSHAKE =====
            System.out.println("[1] THREE-WAY HANDSHAKE");

            // SYN
            long seqInicial = System.currentTimeMillis() % 10000;
            String syn = "SYN:" + nombreArchivo + ":" + seqInicial;
            enviar(socket, ipServidor, PUERTO_SERVIDOR, syn);
            System.out.println("  → SYN (seq=" + seqInicial + ")");

            // SYN-ACK
            System.out.println("  ← Esperando SYN-ACK...");
            socket.setSoTimeout(5000);
            String synAck = recibir(socket);

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

            // Parsear SYN-ACK
            String[] partes = synAck.split(":", 4);
            long seqSynAck = Long.parseLong(partes[2]);
            String clavePublicaBase64 = partes[3];

            try {
                estado.publicaServidor = reconstructPublicKey(clavePublicaBase64);
                System.out.println("  ← SYN-ACK (seq=" + seqSynAck + ", con PublicKey servidor)");
            } catch (Exception e) {
                System.err.println("✗ Error al procesar clave pública: " + e.getMessage());
                return;
            }

            // ACK + PUBKEY
            String ack = "ACK:" + seqSynAck;
            String publicKeyMsg = "PUBKEY:" + Base64.getEncoder().encodeToString(estado.publicaCliente.getEncoded());

            enviar(socket, ipServidor, PUERTO_SERVIDOR, ack);
            System.out.println("  → ACK (ack=" + seqSynAck + ")");

            Thread.sleep(100);
            enviar(socket, ipServidor, PUERTO_SERVIDOR, publicKeyMsg);
            System.out.println("  → PUBKEY (clave pública del cliente)");
            System.out.println("✓ Conexión establecida\n");

            // ===== 2. RECIBIR ARCHIVO CIFRADO =====
            System.out.println("[2] RECIBIENDO ARCHIVO CIFRADO");
            System.out.println("  Archivo cifrado: " + estado.archivoCifrado);
            System.out.println("  Archivo descifrado: " + estado.archivoDescifrado);
            System.out.println("  Esperando datos...\n");

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
                            // Guardar línea cifrada
                            estado.escritorCifrado.write(contenido + "\n");
                            estado.lineasProcesadas++;

                            // Descifrar
                            try {
                                String lineaDescifrada = descifrarConRSA(contenido, estado.privateaCliente);
                                estado.escritorDescifrado.write(lineaDescifrada + "\n");

                                if (estado.lineasProcesadas % 5 == 0 || estado.lineasProcesadas == 1) {
                                    System.out.println("  ✓ Línea " + estado.lineasProcesadas + " procesada");
                                }
                            } catch (Exception e) {
                                System.err.println("  ⚠ Error descifrando línea " + estado.lineasProcesadas);
                                estado.escritorDescifrado.write("[ERROR AL DESCIFRAR]\n");
                            }

                        } else if (tamaño == 0 && contenido.equals("FIN-DATA")) {
                            System.out.println("\n  ← FIN-DATA recibido");
                            transferenciaCompleta = true;
                        }

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

            estado.cerrar();
            System.out.println("\n✓ Archivos guardados: " + estado.lineasProcesadas + " líneas\n");

            // ===== 3. CERRAR CONEXIÓN =====
            System.out.println("[3] CERRANDO CONEXIÓN");

            String fin = "FIN:" + secuenciaEsperada;
            enviar(socket, ipServidor, PUERTO_SERVIDOR, fin);
            System.out.println("  → FIN enviado");

            socket.setSoTimeout(2000);
            try {
                String respuesta = recibir(socket);
                if (respuesta != null && respuesta.startsWith("ACK:")) {
                    System.out.println("  ← ACK recibido");
                }
            } catch (SocketTimeoutException e) {
                // OK
            }

            socket.close();

            // ===== RESUMEN =====
            System.out.println("\n╔════════════════════════════════════════╗");
            System.out.println("║    ✓ TRANSFERENCIA COMPLETADA          ║");
            System.out.println("╚════════════════════════════════════════╝");
            System.out.println("Archivo: " + nombreArchivo);
            System.out.println("Líneas: " + estado.lineasProcesadas);
            System.out.println("\nGenerados:");
            System.out.println("  1. " + estado.archivoCifrado + " (BASE64 - contenido encriptado)");
            System.out.println("  2. " + estado.archivoDescifrado + " (contenido descifrado)\n");

        } catch (Exception e) {
            System.err.println("\n✗ ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (estado != null)
                    estado.cerrar();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // ===== CRIPTOGRAFÍA RSA =====

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

    private static String recibir(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[65507];
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
        socket.receive(paquete);
        return new String(paquete.getData(), 0, paquete.getLength());
    }

    // ===== MÉTODO MAIN =====

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n╔════════════════════════════════════════╗");
        System.out.println("║  CLIENTE UDP CONFIABLE - CIFRADO RSA   ║");
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