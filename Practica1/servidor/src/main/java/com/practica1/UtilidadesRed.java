package com.practica1;

import java.net.*;

public class UtilidadesRed {

    public static String extraerClaveSesion(DatagramPacket paquete) {
        InetAddress ip = paquete.getAddress();
        int puerto = paquete.getPort();
        return ip.getHostAddress() + ":" + puerto;
    }

    public static String extraerMensaje(DatagramPacket paquete) {
        return new String(paquete.getData(), 0, paquete.getLength());
    }

    public static String obtenerIPLocal() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }
}