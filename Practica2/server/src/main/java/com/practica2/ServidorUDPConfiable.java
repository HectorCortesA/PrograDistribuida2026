package com.practica2;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.util.Base64;
import javax.crypto.Cipher;

public class ServidorUDPConfiable {

    private static class EstadoConexion {
        String ip;
        int puerto;
        String estado;
        PublicKey publicaCliente;
        long secuencia;
        long ackEsperado;

        EstadoConexion(String ip, int puerto) {
            this.ip = ip;
            this.puerto = puerto;
            this.estado = Configuracion.ESTADO_SYN_RECIBIDO;
            this.secuencia = System.currentTimeMillis() % 10000;
            this.ackEsperado = 0;
        }
    }

    private static KeyPair parClaves;
    private static Map<String, EstadoConexion> conexiones = new HashMap<>();

    public static void iniciarServidor() {
        System.out.println("=== SERVIDOR UDP CONFIABLE CON CIFRADO RSA ===");
        System.out.println("Puerto: " + Configuracion.PUERTO_SERVIDOR);
        System.out.println("Directorio base: " + Configuracion.DIRECTORIO_BASE);
        System.out.println();

        // Generar claves RSA del servidor
        try {
            generarClaves();
        } catch (Exception e) {
            System.err.println("Error al generar claves: " + e.getMessage());
            return;
        }

        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(Configuracion.PUERTO_SERVIDOR);
            System.out.println("✓ Servidor iniciado y esperando conexiones...\n");

            while (true) {
                byte[] buffer = new byte[65507];
                DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                socket.receive(paquete);

                String ipCliente = paquete.getAddress().getHostAddress();
                int puertoCliente = paquete.getPort();
                String mensaje = new String(paquete.getData(), 0, paquete.getLength());

                System.out.println("[" + ipCliente + ":" + puertoCliente + "] → " +
                        (mensaje.length() > 50 ? mensaje.substring(0, 50) + "..." : mensaje));

                procesarMensaje(socket, ipCliente, puertoCliente, mensaje);
            }

        } catch (Exception e) {
            System.err.println("✗ Error del servidor: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    private static void procesarMensaje(DatagramSocket socket, String ipCliente,
            int puertoCliente, String mensaje) {
        try {
            String clave = ipCliente + ":" + puertoCliente;
            EstadoConexion estado = conexiones.getOrDefault(clave, new EstadoConexion(ipCliente, puertoCliente));

            if (mensaje.startsWith("SYN:")) {
                procesarSYN(socket, ipCliente, puertoCliente, mensaje, estado);
                conexiones.put(clave, estado);

            } else if (mensaje.startsWith("ACK:")) {
                procesarACK(socket, ipCliente, puertoCliente, mensaje, estado);

            } else if (mensaje.startsWith("FIN:")) {
                procesarFIN(socket, ipCliente, puertoCliente, mensaje, estado);
                conexiones.remove(clave);

            } else if (mensaje.startsWith("PUBKEY:")) {
                procesarPublicKey(mensaje, estado);

            } else {
                System.out.println(
                        "[" + ipCliente + ":" + puertoCliente + "] Mensaje desconocido: " + mensaje.substring(0, 30));
            }

        } catch (Exception e) {
            System.err.println("Error procesando mensaje: " + e.getMessage());
        }
    }

    private static void procesarSYN(DatagramSocket socket, String ipCliente, int puertoCliente,
            String mensaje, EstadoConexion estado) throws Exception {
        String[] partes = mensaje.split(":");
        String nombreArchivo = partes[1];
        long seqCliente = Long.parseLong(partes[2]);

        System.out.println("  [SYN] Solicitando: " + nombreArchivo + " (seq=" + seqCliente + ")");

        // Verificar que el archivo existe
        File archivo = new File(Configuracion.DIRECTORIO_BASE, nombreArchivo);
        if (!archivo.exists()) {
            String error = "ERROR:Archivo no encontrado";
            enviar(socket, ipCliente, puertoCliente, error);
            System.out.println("  [ERROR] Archivo no existe");
            return;
        }

        // Responder con SYN-ACK
        estado.ackEsperado = seqCliente + 1;
        estado.estado = Configuracion.ESTADO_SYN_RECIBIDO;
        String synAck = "SYN-ACK:PUBKEY:" + estado.secuencia + ":"
                + Base64.getEncoder().encodeToString(parClaves.getPublic().getEncoded());

        enviar(socket, ipCliente, puertoCliente, synAck);
        System.out.println("  [SYN-ACK] Enviando clave pública (seq=" + estado.secuencia + ")");
    }

    private static void procesarPublicKey(String mensaje, EstadoConexion estado) {
        try {
            String[] partes = mensaje.split(":", 2);
            String claveBase64 = partes[1];
            // Aquí se guardaría la clave pública del cliente si es necesaria
            System.out.println("  [PUBKEY] Clave pública del cliente recibida");
        } catch (Exception e) {
            System.err.println("Error procesando clave pública: " + e.getMessage());
        }
    }

    private static void procesarACK(DatagramSocket socket, String ipCliente, int puertoCliente,
            String mensaje, EstadoConexion estado) throws Exception {
        String[] partes = mensaje.split(":");
        long ack = Long.parseLong(partes[1]);

        if (ack == estado.ackEsperado) {
            estado.estado = Configuracion.ESTADO_ESTABLISHED;
            System.out.println("  [ACK] Conexión establecida");

            // Iniciar transferencia de archivo
            transferirArchivo(socket, ipCliente, puertoCliente, estado);
        }
    }

    private static void transferirArchivo(DatagramSocket socket, String ipCliente,
            int puertoCliente, EstadoConexion estado) throws Exception {
        System.out.println("\n[2] TRANSFIRIENDO ARCHIVO CIFRADO");

        // Obtener nombre del archivo del mensaje original (se asume que está guardado)
        // Para este ejemplo, usaremos un archivo de prueba
        File archivo = new File(Configuracion.DIRECTORIO_BASE);
        File[] archivos = archivo.listFiles();
        if (archivos == null || archivos.length == 0) {
            System.out.println("  No hay archivos en " + Configuracion.DIRECTORIO_BASE);
            return;
        }

        File archivoEnvio = archivos[0]; // Primer archivo

        try (BufferedReader lector = new BufferedReader(new FileReader(archivoEnvio))) {
            estado.estado = Configuracion.ESTADO_ENVIANDO;
            long secuencia = estado.secuencia + 1;
            String linea;
            int numLinea = 0;

            while ((linea = lector.readLine()) != null) {
                numLinea++;

                // Cifrar línea con la clave pública del cliente (usamos nuestra clave para
                // demo)
                String lineaCifrada = cifrarConRSA(linea, parClaves.getPublic());

                // Crear paquete DATA
                int tamaño = linea.length();
                String paquete = "DATA:" + secuencia + ":" + tamaño + ":" + lineaCifrada;

                enviar(socket, ipCliente, puertoCliente, paquete);
                System.out.println("  [DATA] Línea " + numLinea + " cifrada (seq=" + secuencia +
                        ", tamaño original=" + tamaño + ")");

                // Esperar ACK
                socket.setSoTimeout(Configuracion.TIMEOUT_RECEPCION);
                try {
                    byte[] buffer = new byte[65507];
                    DatagramPacket respuesta = new DatagramPacket(buffer, buffer.length);
                    socket.receive(respuesta);
                    String ackMsg = new String(respuesta.getData(), 0, respuesta.getLength());

                    if (ackMsg.startsWith("ACK:")) {
                        System.out.println("  [ACK] Línea " + numLinea + " confirmada");
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("  ⏱ Timeout esperando ACK para línea " + numLinea);
                }

                secuencia++;
            }

            // Enviar FIN-DATA
            String finData = "DATA:" + secuencia + ":0:FIN-DATA";
            enviar(socket, ipCliente, puertoCliente, finData);
            System.out.println("\n  [FIN-DATA] Transferencia completada (" + numLinea + " líneas)");

            estado.estado = Configuracion.ESTADO_TRANSFER_COMPLETE;

        } catch (Exception e) {
            System.err.println("Error transfiriendo archivo: " + e.getMessage());
        }
    }

    private static void procesarFIN(DatagramSocket socket, String ipCliente, int puertoCliente,
            String mensaje, EstadoConexion estado) throws Exception {
        System.out.println("\n[3] CERRANDO CONEXIÓN");

        // Responder con ACK
        String[] partes = mensaje.split(":");
        long seqFin = Long.parseLong(partes[1]);
        String ack = "ACK:" + seqFin;

        enviar(socket, ipCliente, puertoCliente, ack);
        System.out.println("  [ACK] Conexión cerrada correctamente\n");

        estado.estado = "CLOSED";
    }

    // ===== CRIPTOGRAFÍA RSA =====

    private static void generarClaves() throws Exception {
        System.out.println("Generando claves RSA (2048 bits)...");
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        parClaves = generador.generateKeyPair();
        System.out.println("✓ Claves RSA generadas\n");
    }

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

    // ===== MÉTODOS AUXILIARES =====

    private static void enviar(DatagramSocket socket, String ip, int puerto, String mensaje) throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length,
                InetAddress.getByName(ip), puerto);
        socket.send(paquete);
    }

    public static void main(String[] args) {
        iniciarServidor();
    }
}