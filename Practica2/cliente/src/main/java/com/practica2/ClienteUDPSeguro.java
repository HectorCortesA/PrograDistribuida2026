package com.practica2;

import java.io.*;
import java.net.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class ClienteUDPSeguro {

    private static SecretKey claveCompartida;
    private static long tiempoGeneracionClave;
    private static final long TIEMPO_EXPIRACION_CLAVE = 300000; // 5 minutos

    public static void solicitarArchivoSeguro(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== CLIENTE UDP SEGURO (TLS) ===");
        System.out.println("Solicitando archivo: " + nombreArchivo);
        System.out.println("Servidor: " + ipDestino + ":" + Configuracion.PUERTO_SERVIDOR);

        DatagramSocket socket = null;
        try {
            // Configurar socket
            socket = new DatagramSocket();
            socket.setSoTimeout(10000);

            InetAddress ipServidor = InetAddress.getByName(ipDestino);
            final int PUERTO_SERVIDOR = Configuracion.PUERTO_SERVIDOR;

            System.out.println("Cliente en puerto: " + socket.getLocalPort());
            System.out.println();

            // ===== 1. TLS HANDSHAKE =====
            System.out.println("[1] TLS HANDSHAKE PARA ESTABLECER CLAVE COMPARTIDA");

            // Cliente genera su par de claves RSA
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair parClaves = kpg.generateKeyPair();

            // Enviar ClientHello con clave pública
            String clientHello = "CLIENTHELLO:"
                    + Base64.getEncoder().encodeToString(parClaves.getPublic().getEncoded());
            enviar(socket, ipServidor, PUERTO_SERVIDOR, clientHello);
            System.out.println("  → ClientHello (RSA Public Key enviada)");

            // Recibir ServerHello con clave pública del servidor y clave AES cifrada
            System.out.println("  ← Esperando ServerHello...");
            String serverHello = recibirConTimeout(socket, 5000);

            if (serverHello == null || !serverHello.startsWith("SERVERHELLO:")) {
                System.err.println("  ✗ ServerHello inválido");
                return;
            }

            String[] partesSH = serverHello.split(":", 3);
            byte[] claveServidor = Base64.getDecoder().decode(partesSH[1]);
            byte[] claveAESCifrada = Base64.getDecoder().decode(partesSH[2]);

            // Desencriptar clave AES usando clave privada del cliente
            Cipher cipherRSA = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipherRSA.init(Cipher.DECRYPT_MODE, parClaves.getPrivate());
            byte[] claveAESDescifrada = cipherRSA.doFinal(claveAESCifrada);

            // Crear SecretKey a partir de bytes
            claveCompartida = new SecretKeySpec(claveAESDescifrada, 0, claveAESDescifrada.length, "AES");
            tiempoGeneracionClave = System.currentTimeMillis();

            System.out.println("  ← ServerHello recibido (Clave AES establecida)");
            System.out.println("  ✓ Handshake TLS completado\n");

            // ===== 2. THREE-WAY HANDSHAKE CIFRADO =====
            System.out.println("[2] THREE-WAY HANDSHAKE (CIFRADO)");

            long seqInicial = System.currentTimeMillis() % 10000;
            String synPlano = "SYN:" + nombreArchivo + ":" + seqInicial;
            String synCifrado = cifrarAES(synPlano);

            enviar(socket, ipServidor, Configuracion.PUERTO_SERVIDOR, synCifrado);
            System.out.println("  → SYN cifrado enviado (seq=" + seqInicial + ")");

            // Recibir SYN-ACK
            System.out.println("  ← Esperando SYN-ACK cifrado...");
            String synAckCifrado = recibirConTimeout(socket, 5000);

            if (synAckCifrado == null) {
                System.err.println("  ✗ Timeout: Servidor no responde");
                return;
            }

            String synAck = descifrarAES(synAckCifrado);

            if (!synAck.startsWith("SYN-ACK:")) {
                System.err.println("  ✗ SYN-ACK inválido");
                return;
            }

            String[] partes = synAck.split(":");
            long seqSynAck = Long.parseLong(partes[2]);
            System.out.println("  ← SYN-ACK cifrado recibido (seq=" + seqSynAck + ")");

            // Enviar ACK
            String ackPlano = "ACK:" + seqSynAck;
            String ackCifrado = cifrarAES(ackPlano);
            enviar(socket, ipServidor, Configuracion.PUERTO_SERVIDOR, ackCifrado);
            System.out.println("  → ACK cifrado enviado");
            System.out.println("  ✓ Conexión segura establecida\n");

            // ===== 3. RECIBIR ARCHIVO CIFRADO =====
            System.out.println("[3] RECIBIENDO ARCHIVO CIFRADO");

            String nombreLocal = nombreArchivo.replace(".txt", "_recibido.txt");
            FileWriter escritor = new FileWriter(nombreLocal);
            int lineasRecibidas = 0;
            boolean transferenciaCompleta = false;

            System.out.println("  Guardando en: " + nombreLocal);
            System.out.println("  Esperando datos cifrados...\n");

            socket.setSoTimeout(15000);

            while (!transferenciaCompleta) {
                // Verificar si clave expiró
                if (claveExpiro()) {
                    System.out.println("\n  ⏰ Clave compartida expirada, renegociando...");
                    renegociarClave(socket, parClaves, ipServidor);
                }

                try {
                    String paqueteCifrado = recibir(socket);
                    String paquete = descifrarAES(paqueteCifrado);

                    if (paquete.startsWith("DATA:")) {
                        String[] datos = paquete.split(":", 4);
                        long seq = Long.parseLong(datos[1]);
                        int tamaño = Integer.parseInt(datos[2]);
                        String contenido = datos[3];

                        // Enviar ACK cifrado
                        String ackPlanoData = "ACK:" + seq;
                        String ackCifradoData = cifrarAES(ackPlanoData);
                        enviar(socket, ipServidor, Configuracion.PUERTO_SERVIDOR, ackCifradoData);

                        if (tamaño > 0) {
                            escritor.write(contenido + "\n");
                            lineasRecibidas++;

                            if (lineasRecibidas % 5 == 0 || lineasRecibidas == 1) {
                                System.out.println("    ✓ Líneas recibidas: " + lineasRecibidas);
                            }
                        } else if (tamaño == 0 && contenido.equals("FIN-DATA")) {
                            System.out.println("  ✓ FIN-DATA recibido (cifrado)");
                            transferenciaCompleta = true;
                        }

                    } else if (paquete.startsWith("FIN:")) {
                        String[] finPartes = paquete.split(":");
                        long seqFin = Long.parseLong(finPartes[1]);
                        String ackFinPlano = "ACK:" + seqFin;
                        String ackFinCifrado = cifrarAES(ackFinPlano);
                        enviar(socket, ipServidor, Configuracion.PUERTO_SERVIDOR, ackFinCifrado);
                        break;
                    }

                } catch (SocketTimeoutException e) {
                    System.out.println("  ⏱ Timeout: No más datos");
                    transferenciaCompleta = true;
                }
            }

            escritor.close();
            System.out.println("\n✓ Archivo descifrado y guardado: " + lineasRecibidas + " líneas");

            // ===== 4. CERRAR CONEXIÓN SEGURA =====
            System.out.println("\n[4] CERRANDO CONEXIÓN SEGURA");

            String finPlano = "FIN:" + (System.currentTimeMillis() % 10000);
            String finCifrado = cifrarAES(finPlano);
            enviar(socket, ipServidor, Configuracion.PUERTO_SERVIDOR, finCifrado);
            System.out.println("  → FIN cifrado enviado");

            socket.close();

            // ===== RESUMEN =====
            System.out.println("\n✅ TRANSFERENCIA SEGURA COMPLETADA");
            System.out.println("   Archivo: " + nombreArchivo);
            System.out.println("   Guardado: " + nombreLocal);
            System.out.println("   Líneas: " + lineasRecibidas);
            System.out.println("   Cifrado: AES-256");
            System.out.println("   Handshake: TLS");

        } catch (Exception e) {
            System.err.println("\n✗ ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // ===== FUNCIONES DE CIFRADO =====

    private static String cifrarAES(String mensaje) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, claveCompartida);
        byte[] encrypted = cipher.doFinal(mensaje.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private static String descifrarAES(String mensajeCifrado) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, claveCompartida);
        byte[] decodedBytes = Base64.getDecoder().decode(mensajeCifrado);
        byte[] decrypted = cipher.doFinal(decodedBytes);
        return new String(decrypted);
    }

    // ===== VERIFICACIÓN Y RENEGOCIACIÓN DE CLAVE =====

    private static boolean claveExpiro() {
        return (System.currentTimeMillis() - tiempoGeneracionClave) > TIEMPO_EXPIRACION_CLAVE;
    }

    private static void renegociarClave(DatagramSocket socket, KeyPair parClaves, InetAddress ipServidor)
            throws Exception {
        System.out.println("  Renegociando clave compartida...");

        // Solicitar nueva clave
        String renegociarMsg = "RENEGOCIAR:" + Base64.getEncoder().encodeToString(parClaves.getPublic().getEncoded());
        enviar(socket, ipServidor, Configuracion.PUERTO_SERVIDOR, renegociarMsg);

        // Recibir ServerHello con nueva clave
        String serverHello = recibirConTimeout(socket, 5000);
        String[] partesSH = serverHello.split(":", 3);
        byte[] claveAESCifrada = Base64.getDecoder().decode(partesSH[2]);

        Cipher cipherRSA = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipherRSA.init(Cipher.DECRYPT_MODE, parClaves.getPrivate());
        byte[] claveAESDescifrada = cipherRSA.doFinal(claveAESCifrada);

        claveCompartida = new SecretKeySpec(claveAESDescifrada, 0, claveAESDescifrada.length, "AES");
        tiempoGeneracionClave = System.currentTimeMillis();

        System.out.println("  ✓ Clave renegociada exitosamente");
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

    private static String recibirConTimeout(DatagramSocket socket, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        try {
            return recibir(socket);
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    // ===== MAIN =====

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== CLIENTE UDP SEGURO (TLS) ===");
        System.out.println("Solicita archivos de forma segura");
        System.out.println("Puerto: " + Configuracion.PUERTO_SERVIDOR + "\n");

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

        solicitarArchivoSeguro("0.0.0.0", ipServidor, nombreArchivo);
    }
}