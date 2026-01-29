package com.practica2;

import java.io.*;
import java.net.*;
import java.util.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import javax.crypto.Cipher;

public class ServidorUDPConfiable {

    static class ClienteConexion {
        String ip;
        int puerto;
        String nombreArchivo;
        PublicKey clavePublicaCliente;
        boolean listo = false;
    }

    private static KeyPair parClavesServidor;
    private static DatagramSocket socketServidor;
    private static Map<String, ClienteConexion> clientes = new HashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("  SERVIDOR UDP - CIFRADO RSA TLS ");

        // Generar claves RSA del servidor
        System.out.println("Generando claves RSA 2048 bits...");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        parClavesServidor = kpg.generateKeyPair();
        System.out.println("Claves generadas\n");

        // Iniciar servidor UDP
        socketServidor = new DatagramSocket(Configuracion.PUERTO_SERVIDOR);
        System.out.println(" Escuchando en puerto " + Configuracion.PUERTO_SERVIDOR + "\n");

        byte[] buffer = new byte[65507];

        while (true) {
            try {
                DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                socketServidor.receive(paquete);

                String ip = paquete.getAddress().getHostAddress();
                int puerto = paquete.getPort();
                String mensaje = new String(paquete.getData(), 0, paquete.getLength()).trim();

                String preview = mensaje.length() > 60 ? mensaje.substring(0, 60) + "..." : mensaje;
                System.out.println("[" + ip + ":" + puerto + "] RX: " + preview);

                procesarMensaje(ip, puerto, mensaje);

            } catch (Exception e) {
                System.err.println("[ERROR] " + e.getMessage());
            }
        }
    }

    private static void procesarMensaje(String ip, int puerto, String mensaje) throws Exception {
        String clave = ip + ":" + puerto;

        if (mensaje.startsWith("SYN:")) {
            String[] partes = mensaje.split(":");
            String archivo = partes[1];

            System.out.println("  [SYN] Archivo: " + archivo);

            // Verificar archivo
            File f = new File(Configuracion.DIRECTORIO_BASE, archivo);
            if (!f.exists()) {
                System.err.println("  [ERROR] No existe: " + f.getAbsolutePath());
                enviar(ip, puerto, "ERROR:Archivo no encontrado");
                return;
            }

            System.out.println("  [OK] Encontrado: " + f.length() + " bytes");

            // Guardar cliente
            ClienteConexion cliente = new ClienteConexion();
            cliente.ip = ip;
            cliente.puerto = puerto;
            cliente.nombreArchivo = archivo;
            clientes.put(clave, cliente);

            // Enviar SYN-ACK
            String claveB64 = Base64.getEncoder().encodeToString(
                    parClavesServidor.getPublic().getEncoded());
            String respuesta = "SYN-ACK:" + (System.currentTimeMillis() % 10000) + ":" + claveB64;
            enviar(ip, puerto, respuesta);
            System.out.println("  [TX] SYN-ACK enviado\n");

        } else if (mensaje.startsWith("ACK:")) {
            System.out.println("  [ACK] Confirmación\n");

        } else if (mensaje.startsWith("PUBKEY:")) {
            System.out.println("  [PUBKEY] Clave pública del cliente");

            ClienteConexion cliente = clientes.get(clave);
            if (cliente == null) {
                System.err.println("  [ERROR] Cliente no encontrado");
                return;
            }

            try {
                // Extraer clave
                String[] partes = mensaje.split(":", 2);
                String claveB64 = partes[1];

                System.out.println("  [DEBUG] Clave Base64 length: " + claveB64.length());

                // Decodificar
                byte[] claveDecodificada = Base64.getDecoder().decode(claveB64);
                System.out.println("  [DEBUG] Clave decodificada: " + claveDecodificada.length + " bytes");

                // Reconstruir
                java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(
                        claveDecodificada);
                cliente.clavePublicaCliente = java.security.KeyFactory.getInstance("RSA").generatePublic(spec);

                cliente.listo = true;
                System.out.println("  [OK] Clave guardada - Iniciando cifrado\n");

                // Enviar archivo AHORA
                enviarArchivo(cliente);

            } catch (Exception e) {
                System.err.println("  [ERROR] " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void enviarArchivo(ClienteConexion cliente) {
        System.out.println("[TRANSFER] Enviando: " + cliente.nombreArchivo);

        try {
            File archivo = new File(Configuracion.DIRECTORIO_BASE, cliente.nombreArchivo);
            BufferedReader reader = new BufferedReader(new FileReader(archivo));
            String linea;
            int numLinea = 0;
            long seq = 1000;

            while ((linea = reader.readLine()) != null) {
                numLinea++;

                System.out.println("  [LÍNEA " + numLinea + "] Original: " +
                        (linea.length() > 40 ? linea.substring(0, 40) + "..." : linea));

                // CIFRAR con clave pública del cliente
                System.out.println("  [DEBUG] Cifrando con clave del cliente...");
                String lineaCifrada = cifrar(linea, cliente.clavePublicaCliente);
                System.out.println("  [DEBUG] Cifrado: " + lineaCifrada.substring(0, 50) + "...");

                // Enviar
                String paqueteDATA = "DATA:" + seq + ":" + linea.length() + ":" + lineaCifrada;
                enviar(cliente.ip, cliente.puerto, paqueteDATA);

                System.out.println("  [TX] DATA enviado (" + lineaCifrada.length() + " bytes)\n");

                seq++;
                Thread.sleep(150);
            }
            reader.close();

            // Enviar FIN
            String paqueteFIN = "DATA:" + seq + ":0:FIN";
            enviar(cliente.ip, cliente.puerto, paqueteFIN);
            System.out.println("[FIN] Completado (" + numLinea + " líneas)\n");

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String cifrar(String texto, PublicKey clave) throws Exception {
        System.out.println("    [RSA] Iniciando cifrado...");
        System.out.println("    [RSA] Texto: " + texto.length() + " chars");
        System.out.println("    [RSA] Clave pública: " + (clave != null ? "OK" : "NULL"));

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, clave);

        byte[] cifrado = cipher.doFinal(texto.getBytes());
        System.out.println("    [RSA] Cifrado: " + cifrado.length + " bytes");

        String resultado = Base64.getEncoder().encodeToString(cifrado);
        System.out.println("    [RSA] Base64: " + resultado.length() + " chars");

        return resultado;
    }

    private static void enviar(String ip, int puerto, String mensaje) throws Exception {
        byte[] datos = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(
                datos, datos.length, InetAddress.getByName(ip), puerto);
        socketServidor.send(paquete);
    }
}