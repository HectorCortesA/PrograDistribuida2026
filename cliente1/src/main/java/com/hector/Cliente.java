package com.hector;

import java.net.*;
import java.util.Scanner;

public class Cliente {
    public static void main(String[] args) {
        final String SERVER_ADDRESS = "localhost";
        final int PORT = 22000;

        try (DatagramSocket socket = new DatagramSocket();
                Scanner scanner = new Scanner(System.in)) {

            InetAddress address = InetAddress.getByName(SERVER_ADDRESS);
            byte[] buffer;

            System.out.println("Cliente UDP iniciado");

            while (true) {
                System.out.print("Escribe un mensaje (o 'salir'): ");
                String message = scanner.nextLine();

                if (message.equalsIgnoreCase("salir")) {
                    break;
                }

                buffer = message.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        buffer, buffer.length, address, PORT);
                socket.send(packet);

                buffer = new byte[1024];
                packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String response = new String(packet.getData(), 0, packet.getLength());
                System.out.println("Servidor: " + response);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}