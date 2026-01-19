package com.practica1;

import java.io.*;
import java.net.*;

public class ManejadorCliente implements Runnable {
    private DatagramPacket paqueteInicial;
    private DatagramSocket socket;

    public ManejadorCliente(DatagramPacket paquete, DatagramSocket socket) {
        this.paqueteInicial = paquete;
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            InetAddress ipCliente = paqueteInicial.getAddress();
            int puertoCliente = paqueteInicial.getPort();
            String clave = ipCliente.getHostAddress() + ":" + puertoCliente;

            String mensaje = new String(paqueteInicial.getData(), 0, paqueteInicial.getLength());
            System.out.println("Cliente " + clave + ": " + mensaje);

            // THREE-WAY HANDSHAKE (Cliente inicia con SYN)
            if (mensaje.startsWith(Configuracion.TIPO_SYN + ":")) {
                manejarSYN(ipCliente, puertoCliente, mensaje, clave);
            }
            // Recepción de datos
            else if (mensaje.startsWith(Configuracion.TIPO_DATA + ":")) {
                manejarDATA(ipCliente, puertoCliente, mensaje, clave);
            }
            // FOUR-WAY HANDSHAKE (Cierre)
            else if (mensaje.startsWith(Configuracion.TIPO_FIN + ":")) {
                manejarFIN(ipCliente, puertoCliente, mensaje, clave);
            }
            // ACK del cliente
            else if (mensaje.startsWith(Configuracion.TIPO_ACK + ":")) {
                manejarACK(ipCliente, puertoCliente, mensaje, clave);
            }

        } catch (Exception e) {
            System.err.println("Error en cliente: " + e.getMessage());
        }
    }

    // THREE-WAY HANDSHAKE: Paso 2 (SYN-ACK)
    private void manejarSYN(InetAddress ip, int puerto, String mensaje, String clave) throws IOException {
        String[] partes = mensaje.split(":");
        if (partes.length < 3) {
            enviarError(ip, puerto, "SYN inválido");
            return;
        }

        String nombreArchivo = partes[1];
        long seq = Long.parseLong(partes[2]);

        System.out.println("SYN recibido para archivo: " + nombreArchivo);

        // Crear sesión
        Sesion sesion = new Sesion(ip, puerto, nombreArchivo);
        sesion.setSecuenciaEsperada(seq + 1);
        GestorSesiones.agregarSesion(clave, sesion);

        // Enviar SYN-ACK
        String synAck = Configuracion.TIPO_SYN_ACK + ":" + nombreArchivo + ":" + (seq + 1);
        enviar(ip, puerto, synAck);
    }

    // Recepción de datos del archivo
    private void manejarDATA(InetAddress ip, int puerto, String mensaje, String clave) throws IOException {
        Sesion sesion = GestorSesiones.obtenerSesion(clave);
        if (sesion == null) {
            enviarError(ip, puerto, "No hay sesión");
            return;
        }

        String[] partes = mensaje.split(":", 4);
        if (partes.length < 4) {
            enviarError(ip, puerto, "DATA inválido");
            return;
        }

        long seq = Long.parseLong(partes[1]);
        int tamano = Integer.parseInt(partes[2]);
        String datos = partes[3];

        // Verificar secuencia
        if (seq == sesion.getSecuenciaEsperada()) {
            // Guardar datos
            sesion.agregarDatos(datos);
            sesion.incrementarSecuencia();

            // Enviar ACK
            String ack = Configuracion.TIPO_ACK + ":" + seq;
            enviar(ip, puerto, ack);

            // Si es el último paquete
            if (tamano == 0) {
                ManejadorArchivos.guardarArchivo(sesion);

                // Iniciar FOUR-WAY HANDSHAKE (servidor inicia cierre)
                String fin = Configuracion.TIPO_FIN + ":" + (seq + 1);
                enviar(ip, puerto, fin);
                sesion.setEstado(Configuracion.ESTADO_FIN_ENVIADO);
            }
        } else {
            // Reenviar ACK anterior
            long ultimoAck = sesion.getSecuenciaEsperada() - 1;
            String ack = Configuracion.TIPO_ACK + ":" + ultimoAck;
            enviar(ip, puerto, ack);
        }
    }

    // FOUR-WAY HANDSHAKE
    private void manejarFIN(InetAddress ip, int puerto, String mensaje, String clave) throws IOException {
        Sesion sesion = GestorSesiones.obtenerSesion(clave);
        if (sesion == null)
            return;

        String[] partes = mensaje.split(":");
        if (partes.length < 2)
            return;

        long seq = Long.parseLong(partes[1]);

        if (Configuracion.ESTADO_ESTABLISHED.equals(sesion.getEstado())) {
            // Paso 2: ACK del FIN del cliente
            String ack = Configuracion.TIPO_ACK + ":" + seq;
            enviar(ip, puerto, ack);

            // Paso 3: Enviar nuestro FIN
            String fin = Configuracion.TIPO_FIN + ":" + (seq + 1);
            enviar(ip, puerto, fin);
            sesion.setEstado(Configuracion.ESTADO_FIN_ENVIADO);
        } else if (Configuracion.ESTADO_FIN_ENVIADO.equals(sesion.getEstado())) {
            // Paso 4: ACK del nuestro FIN
            if (mensaje.startsWith(Configuracion.TIPO_ACK + ":")) {
                GestorSesiones.eliminarSesion(clave);
                System.out.println("Conexión cerrada con " + clave);
            }
        }
    }

    private void manejarACK(InetAddress ip, int puerto, String mensaje, String clave) throws IOException {
        Sesion sesion = GestorSesiones.obtenerSesion(clave);
        if (sesion == null)
            return;

        // ACK del SYN-ACK (three-way handshake completo)
        if (Configuracion.ESTADO_SYN_RECIBIDO.equals(sesion.getEstado())) {
            sesion.setEstado(Configuracion.ESTADO_ESTABLISHED);
            System.out.println("Conexión establecida con " + clave);
        }
        // ACK del nuestro FIN
        else if (Configuracion.ESTADO_FIN_ENVIADO.equals(sesion.getEstado())) {
            manejarFIN(ip, puerto, mensaje, clave);
        }
    }

    private void enviar(InetAddress ip, int puerto, String mensaje) throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
        System.out.println("Enviado a " + ip.getHostAddress() + ":" + puerto + ": " + mensaje);
    }

    private void enviarError(InetAddress ip, int puerto, String error) throws IOException {
        enviar(ip, puerto, Configuracion.TIPO_ERROR + ":" + error);
    }
}