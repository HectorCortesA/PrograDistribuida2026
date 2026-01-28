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
    private static String estadoConexion = ConfiguracionCliente.ESTADO_INICIAL;

    public static void solicitarArchivoSeguro(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== CLIENTE UDP SEGURO (TLS) ===");
        System.out.println("Solicitando archivo: " + nombreArchivo);
        System.out.println("Servidor: " + ipDestino + ":" + ConfiguracionCliente.PUERTO_SERVIDOR);
        System.out.println("====================================\n");

        DatagramSocket socket = null;
        try {
            // Configurar socket
            socket = new DatagramSocket();
            socket.setSoTimeout(ConfiguracionCliente.TIMEOUT_CONEXION);

            InetAddress ipServidor = InetAddress.getByName(ipDestino);
            final int PUERTO_SERVIDOR = ConfiguracionCliente.PUERTO_SERVIDOR;

            System.out.println("Cliente en puerto: " + socket.getLocalPort());
            System.out.println();

            // ===== 1. TLS HANDSHAKE =====
            System.out.println("[1] TLS HANDSHAKE PARA ESTABLECER CLAVE COMPARTIDA");
            cambiarEstado(ConfiguracionCliente.ESTADO_HANDSHAKE_TLS);

            // Cliente genera su par de claves RSA
            System.out.println("  Generando par de claves RSA-" + ConfiguracionCliente.TAMANIO_CLAVE_RSA + "...");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ConfiguracionCliente.ALGORITMO_RSA);
            kpg.initialize(ConfiguracionCliente.TAMANIO_CLAVE_RSA);
            KeyPair parClaves = kpg.generateKeyPair();
            System.out.println("  ✓ Par de claves RSA generado");

            // Enviar ClientHello con clave pública
            String clientHello = ConfiguracionCliente.TIPO_CLIENTHELLO + ":"
                    + Base64.getEncoder().encodeToString(parClaves.getPublic().getEncoded());
            enviar(socket, ipServidor, PUERTO_SERVIDOR, clientHello);
            System.out.println("  → ClientHello enviado (clave pública RSA)");

            // Recibir ServerHello
            System.out.println("  ← Esperando ServerHello...");
            String serverHello = recibirConTimeout(socket, ConfiguracionCliente.TIMEOUT_CONEXION);

            if (serverHello == null || !serverHello.startsWith(ConfiguracionCliente.TIPO_SERVERHELLO + ":")) {
                System.err.println("  ✗ ServerHello inválido");
                cambiarEstado(ConfiguracionCliente.ESTADO_CERRADO);
                return;
            }

            String[] partesSH = serverHello.split(":", 3);
            byte[] claveServidor = Base64.getDecoder().decode(partesSH[1]);
            byte[] claveAESCifrada = Base64.getDecoder().decode(partesSH[2]);

            // Desencriptar clave AES usando clave privada del cliente
            System.out.println("  Descifrando clave AES-" + ConfiguracionCliente.TAMANIO_CLAVE_AES + "...");
            Cipher cipherRSA = Cipher.getInstance(ConfiguracionCliente.CIPHER_RSA);
            cipherRSA.init(Cipher.DECRYPT_MODE, parClaves.getPrivate());
            byte[] claveAESDescifrada = cipherRSA.doFinal(claveAESCifrada);

            // Crear SecretKey
            claveCompartida = new SecretKeySpec(claveAESDescifrada, 0, claveAESDescifrada.length,
                    ConfiguracionCliente.ALGORITMO_AES);
            tiempoGeneracionClave = System.currentTimeMillis();

            System.out.println("  ✓ Clave AES-256 establecida");
            System.out.println("  ✓ Handshake TLS completado\n");

            // ===== 2. THREE-WAY HANDSHAKE CIFRADO =====
            System.out.println("[2] THREE-WAY HANDSHAKE (CIFRADO)");
            cambiarEstado(ConfiguracionCliente.ESTADO_HANDSHAKE_3W);

            long seqInicial = System.currentTimeMillis() % 10000;
            String synPlano = ConfiguracionCliente.TIPO_SYN + ":" + nombreArchivo + ":" + seqInicial;
            String synCifrado = cifrarAES(synPlano);

            enviar(socket, ipServidor, PUERTO_SERVIDOR, synCifrado);
            System.out.println("  → SYN cifrado enviado (seq=" + seqInicial + ")");

            // Recibir SYN-ACK
            System.out.println("  ← Esperando SYN-ACK cifrado...");
            String synAckCifrado = recibirConTimeout(socket, ConfiguracionCliente.TIMEOUT_CONEXION);

            if (synAckCifrado == null) {
                System.err.println("  ✗ Timeout: Servidor no responde");
                cambiarEstado(ConfiguracionCliente.ESTADO_CERRADO);
                return;
            }

            String synAck = descifrarAES(synAckCifrado);

            if (!synAck.startsWith(ConfiguracionCliente.TIPO_SYN_ACK + ":")) {
                System.err.println("  ✗ SYN-ACK inválido");
                cambiarEstado(ConfiguracionCliente.ESTADO_CERRADO);
                return;
            }

            String[] partes = synAck.split(":");
            long seqSynAck = Long.parseLong(partes[2]);
            System.out.println("  ← SYN-ACK cifrado recibido (seq=" + seqSynAck + ")");

            // Enviar ACK
            String ackPlano = ConfiguracionCliente.TIPO_ACK + ":" + seqSynAck;
            String ackCifrado = cifrarAES(ackPlano);
            enviar(socket, ipServidor, PUERTO_SERVIDOR, ackCifrado);
            System.out.println("  → ACK cifrado enviado");
            System.out.println("  ✓ Conexión segura establecida\n");
            cambiarEstado(ConfiguracionCliente.ESTADO_CONECTADO);

            // ===== 3. RECIBIR ARCHIVO CIFRADO =====
            System.out.println("[3] RECIBIENDO ARCHIVO CIFRADO");
            cambiarEstado(ConfiguracionCliente.ESTADO_TRANSFIRIENDO);

            String nombreLocal = nombreArchivo.replace(".txt", ConfiguracionCliente.SUFIJO_RECIBIDO);
            FileWriter escritor = new FileWriter(nombreLocal);
            int lineasRecibidas = 0;
            boolean transferenciaCompleta = false;

            System.out.println("  Guardando en: " + nombreLocal);
            System.out.println("  Esperando datos cifrados...\n");

            socket.setSoTimeout(ConfiguracionCliente.TIMEOUT_DATOS);

            while (!transferenciaCompleta) {
                // Verificar si clave expiró
                if (claveExpiro()) {
                    System.out.println("\n  ⏰ Clave compartida expirada, renegociando...");
                    renegociarClave(socket, parClaves, ipServidor);
                }

                try {
                    String paqueteCifrado = recibir(socket);
                    String paquete = descifrarAES(paqueteCifrado);

                    if (paquete.startsWith(ConfiguracionCliente.TIPO_DATA + ":")) {
                        String[] datos = paquete.split(":", 4);
                        long seq = Long.parseLong(datos[1]);
                        int tamaño = Integer.parseInt(datos[2]);
                        String contenido = datos[3];

                        // Enviar ACK cifrado
                        String ackPlanoData = ConfiguracionCliente.TIPO_ACK + ":" + seq;
                        String ackCifradoData = cifrarAES(ackPlanoData);
                        enviar(socket, ipServidor, PUERTO_SERVIDOR, ackCifradoData);

                        if (tamaño > 0) {
                            escritor.write(contenido + "\n");
                            lineasRecibidas++;

                            if (lineasRecibidas % 5 == 0 || lineasRecibidas == 1) {
                                System.out.println("    ✓ Líneas recibidas: " + lineasRecibidas);
                            }
                        } else if (tamaño == 0 && contenido.equals(ConfiguracionCliente.TIPO_FIN_DATA)) {
                            System.out.println("  ✓ FIN-DATA recibido (cifrado)");
                            transferenciaCompleta = true;
                        }

                    } else if (paquete.startsWith(ConfiguracionCliente.TIPO_FIN + ":")) {
                        String[] finPartes = paquete.split(":");
                        long seqFin = Long.parseLong(finPartes[1]);
                        String ackFinPlano = ConfiguracionCliente.TIPO_ACK + ":" + seqFin;
                        String ackFinCifrado = cifrarAES(ackFinPlano);
                        enviar(socket, ipServidor, PUERTO_SERVIDOR, ackFinCifrado);
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
            cambiarEstado(ConfiguracionCliente.ESTADO_CERRANDO);

            String finPlano = ConfiguracionCliente.TIPO_FIN + ":" + (System.currentTimeMillis() % 10000);
            String finCifrado = cifrarAES(finPlano);
            enviar(socket, ipServidor, PUERTO_SERVIDOR, finCifrado);
            System.out.println("  → FIN cifrado enviado");

            socket.close();
            cambiarEstado(ConfiguracionCliente.ESTADO_CERRADO);

            // ===== RESUMEN =====
            System.out.println("\n✅ TRANSFERENCIA SEGURA COMPLETADA");
            System.out.println("====================================");
            System.out.println("Archivo original: " + nombreArchivo);
            System.out.println("Guardado como: " + nombreLocal);
            System.out.println("Líneas recibidas: " + lineasRecibidas);
            System.out.println("Cifrado: AES-256");
            System.out.println("Handshake: TLS (RSA-2048)");
            System.out.println("Servidor: " + ipDestino + ":" + ConfiguracionCliente.PUERTO_SERVIDOR);

        } catch (Exception e) {
            System.err.println("\n✗ ERROR: " + e.getMessage());
            e.printStackTrace();
            cambiarEstado(ConfiguracionCliente.ESTADO_CERRADO);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    // ===== FUNCIONES DE CIFRADO AES =====

    private static String cifrarAES(String mensaje) throws Exception {
        Cipher cipher = Cipher.getInstance(ConfiguracionCliente.CIPHER_AES);
        cipher.init(Cipher.ENCRYPT_MODE, claveCompartida);
        byte[] encrypted = cipher.doFinal(mensaje.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private static String descifrarAES(String mensajeCifrado) throws Exception {
        Cipher cipher = Cipher.getInstance(ConfiguracionCliente.CIPHER_AES);
        cipher.init(Cipher.DECRYPT_MODE, claveCompartida);
        byte[] decodedBytes = Base64.getDecoder().decode(mensajeCifrado);
        byte[] decrypted = cipher.doFinal(decodedBytes);
        return new String(decrypted);
    }

    // ===== GESTIÓN DE CLAVES =====

    private static boolean claveExpiro() {
        return (System.currentTimeMillis() - tiempoGeneracionClave) > ConfiguracionCliente.TIEMPO_EXPIRACION_CLAVE;
    }

    private static void renegociarClave(DatagramSocket socket, KeyPair parClaves, InetAddress ipServidor)
            throws Exception {
        System.out.println("  Renegociando clave compartida...");

        // Solicitar nueva clave
        String renegociarMsg = ConfiguracionCliente.TIPO_RENEGOCIAR + ":"
                + Base64.getEncoder().encodeToString(parClaves.getPublic().getEncoded());
        enviar(socket, ipServidor, ConfiguracionCliente.PUERTO_SERVIDOR, renegociarMsg);

        // Recibir ServerHello con nueva clave
        String serverHello = recibirConTimeout(socket, ConfiguracionCliente.TIMEOUT_CIFRADO);
        String[] partesSH = serverHello.split(":", 3);
        byte[] claveAESCifrada = Base64.getDecoder().decode(partesSH[2]);

        Cipher cipherRSA = Cipher.getInstance(ConfiguracionCliente.CIPHER_RSA);
        cipherRSA.init(Cipher.DECRYPT_MODE, parClaves.getPrivate());
        byte[] claveAESDescifrada = cipherRSA.doFinal(claveAESCifrada);

        claveCompartida = new SecretKeySpec(claveAESDescifrada, 0, claveAESDescifrada.length,
                ConfiguracionCliente.ALGORITMO_AES);
        tiempoGeneracionClave = System.currentTimeMillis();

        System.out.println("  ✓ Clave renegociada exitosamente");
    }

    // ===== MÉTODOS AUXILIARES DE RED =====

    private static void enviar(DatagramSocket socket, InetAddress ip, int puerto, String mensaje)
            throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static String recibir(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[ConfiguracionCliente.BUFFER_SIZE];
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

    // ===== UTILIDADES =====

    private static void cambiarEstado(String nuevoEstado) {
        estadoConexion = nuevoEstadcleo;
        System.out.println("  [Estado: " + nuevoEstado + "]");
    }

    // ===== MÉTODO MAIN =====

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== CLIENTE UDP SEGURO (TLS) ===");
        System.out.println("Solicita archivos de forma segura");
        System.out.println("Puerto: " + ConfiguracionCliente.PUERTO_SERVIDOR + "\n");

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