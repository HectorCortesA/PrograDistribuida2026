package com.practica1;

import java.io.*;
import java.net.*;

public class ProcesadorMensajes {

    public static void procesarMensaje(String mensaje, InetAddress ipCliente,
            int puertoCliente, DatagramSocket socket) throws IOException {

        String claveSesion = ipCliente.getHostAddress() + ":" + puertoCliente;

        if (mensaje.startsWith(Configuracion.FORMATO_SYN)) {
            manejarSYN(mensaje, ipCliente, puertoCliente, socket, claveSesion);
        } else if (mensaje.startsWith(Configuracion.FORMATO_DATA)) {
            manejarDatos(mensaje, ipCliente, puertoCliente, socket, claveSesion);
        } else if (mensaje.startsWith(Configuracion.FORMATO_FIN)) {
            manejarFIN(mensaje, ipCliente, puertoCliente, socket, claveSesion);
        } else if (mensaje.startsWith(Configuracion.FORMATO_ACK)) {
            manejarACK(mensaje, ipCliente, puertoCliente, socket, claveSesion);
        } else if (mensaje.startsWith(Configuracion.FORMATO_ERROR)) {
            System.err.println("Error del cliente [" + claveSesion + "]: " + mensaje.substring(6));
        }
    }

    private static void manejarSYN(String mensaje, InetAddress ipCliente, int puertoCliente,
            DatagramSocket socket, String claveSesion) throws IOException {
        String[] partes = mensaje.split(":");
        if (partes.length < 3) {
            enviarError(ipCliente, puertoCliente, socket, "Formato SYN inválido");
            return;
        }

        String nombreArchivo = partes[1];
        long numeroSecuencia = Long.parseLong(partes[2]);

        System.out.println("SYN recibido de [" + claveSesion + "] para archivo: " + nombreArchivo);

        GestorSesiones.SesionCliente sesion = new GestorSesiones.SesionCliente(
                ipCliente, puertoCliente, nombreArchivo);
        sesion.setSecuenciaEsperada(numeroSecuencia + 1);
        GestorSesiones.agregarSesion(claveSesion, sesion);

        boolean ackRecibido = enviarSYNACKConReintentos(ipCliente, puertoCliente, socket,
                nombreArchivo, numeroSecuencia, claveSesion, sesion);

        if (!ackRecibido) {
            System.err.println("No se recibió ACK de [" + claveSesion + "] después de " +
                    Configuracion.MAX_REINTENTOS + " intentos");
            GestorSesiones.eliminarSesion(claveSesion);
        }
    }

    private static boolean enviarSYNACKConReintentos(InetAddress ipCliente, int puertoCliente,
            DatagramSocket socket, String nombreArchivo,
            long numeroSecuencia, String claveSesion,
            GestorSesiones.SesionCliente sesion) throws IOException {

        boolean ackRecibido = false;
        for (int intento = 0; intento < Configuracion.MAX_REINTENTOS && !ackRecibido; intento++) {
            String synAck = Configuracion.construirMensaje(Configuracion.FORMATO_SYN_ACK,
                    nombreArchivo, (numeroSecuencia + 1));
            enviarPaquete(ipCliente, puertoCliente, socket, synAck);
            System.out.println("SYN-ACK enviado a [" + claveSesion + "] (intento " + (intento + 1) + ")");

            try {
                socket.setSoTimeout(Configuracion.TIMEOUT_MS);
                byte[] buffer = new byte[Configuracion.TAMANO_BUFFER];
                DatagramPacket paqueteRespuesta = new DatagramPacket(buffer, buffer.length);
                socket.receive(paqueteRespuesta);

                if (paqueteRespuesta.getAddress().equals(ipCliente) &&
                        paqueteRespuesta.getPort() == puertoCliente) {

                    String respuesta = new String(paqueteRespuesta.getData(), 0, paqueteRespuesta.getLength());
                    if (respuesta.startsWith(Configuracion.FORMATO_ACK)) {
                        System.out.println("ACK recibido de [" + claveSesion + "]");
                        ackRecibido = true;
                        sesion.setEstado(GestorSesiones.SesionCliente.Estado.ESTABLISHED);
                    }
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout esperando ACK de [" + claveSesion + "], reintentando...");
            }
        }
        return ackRecibido;
    }

    private static void manejarDatos(String mensaje, InetAddress ipCliente, int puertoCliente,
            DatagramSocket socket, String claveSesion) throws IOException {

        GestorSesiones.SesionCliente sesion = GestorSesiones.obtenerSesion(claveSesion);
        if (sesion == null) {
            enviarError(ipCliente, puertoCliente, socket, "Sesión no encontrada");
            return;
        }

        String[] partes = mensaje.split(":", 4);
        if (partes.length < 4) {
            enviarError(ipCliente, puertoCliente, socket, "Formato DATA inválido");
            return;
        }

        long numeroSecuencia = Long.parseLong(partes[1]);
        int longitud = Integer.parseInt(partes[2]);
        String datos = partes[3];

        if (numeroSecuencia == sesion.getSecuenciaEsperada()) {
            sesion.agregarDatos(datos);
            sesion.setSecuenciaEsperada(numeroSecuencia + 1);

            enviarACKConReintentos(ipCliente, puertoCliente, socket, numeroSecuencia, claveSesion);

            System.out.println("Datos recibidos de [" + claveSesion +
                    "], seq: " + numeroSecuencia + ", ACK enviado");

            if (longitud == 0) {
                System.out.println("Transferencia completa para [" + claveSesion + "]");
                ManejadorArchivos.guardarArchivo(sesion);
                iniciarCierreConexion(ipCliente, puertoCliente, socket, sesion, numeroSecuencia + 1);
            }
        } else {
            long ultimoAck = sesion.getSecuenciaEsperada() - 1;
            enviarACKConReintentos(ipCliente, puertoCliente, socket, ultimoAck, claveSesion);

            System.out.println("Paquete fuera de orden de [" + claveSesion +
                    "], esperado: " + sesion.getSecuenciaEsperada() +
                    ", recibido: " + numeroSecuencia);
        }
    }

    private static void manejarFIN(String mensaje, InetAddress ipCliente, int puertoCliente,
            DatagramSocket socket, String claveSesion) throws IOException {

        GestorSesiones.SesionCliente sesion = GestorSesiones.obtenerSesion(claveSesion);
        if (sesion == null) {
            return;
        }

        String[] partes = mensaje.split(":");
        if (partes.length < 2)
            return;

        long numeroSecuencia = Long.parseLong(partes[1]);

        switch (sesion.getEstado()) {
            case ESTABLISHED:
                String ack = Configuracion.construirMensaje(Configuracion.FORMATO_ACK, numeroSecuencia);
                enviarPaquete(ipCliente, puertoCliente, socket, ack);

                String fin = Configuracion.construirMensaje(Configuracion.FORMATO_FIN, (numeroSecuencia + 1));
                enviarPaquete(ipCliente, puertoCliente, socket, fin);
                sesion.setEstado(GestorSesiones.SesionCliente.Estado.FIN_WAIT_1);
                break;

            case FIN_WAIT_1:
                if (mensaje.startsWith(Configuracion.FORMATO_ACK)) {
                    sesion.setEstado(GestorSesiones.SesionCliente.Estado.FIN_WAIT_2);
                    System.out.println("ACK recibido para nuestro FIN de [" + claveSesion + "]");
                }
                break;

            case FIN_WAIT_2:
                if (mensaje.startsWith(Configuracion.FORMATO_ACK)) {
                    sesion.setEstado(GestorSesiones.SesionCliente.Estado.CLOSED);
                    GestorSesiones.eliminarSesion(claveSesion);
                    System.out.println("Conexión cerrada con [" + claveSesion + "]");
                }
                break;

            case SYN_RECEIVED:
                enviarError(ipCliente, puertoCliente, socket, "FIN recibido en estado incorrecto");
                break;

            case CLOSED:
                System.out.println("FIN recibido para conexión ya cerrada [" + claveSesion + "]");
                break;
        }
    }

    private static void manejarACK(String mensaje, InetAddress ipCliente, int puertoCliente,
            DatagramSocket socket, String claveSesion) throws IOException {

        GestorSesiones.SesionCliente sesion = GestorSesiones.obtenerSesion(claveSesion);
        if (sesion == null)
            return;

        if (sesion.getEstado() == GestorSesiones.SesionCliente.Estado.FIN_WAIT_1 ||
                sesion.getEstado() == GestorSesiones.SesionCliente.Estado.FIN_WAIT_2) {
            manejarFIN(mensaje, ipCliente, puertoCliente, socket, claveSesion);
        } else if (sesion.getEstado() == GestorSesiones.SesionCliente.Estado.ESTABLISHED) {
            System.out.println("ACK recibido para datos de [" + claveSesion + "]: " + mensaje);
        }
    }

    private static void iniciarCierreConexion(InetAddress ip, int puerto, DatagramSocket socket,
            GestorSesiones.SesionCliente sesion, long seq) throws IOException {

        String fin = Configuracion.construirMensaje(Configuracion.FORMATO_FIN, seq);
        boolean finAceptado = false;

        for (int intento = 0; intento < Configuracion.MAX_REINTENTOS && !finAceptado; intento++) {
            enviarPaquete(ip, puerto, socket, fin);
            System.out.println("FIN enviado a [" + ip.getHostAddress() + ":" +
                    puerto + "] (intento " + (intento + 1) + ")");

            try {
                socket.setSoTimeout(Configuracion.TIMEOUT_MS);
                byte[] buffer = new byte[Configuracion.TAMANO_BUFFER];
                DatagramPacket paqueteRespuesta = new DatagramPacket(buffer, buffer.length);
                socket.receive(paqueteRespuesta);

                if (paqueteRespuesta.getAddress().equals(ip) &&
                        paqueteRespuesta.getPort() == puerto) {

                    String respuesta = new String(paqueteRespuesta.getData(), 0, paqueteRespuesta.getLength());
                    if (respuesta.startsWith(Configuracion.FORMATO_ACK)) {
                        finAceptado = true;
                        sesion.setEstado(GestorSesiones.SesionCliente.Estado.FIN_WAIT_2);
                        System.out.println("ACK recibido para FIN de [" +
                                ip.getHostAddress() + ":" + puerto + "]");
                    }
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout esperando ACK para FIN, reintentando...");
            }
        }

        if (!finAceptado) {
            sesion.setEstado(GestorSesiones.SesionCliente.Estado.CLOSED);
            GestorSesiones.eliminarSesion(ip.getHostAddress() + ":" + puerto);
            System.err.println("No se recibió ACK para FIN después de " +
                    Configuracion.MAX_REINTENTOS + " intentos");
        } else {
            sesion.setEstado(GestorSesiones.SesionCliente.Estado.FIN_WAIT_1);
            System.out.println("Iniciado cierre de conexión con [" +
                    ip.getHostAddress() + ":" + puerto + "]");
        }
    }

    private static void enviarACKConReintentos(InetAddress ip, int puerto, DatagramSocket socket,
            long numeroSecuencia, String claveSesion) throws IOException {

        String ack = Configuracion.construirMensaje(Configuracion.FORMATO_ACK, numeroSecuencia);
        for (int intento = 0; intento < Configuracion.MAX_REINTENTOS; intento++) {
            try {
                enviarPaquete(ip, puerto, socket, ack);
                break;
            } catch (IOException e) {
                System.err.println("Error enviando ACK a [" + claveSesion +
                        "] (intento " + (intento + 1) + "): " + e.getMessage());
                if (intento == Configuracion.MAX_REINTENTOS - 1) {
                    throw e;
                }
            }
        }
    }

    private static void enviarPaquete(InetAddress ip, int puerto, DatagramSocket socket,
            String mensaje) throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static void enviarError(InetAddress ip, int puerto, DatagramSocket socket,
            String mensajeError) throws IOException {
        String error = Configuracion.construirMensaje(Configuracion.FORMATO_ERROR, mensajeError);
        enviarPaquete(ip, puerto, socket, error);
    }
}