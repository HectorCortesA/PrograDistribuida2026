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
            System.out.println("\nCliente " + clave + ": " + mensaje);

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
            // SYN-ACK (respuesta del servidor)
            else if (mensaje.startsWith(Configuracion.TIPO_SYN_ACK + ":")) {
                // Esto solo debería ocurrir si el servidor envía SYN-ACK a otro servidor
                System.out.println("SYN-ACK recibido de " + clave);
            }

        } catch (Exception e) {
            System.err.println("Error en cliente " + paqueteInicial.getAddress() + ":" +
                    paqueteInicial.getPort() + ": " + e.getMessage());
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

        System.out.println("SYN recibido para archivo: " + nombreArchivo + " (seq=" + seq + ")");

        // Crear sesión
        Sesion sesion = new Sesion(ip, puerto, nombreArchivo);
        sesion.setSecuenciaCliente(seq + 1); // ACK esperado del cliente
        sesion.setSecuenciaEsperada(seq + 2); // Primer DATA esperado
        GestorSesiones.agregarSesion(clave, sesion);

        // Enviar SYN-ACK
        String synAck = Configuracion.TIPO_SYN_ACK + ":" + nombreArchivo + ":" + (seq + 1);
        enviar(ip, puerto, synAck);
        System.out.println("SYN-ACK enviado a " + clave);
    }

    // Recepción de datos del archivo
    private void manejarDATA(InetAddress ip, int puerto, String mensaje, String clave) throws IOException {
        Sesion sesion = GestorSesiones.obtenerSesion(clave);
        if (sesion == null) {
            enviarError(ip, puerto, "No hay sesión activa");
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

        System.out.println("DATA recibido: seq=" + seq + ", tamaño=" + tamano + ", datos='" + datos + "'");

        // Verificar secuencia
        if (seq == sesion.getSecuenciaEsperada()) {
            // Si es paquete final (tamaño 0)
            if (tamano == 0 || datos.equals("FIN")) {
                System.out.println("Paquete FIN recibido del cliente");

                // Guardar archivo antes de reenviar
                sesion.limpiarDatos();
                ManejadorArchivos.guardarArchivo(sesion);

                // Reenviar archivo al cliente
                System.out.println("Iniciando reenvío del archivo al cliente...");
                reenviarArchivoAlCliente(ip, puerto, sesion);

                // No incrementar secuencia para paquete FIN
            } else {
                // Guardar datos válidos
                sesion.agregarDatos(datos);
                sesion.incrementarSecuencia();
            }

            // Enviar ACK
            String ack = Configuracion.TIPO_ACK + ":" + seq;
            enviar(ip, puerto, ack);

        } else {
            // Secuencia incorrecta, reenviar ACK del último paquete válido
            long ultimoSeq = sesion.getSecuenciaEsperada() - 1;
            String ack = Configuracion.TIPO_ACK + ":" + ultimoSeq;
            enviar(ip, puerto, ack);
            System.out.println("Secuencia incorrecta, reenviando ACK: " + ack);
        }
    }

    // Reenviar archivo al cliente
    private void reenviarArchivoAlCliente(InetAddress ip, int puerto, Sesion sesion) throws IOException {
        String contenido = sesion.getDatos();
        String[] lineas = contenido.split("\n");

        // Usar secuencia específica para reenvío (mayor que las secuencias normales)
        long seqReenvio = sesion.getSecuenciaEsperada() + 1000;

        System.out.println("Iniciando reenvío de " + lineas.length + " líneas al cliente " +
                ip.getHostAddress() + ":" + puerto);

        for (int i = 0; i < lineas.length; i++) {
            String linea = lineas[i].trim();

            if (linea.isEmpty()) {
                continue;
            }

            // Crear paquete DATA para reenvío
            String dataPacket = "DATA:" + seqReenvio + ":" + linea.length() + ":" + linea;

            boolean ackRecibido = false;
            int intentos = 0;

            while (!ackRecibido && intentos < Configuracion.MAX_REINTENTOS) {
                intentos++;
                try {
                    System.out.println("  Reenviando línea " + (i + 1) + ": '" + linea + "' (seq=" + seqReenvio + ")");
                    enviar(ip, puerto, dataPacket);

                    // Esperar ACK con timeout
                    byte[] buffer = new byte[Configuracion.TAMANO_BUFFER];
                    DatagramPacket ackPacket = new DatagramPacket(buffer, buffer.length);
                    socket.setSoTimeout(Configuracion.TIMEOUT_ACK);

                    try {
                        socket.receive(ackPacket);
                        String ackMsg = new String(ackPacket.getData(), 0, ackPacket.getLength());

                        if (ackMsg.equals("ACK:" + seqReenvio)) {
                            ackRecibido = true;
                            System.out.println("    ACK recibido para seq=" + seqReenvio);
                        } else if (ackMsg.startsWith("ERROR:")) {
                            System.out.println("    ERROR recibido: " + ackMsg);
                            break;
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("    Timeout esperando ACK, reintento " + intentos + "/"
                                + Configuracion.MAX_REINTENTOS);
                        if (intentos >= Configuracion.MAX_REINTENTOS) {
                            System.out.println("    ACK no recibido después de " + Configuracion.MAX_REINTENTOS
                                    + " intentos, continuando...");
                            break;
                        }
                    }

                } catch (Exception e) {
                    System.err.println("Error reenviando línea " + (i + 1) + ": " + e.getMessage());
                } finally {
                    socket.setSoTimeout(0);
                }
            }

            if (ackRecibido) {
                seqReenvio++;
            }
        }

        // Enviar paquete final con tamaño 0
        String finReenvio = "DATA:" + seqReenvio + ":0:FIN";
        enviar(ip, puerto, finReenvio);
        System.out.println("Reenvío completado. Paquete final enviado (seq=" + seqReenvio + ")");

        // Cambiar estado de la sesión
        sesion.setEstado(Configuracion.ESTADO_ESTABLISHED);
    }

    // FOUR-WAY HANDSHAKE
    private void manejarFIN(InetAddress ip, int puerto, String mensaje, String clave) throws IOException {
        Sesion sesion = GestorSesiones.obtenerSesion(clave);
        if (sesion == null) {
            System.out.println("Sesión no encontrada para " + clave);
            return;
        }

        String[] partes = mensaje.split(":");
        if (partes.length < 2) {
            enviarError(ip, puerto, "FIN inválido");
            return;
        }

        long seq = Long.parseLong(partes[1]);
        System.out.println("FIN recibido de cliente " + clave + " (seq=" + seq + ")");

        if (Configuracion.ESTADO_ESTABLISHED.equals(sesion.getEstado())) {
            // Paso 2: ACK del FIN del cliente
            String ack = Configuracion.TIPO_ACK + ":" + seq;
            enviar(ip, puerto, ack);
            System.out.println("ACK enviado para FIN del cliente: " + ack);

            // Paso 3: Enviar nuestro FIN
            String fin = Configuracion.TIPO_FIN + ":" + (seq + 1);
            enviar(ip, puerto, fin);
            sesion.setEstado(Configuracion.ESTADO_FIN_ENVIADO);
            System.out.println("FIN enviado al cliente: " + fin);

        } else if (Configuracion.ESTADO_FIN_ENVIADO.equals(sesion.getEstado())) {
            // El cliente ya envió su FIN, esto es inesperado
            System.out.println("FIN adicional recibido en estado FIN_ENVIADO");
        }
    }

    private void manejarACK(InetAddress ip, int puerto, String mensaje, String clave) throws IOException {
        Sesion sesion = GestorSesiones.obtenerSesion(clave);
        if (sesion == null) {
            System.out.println("Sesión no encontrada para ACK de " + clave);
            return;
        }

        System.out.println("ACK recibido de " + clave + ": " + mensaje);

        // ACK del SYN-ACK (three-way handshake completo)
        if (Configuracion.ESTADO_SYN_RECIBIDO.equals(sesion.getEstado())) {
            sesion.setEstado(Configuracion.ESTADO_ESTABLISHED);
            System.out.println("✓ Conexión establecida con " + clave);
        }
        // ACK de nuestro FIN (paso 4 del four-way handshake)
        else if (Configuracion.ESTADO_FIN_ENVIADO.equals(sesion.getEstado())) {
            if (mensaje.startsWith(Configuracion.TIPO_ACK + ":")) {
                GestorSesiones.eliminarSesion(clave);
                System.out.println("✓ Conexión cerrada correctamente con " + clave);
            }
        }
        // ACK normal de datos
        else {
            System.out.println("ACK normal recibido: " + mensaje);
        }
    }

    private void enviar(InetAddress ip, int puerto, String mensaje) throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
        System.out.println("Enviado a " + ip.getHostAddress() + ":" + puerto + ": " + mensaje);
    }

    private void enviarError(InetAddress ip, int puerto, String error) throws IOException {
        String errorMsg = Configuracion.TIPO_ERROR + ":" + error;
        enviar(ip, puerto, errorMsg);
        System.out.println("Error enviado a " + ip.getHostAddress() + ":" + puerto + ": " + error);
    }
}