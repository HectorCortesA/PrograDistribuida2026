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

    private static DatagramSocket socket;

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        System.out.println(" CLIENTE UDP - CIFRADO RSA TLS ");

        System.out.print("IP del servidor (default: localhost): ");
        String ipServidor = scanner.nextLine().trim();
        if (ipServidor.isEmpty())
            ipServidor = "localhost";

        System.out.print("Nombre del archivo: ");
        String nombreArchivo = scanner.nextLine().trim();
        if (nombreArchivo.isEmpty()) {
            System.out.println("✗ Nombre requerido");
            return;
        }

        scanner.close();

        // Crear socket UDP
        socket = new DatagramSocket();
        socket.setSoTimeout(15000);

        InetAddress ipServer = InetAddress.getByName(ipServidor);
        final int PUERTO = Configuracion.PUERTO_SERVIDOR;

        System.out.println("\n[CLIENTE] Puerto: " + socket.getLocalPort());
        System.out.println("[CLIENTE] Servidor: " + ipServidor + ":" + PUERTO + "\n");

        // 1. Generar claves
        System.out.println("[GENERAR CLAVES]");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair parClavesCliente = kpg.generateKeyPair();
        PrivateKey clavePrivadaCliente = parClavesCliente.getPrivate();
        PublicKey clavePublicaCliente = parClavesCliente.getPublic();
        System.out.println("[OK] Claves generadas\n");

        // 2. Handshake
        System.out.println("[HANDSHAKE]");

        // SYN
        String syn = "SYN:" + nombreArchivo + ":" + (System.currentTimeMillis() % 10000);
        enviar(ipServer, PUERTO, syn);
        System.out.println("[TX] SYN");

        // SYN-ACK
        System.out.println("[RX] Esperando SYN-ACK...");
        String synAck = recibir();
        if (synAck == null || !synAck.startsWith("SYN-ACK:")) {
            System.err.println("[ERROR] Respuesta inválida");
            return;
        }
        System.out.println("[RX] SYN-ACK recibido\n");

        // ACK
        enviar(ipServer, PUERTO, "ACK:1");
        System.out.println("[TX] ACK\n");

        // PUBKEY - ENVIAR CLAVE PÚBLICA DEL CLIENTE
        System.out.println("[ENVIAR CLAVE PÚBLICA]");
        String claveClienteB64 = Base64.getEncoder().encodeToString(clavePublicaCliente.getEncoded());
        System.out.println("  Base64 length: " + claveClienteB64.length());
        System.out.println("  Bytes: " + clavePublicaCliente.getEncoded().length);

        String mensajeClave = "PUBKEY:" + claveClienteB64;
        enviar(ipServer, PUERTO, mensajeClave);
        System.out.println("[TX] PUBKEY enviada\n");

        // 3. Recibir datos
        System.out.println("[RECIBIR DATOS]\n");

        String archivoCifrado = nombreArchivo.replace(".txt", "_CIFRADO.txt");
        String archivoDescifrado = nombreArchivo.replace(".txt", "_DESCIFRADO.txt");

        FileWriter writerCifrado = new FileWriter(archivoCifrado);
        FileWriter writerDescifrado = new FileWriter(archivoDescifrado);

        int lineasRecibidas = 0;
        boolean transferenciaPendiente = true;

        while (transferenciaPendiente) {
            try {
                String paquete = recibir();

                if (paquete != null && paquete.startsWith("DATA:")) {
                    String[] datos = paquete.split(":", 4);
                    int tamaño = Integer.parseInt(datos[2]);
                    String contenidoCifrado = datos[3];

                    if (tamaño > 0) {
                        System.out.println("[RX] Línea " + (lineasRecibidas + 1));
                        System.out.println("  Cifrada: " + contenidoCifrado.substring(0, 50) + "...");

                        // Guardar cifrada
                        writerCifrado.write(contenidoCifrado + "\n");
                        writerCifrado.flush();

                        try {
                            System.out.println("  Descifrando...");
                            String lineaDescifrada = descifrar(contenidoCifrado, clavePrivadaCliente);
                            System.out.println("  ✓ Descifrada: " + lineaDescifrada + "\n");

                            writerDescifrado.write(lineaDescifrada + "\n");
                            writerDescifrado.flush();

                            lineasRecibidas++;

                        } catch (Exception e) {
                            System.err.println("  ✗ Error: " + e.getMessage());
                            System.err.println("  Clase: " + e.getClass().getName());
                            writerDescifrado.write("[ERROR]\n");
                            writerDescifrado.flush();
                        }

                    } else if (tamaño == 0 && contenidoCifrado.equals("FIN")) {
                        System.out.println("[FIN] Transferencia completada\n");
                        transferenciaPendiente = false;
                    }
                }

            } catch (SocketTimeoutException e) {
                System.out.println("[TIMEOUT]\n");
                transferenciaPendiente = false;
            }
        }

        writerCifrado.close();
        writerDescifrado.close();
        socket.close();

        // Resumen
        System.out.println("TRANSFERENCIA COMPLETADA ");

        System.out.println("\nArchivo: " + nombreArchivo);
        System.out.println("Líneas: " + lineasRecibidas);
        System.out.println("\nGenerados:");
        System.out.println("  1. " + archivoCifrado);
        System.out.println("  2. " + archivoDescifrado + "\n");
    }

    private static String descifrar(String textoCifrado, PrivateKey clave) throws Exception {
        System.out.println("    [RSA] Descifrando...");
        System.out.println("    [RSA] Entrada: " + textoCifrado.length() + " chars");
        System.out.println("    [RSA] Clave privada: " + (clave != null ? "OK" : "NULL"));

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, clave);

        byte[] decodificado = Base64.getDecoder().decode(textoCifrado);
        System.out.println("    [RSA] Decodificado: " + decodificado.length + " bytes");

        byte[] descifrado = cipher.doFinal(decodificado);
        System.out.println("    [RSA] Descifrado: " + descifrado.length + " bytes");

        String resultado = new String(descifrado);
        System.out.println("    [RSA] Resultado: " + resultado);

        return resultado;
    }

    private static void enviar(InetAddress ip, int puerto, String mensaje) throws Exception {
        byte[] datos = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(datos, datos.length, ip, puerto);
        socket.send(paquete);
    }

    private static String recibir() throws Exception {
        byte[] buffer = new byte[65507];
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
        socket.receive(paquete);
        return new String(paquete.getData(), 0, paquete.getLength()).trim();
    }
}