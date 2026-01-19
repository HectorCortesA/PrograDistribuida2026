package com.hector;

import java.net.*;
import java.io.File;

public class Server {
    public static void main(String[] args) {
        final int PORT = 22000;
        File directorio = new File("/Users/hectorcortes/Downloads");
        int contadorMensajes = 0;
        int contadorLista = 0;

        try (DatagramSocket socket = new DatagramSocket(PORT)) {
            byte[] buffer = new byte[1024];

            System.out.println("Servidor UDP iniciado en puerto " + PORT);

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String received = new String(packet.getData(), 0, packet.getLength()).trim();
                System.out.println("Cliente [" + packet.getAddress() + ":" + packet.getPort() + "] dice: " + received);

                String response;

                if (received.equalsIgnoreCase("lista")) {
                    contadorLista++;
                    System.out.println("CONTADOR LISTA " + contadorLista + " veces solicitada");

                    StringBuilder fileList = new StringBuilder();
                    fileList.append("Archivos en ").append(directorio.getName()).append(":\n");

                    File[] files = directorio.listFiles();
                    if (files != null && files.length > 0) {
                        int contador = 0;
                        for (File file : files) {
                            if (file.isFile()) {
                                contador++;
                                fileList.append(contador).append(". ")
                                        .append(file.getName())
                                        .append(" (").append(file.length()).append(" bytes)\n");
                            }
                        }
                        if (contador == 0) {
                            fileList.append("No hay archivos en este directorio.\n");
                        }
                    } else {
                        fileList.append("El directorio está vacío o no se puede leer.\n");
                    }
                    response = fileList.toString();

                } else {

                    contadorMensajes++;
                    System.out.println("CONTADOR MENSAJEs " + contadorMensajes + " mensajes recibidos");

                    response = "Mensaje enviado:'" + received + "'\n";
                }

                byte[] responseData = response.getBytes();
                DatagramPacket responsePacket = new DatagramPacket(
                        responseData, responseData.length,
                        packet.getAddress(), packet.getPort());
                socket.send(responsePacket);

                System.out.println("Total mensajes recibidos: " + contadorMensajes);
                System.out.println("Total listas solicitadas: " + contadorLista);
                System.out.println("Total interacciones: " + (contadorMensajes + contadorLista));

                buffer = new byte[1024];
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}