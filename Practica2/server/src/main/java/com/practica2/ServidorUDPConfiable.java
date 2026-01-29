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
    }

    private static KeyPair parClavesServidor;
    private static DatagramSocket socketServidor;
    private static Map<String, ClienteConexion> clientes = new HashMap<>();

    public static void main(String[] args) throws Exception {

        System.out.println("  SERVIDOR UDP - CIFRADO RSA TLS         ");

        System.out.println("[SERVIDOR] Generando claves RSA 2048 bits...");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        parClavesServidor = kpg.generateKeyPair();
        System.out.println("[SERVIDOR] ✓ Claves generadas\n");

        socketServidor = new DatagramSocket(Configuracion.PUERTO_SERVIDOR);
        System.out.println("[SERVIDOR] ✓ Escuchando en puerto " + Configuracion.PUERTO_SERVIDOR);
        System.out.println("[SERVIDOR] Esperando conexiones...\n");

        byte[] buffer = new byte[65507];

        while (true) {
            try {
                DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                socketServidor.receive(paquete);

                String ip = paquete.getAddress().getHostAddress();
                int puerto = paquete.getPort();
                String mensaje = new String(paquete.getData(), 0, paquete.getLength()).trim();

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

            System.out.println("[CONEXIÓN] Cliente: " + ip + ":" + puerto);
            System.out.println("[SOLICITUD] Archivo: " + archivo);

            File f = new File(Configuracion.DIRECTORIO_BASE, archivo);
            if (!f.exists()) {
                System.err.println("[ERROR] Archivo no encontrado\n");
                enviar(ip, puerto, "ERROR:Archivo no encontrado");
                return;
            }

            System.out.println("[OK] Encontrado: " + f.length() + " bytes");

            ClienteConexion cliente = new ClienteConexion();
            cliente.ip = ip;
            cliente.puerto = puerto;
            cliente.nombreArchivo = archivo;
            clientes.put(clave, cliente);

            String claveB64 = Base64.getEncoder().encodeToString(
                    parClavesServidor.getPublic().getEncoded());
            String respuesta = "SYN-ACK:" + (System.currentTimeMillis() % 10000) + ":" + claveB64;
            enviar(ip, puerto, respuesta);
            System.out.println("[HANDSHAKE] SYN-ACK enviado\n");

        } else if (mensaje.startsWith("ACK:")) {
            System.out.println("[HANDSHAKE] ACK recibido");

        } else if (mensaje.startsWith("PUBKEY:")) {
            ClienteConexion cliente = clientes.get(clave);
            if (cliente == null) {
                return;
            }

            try {
                String[] partes = mensaje.split(":", 2);
                String claveB64 = partes[1];

                byte[] claveDecodificada = Base64.getDecoder().decode(claveB64);
                java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(
                        claveDecodificada);
                cliente.clavePublicaCliente = java.security.KeyFactory.getInstance("RSA").generatePublic(spec);

                System.out.println("[HANDSHAKE] Clave pública recibida");
                System.out.println("[TRANSFERENCIA] Iniciando...\n");

                enviarArchivo(cliente);

            } catch (Exception e) {
                System.err.println("[ERROR] " + e.getMessage());
            }
        }
    }

    private static void enviarArchivo(ClienteConexion cliente) {
        try {
            File archivo = new File(Configuracion.DIRECTORIO_BASE, cliente.nombreArchivo);
            BufferedReader reader = new BufferedReader(new FileReader(archivo));

            // Contar líneas
            BufferedReader countReader = new BufferedReader(new FileReader(archivo));
            int totalLineas = 0;
            while (countReader.readLine() != null)
                totalLineas++;
            countReader.close();

            reader = new BufferedReader(new FileReader(archivo));
            String linea;
            int numLinea = 0;
            long seq = 1000;

            while ((linea = reader.readLine()) != null) {
                numLinea++;

                // Barra de progreso
                mostrarProgreso(numLinea, totalLineas);

                String lineaCifrada = cifrar(linea, cliente.clavePublicaCliente);
                String paqueteDATA = "DATA:" + seq + ":" + linea.length() + ":" + lineaCifrada;
                enviar(cliente.ip, cliente.puerto, paqueteDATA);

                seq++;
                Thread.sleep(100);
            }
            reader.close();

            System.out.println("\n[FINALIZACIÓN] 100% - " + totalLineas + " líneas");

            String paqueteFIN = "DATA:" + seq + ":0:FIN";
            enviar(cliente.ip, cliente.puerto, paqueteFIN);
            System.out.println("[TRANSFERENCIA] Completada\n");

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
        }
    }

    private static void mostrarProgreso(int actual, int total) {
        int porcentaje = (actual * 100) / total;
        int barraLlena = porcentaje / 5;
        int barraVacia = 20 - barraLlena;

        System.out.print("\r[");
        for (int i = 0; i < barraLlena; i++)
            System.out.print("█");
        for (int i = 0; i < barraVacia; i++)
            System.out.print("░");
        System.out.print("] " + porcentaje + "% (" + actual + "/" + total + ")");
    }

    private static String cifrar(String texto, PublicKey clave) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, clave);
        byte[] cifrado = cipher.doFinal(texto.getBytes());
        return Base64.getEncoder().encodeToString(cifrado);
    }

    private static void enviar(String ip, int puerto, String mensaje) throws Exception {
        byte[] datos = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(
                datos, datos.length, InetAddress.getByName(ip), puerto);
        socketServidor.send(paquete);
    }
}