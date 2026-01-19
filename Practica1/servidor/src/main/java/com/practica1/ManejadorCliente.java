package com.practica1;

import java.net.*;

public class ManejadorCliente implements Runnable {
    private DatagramPacket paqueteInicial;
    private DatagramSocket socketServidor;

    public ManejadorCliente(DatagramPacket paqueteInicial, DatagramSocket socket) {
        this.paqueteInicial = paqueteInicial;
        this.socketServidor = socket;
    }

    @Override
    public void run() {
        InetAddress ipCliente = paqueteInicial.getAddress();
        int puertoCliente = paqueteInicial.getPort();
        String claveSesion = ipCliente.getHostAddress() + ":" + puertoCliente;

        try {
            String mensaje = new String(paqueteInicial.getData(), 0, paqueteInicial.getLength());
            System.out.println("Cliente [" + claveSesion + "]: " + mensaje);
            ProcesadorMensajes.procesarMensaje(mensaje, ipCliente, puertoCliente, socketServidor);

        } catch (Exception e) {
            System.err.println("Error manejando cliente [" + claveSesion + "]: " + e.getMessage());
            e.printStackTrace();
            GestorSesiones.eliminarSesion(claveSesion);
        }
    }
}