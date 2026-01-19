package com.practica1;

import java.io.*;
import java.net.*;

public class ServidorUDPConfiable {
    private static final int PUERTO = 5000;

    public static void main(String[] args) {
        System.out.println("=== Servidor UDP Confiable ===");

        try (DatagramSocket socket = new DatagramSocket(PUERTO)) {
            System.out.println("Escuchando en puerto: " + PUERTO);

            while (true) {
                try {
                    byte[] buffer = new byte[Configuracion.TAMANO_BUFFER];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);

                    // Manejar cada cliente en hilo separado para concurrencia
                    new Thread(new ManejadorCliente(paquete, socket)).start();

                } catch (IOException e) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            System.err.println("Error socket: " + e.getMessage());
        }
    }
}