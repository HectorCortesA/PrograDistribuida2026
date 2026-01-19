package com.practica1;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ServidorUDPConfiable {
    private static final int PUERTO_SERVIDOR = 5000;
    private static final int TIMEOUT_MS = 3000;

    private static ExecutorService threadPool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        System.out.println("=== Servidor UDP Confiable Iniciado ===");

        try (DatagramSocket socket = new DatagramSocket(PUERTO_SERVIDOR)) {
            System.out.println("Servidor escuchando en puerto: " + PUERTO_SERVIDOR);
            socket.setSoTimeout(TIMEOUT_MS);

            // Iniciar hilo para limpieza de sesiones
            Thread limpiezaThread = new Thread(new GestorSesiones.LimpiadorSesiones());
            limpiezaThread.setDaemon(true);
            limpiezaThread.start();

            while (true) {
                try {
                    byte[] buffer = new byte[Configuracion.TAMANO_BUFFER];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);
                    threadPool.execute(new ManejadorCliente(paquete, socket));

                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    System.err.println("Error en recepción: " + e.getMessage());
                    break;
                }
            }
        } catch (SocketException e) {
            System.err.println("Error al crear socket: " + e.getMessage());
        } finally {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(60, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
            }
            System.out.println("Servidor detenido.");
        }
    }
}