package com.practica1;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.nio.file.*;

public class ServidorUDPConfiable {
    private static final int PUERTO_SERVIDOR = 5000;
    private static final int TAMANO_BUFFER = 1024;
    private static final int TIMEOUT_MS = 3000;
    private static final int MAX_REINTENTOS = 5;

    // Mapa para almacenar sesiones activas (ip:puerto -> SesionCliente)
    private static ConcurrentHashMap<String, SesionCliente> sesionesActivas = new ConcurrentHashMap<>();

    // Thread pool para manejar clientes concurrentemente
    private static ExecutorService threadPool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        System.out.println("=== Servidor UDP Confiable Iniciado ===");

        try (DatagramSocket socket = new DatagramSocket(PUERTO_SERVIDOR)) {
            System.out.println("Servidor escuchando en puerto: " + PUERTO_SERVIDOR);
            socket.setSoTimeout(TIMEOUT_MS); // Usando la constante TIMEOUT_MS

            while (true) {
                try {
                    // Buffer para recibir datos
                    byte[] buffer = new byte[TAMANO_BUFFER];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);

                    // Esperar paquete
                    socket.receive(paquete);

                    // Procesar paquete en un hilo separado
                    threadPool.execute(new ManejadorCliente(paquete, socket));

                } catch (SocketTimeoutException e) {
                    // Timeout normal, continuar escuchando
                    System.out.println("Timeout - Revisando conexiones inactivas...");
                    limpiarSesionesInactivas();
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

    // Método para limpiar sesiones inactivas
    private static void limpiarSesionesInactivas() {
        long tiempoActual = System.currentTimeMillis();
        long tiempoLimite = TIMEOUT_MS * 10; // 30 segundos

        int sesionesEliminadas = 0;
        for (String clave : sesionesActivas.keySet()) {
            SesionCliente sesion = sesionesActivas.get(clave);
            long tiempoInactivo = tiempoActual - sesion.getTimestampUltimaActividad();

            if (tiempoInactivo > tiempoLimite) {
                sesionesActivas.remove(clave);
                sesionesEliminadas++;
                System.out.println("Sesión inactiva eliminada: " + clave);
            }
        }

        if (sesionesEliminadas > 0) {
            System.out.println("Sesiones eliminadas: " + sesionesEliminadas +
                    ", Sesiones activas: " + sesionesActivas.size());
        }
    }

    // Clase para manejar la comunicación con cada cliente
    static class ManejadorCliente implements Runnable {
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
                // Leer el mensaje inicial
                String mensaje = new String(paqueteInicial.getData(), 0, paqueteInicial.getLength());
                System.out.println("Cliente [" + claveSesion + "]: " + mensaje);

                // Verificar si es un SYN (three-way handshake)
                if (mensaje.startsWith("SYN:")) {
                    manejarSYN(ipCliente, puertoCliente, mensaje, claveSesion);
                }
                // Verificar si es un paquete de datos
                else if (mensaje.startsWith("DATA:")) {
                    manejarDatos(ipCliente, puertoCliente, mensaje, claveSesion);
                }
                // Verificar si es un FIN (four-way handshake)
                else if (mensaje.startsWith("FIN:")) {
                    manejarFIN(ipCliente, puertoCliente, mensaje, claveSesion);
                }
                // Verificar si es un ACK
                else if (mensaje.startsWith("ACK:")) {
                    manejarACK(ipCliente, puertoCliente, mensaje, claveSesion);
                }
                // Manejar errores
                else if (mensaje.startsWith("ERROR:")) {
                    System.err.println("Error del cliente [" + claveSesion + "]: " + mensaje.substring(6));
                }

            } catch (Exception e) {
                System.err.println("Error manejando cliente [" + claveSesion + "]: " + e.getMessage());
                e.printStackTrace();
                sesionesActivas.remove(claveSesion);
            }
        }

        private void manejarSYN(InetAddress ipCliente, int puertoCliente,
                String mensaje, String claveSesion) throws IOException {
            // Extraer nombre de archivo del mensaje SYN
            String[] partes = mensaje.split(":");
            if (partes.length < 3) {
                enviarError(ipCliente, puertoCliente, "Formato SYN inválido");
                return;
            }

            String nombreArchivo = partes[1];
            long numeroSecuencia = Long.parseLong(partes[2]);

            System.out.println("SYN recibido de [" + claveSesion + "] para archivo: " + nombreArchivo);

            // Crear nueva sesión
            SesionCliente sesion = new SesionCliente(ipCliente, puertoCliente, nombreArchivo);
            sesion.setSecuenciaEsperada(numeroSecuencia + 1);
            sesionesActivas.put(claveSesion, sesion);

            // Enviar SYN-ACK con reintentos si es necesario
            boolean ackRecibido = false;
            for (int intento = 0; intento < MAX_REINTENTOS && !ackRecibido; intento++) {
                String synAck = "SYN-ACK:" + nombreArchivo + ":" + (numeroSecuencia + 1);
                enviarPaquete(ipCliente, puertoCliente, synAck);
                System.out.println("SYN-ACK enviado a [" + claveSesion + "] (intento " + (intento + 1) + ")");

                // Esperar ACK del cliente
                try {
                    socketServidor.setSoTimeout(TIMEOUT_MS);
                    byte[] buffer = new byte[TAMANO_BUFFER];
                    DatagramPacket paqueteRespuesta = new DatagramPacket(buffer, buffer.length);
                    socketServidor.receive(paqueteRespuesta);

                    // Verificar que el ACK venga del mismo cliente
                    if (paqueteRespuesta.getAddress().equals(ipCliente) &&
                            paqueteRespuesta.getPort() == puertoCliente) {

                        String respuesta = new String(paqueteRespuesta.getData(),
                                0, paqueteRespuesta.getLength());
                        if (respuesta.startsWith("ACK:")) {
                            System.out.println("ACK recibido de [" + claveSesion + "]");
                            ackRecibido = true;
                            sesion.setEstado(SesionCliente.Estado.ESTABLISHED);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout esperando ACK de [" + claveSesion + "], reintentando...");
                }
            }

            if (!ackRecibido) {
                System.err.println("No se recibió ACK de [" + claveSesion + "] después de " +
                        MAX_REINTENTOS + " intentos");
                sesionesActivas.remove(claveSesion);
            }
        }

        private void manejarDatos(InetAddress ipCliente, int puertoCliente,
                String mensaje, String claveSesion) throws IOException {
            SesionCliente sesion = sesionesActivas.get(claveSesion);
            if (sesion == null) {
                enviarError(ipCliente, puertoCliente, "Sesión no encontrada");
                return;
            }

            // Extraer datos del paquete
            String[] partes = mensaje.split(":", 4);
            if (partes.length < 4) {
                enviarError(ipCliente, puertoCliente, "Formato DATA inválido");
                return;
            }

            long numeroSecuencia = Long.parseLong(partes[1]);
            int longitud = Integer.parseInt(partes[2]);
            String datos = partes[3];

            // Verificar número de secuencia esperado
            if (numeroSecuencia == sesion.getSecuenciaEsperada()) {
                // Almacenar datos en sesión
                sesion.agregarDatos(datos);
                sesion.setSecuenciaEsperada(numeroSecuencia + 1);

                // Enviar ACK con reintentos
                enviarACKConReintentos(ipCliente, puertoCliente, numeroSecuencia, claveSesion);

                System.out.println("Datos recibidos de [" + claveSesion +
                        "], seq: " + numeroSecuencia + ", ACK enviado");

                // Verificar si es el último paquete (longitud 0)
                if (longitud == 0) {
                    System.out.println("Transferencia completa para [" + claveSesion + "]");

                    // Guardar archivo localmente
                    guardarArchivo(sesion);

                    // Iniciar cierre con FIN
                    iniciarCierreConexion(ipCliente, puertoCliente, sesion, numeroSecuencia + 1);
                }
            } else {
                // Reenviar ACK para el último paquete recibido correctamente
                long ultimoAck = sesion.getSecuenciaEsperada() - 1;
                enviarACKConReintentos(ipCliente, puertoCliente, ultimoAck, claveSesion);

                System.out.println("Paquete fuera de orden de [" + claveSesion +
                        "], esperado: " + sesion.getSecuenciaEsperada() +
                        ", recibido: " + numeroSecuencia);
            }
        }

        private void manejarFIN(InetAddress ipCliente, int puertoCliente,
                String mensaje, String claveSesion) throws IOException {
            SesionCliente sesion = sesionesActivas.get(claveSesion);
            if (sesion == null) {
                return;
            }

            String[] partes = mensaje.split(":");
            if (partes.length < 2)
                return;

            long numeroSecuencia = Long.parseLong(partes[1]);

            // Four-way handshake
            switch (sesion.getEstado()) {
                case ESTABLISHED:
                    // Primer FIN recibido
                    String ack = "ACK:" + numeroSecuencia;
                    enviarPaquete(ipCliente, puertoCliente, ack);

                    // Enviar nuestro FIN
                    String fin = "FIN:" + (numeroSecuencia + 1);
                    enviarPaquete(ipCliente, puertoCliente, fin);
                    sesion.setEstado(SesionCliente.Estado.FIN_WAIT_1);
                    break;

                case FIN_WAIT_1:
                    // ACK del FIN que enviamos
                    if (mensaje.startsWith("ACK:")) {
                        sesion.setEstado(SesionCliente.Estado.FIN_WAIT_2);
                        System.out.println("ACK recibido para nuestro FIN de [" + claveSesion + "]");
                    }
                    break;

                case FIN_WAIT_2:
                    // Último ACK
                    if (mensaje.startsWith("ACK:")) {
                        sesion.setEstado(SesionCliente.Estado.CLOSED);
                        sesionesActivas.remove(claveSesion);
                        System.out.println("Conexión cerrada con [" + claveSesion + "]");
                    }
                    break;

                case SYN_RECEIVED:
                    // No debería recibir FIN en este estado
                    enviarError(ipCliente, puertoCliente, "FIN recibido en estado incorrecto");
                    break;

                case CLOSED:
                    // Conexión ya cerrada
                    System.out.println("FIN recibido para conexión ya cerrada [" + claveSesion + "]");
                    break;
            }
        }

        private void manejarACK(InetAddress ipCliente, int puertoCliente,
                String mensaje, String claveSesion) throws IOException {
            SesionCliente sesion = sesionesActivas.get(claveSesion);
            if (sesion == null)
                return;

            // Manejar ACK para four-way handshake
            if (sesion.getEstado() == SesionCliente.Estado.FIN_WAIT_1 ||
                    sesion.getEstado() == SesionCliente.Estado.FIN_WAIT_2) {
                manejarFIN(ipCliente, puertoCliente, mensaje, claveSesion);
            } else if (sesion.getEstado() == SesionCliente.Estado.ESTABLISHED) {
                // ACK de datos normales, ya se maneja en manejarDatos
                System.out.println("ACK recibido para datos de [" + claveSesion + "]: " + mensaje);
            }
        }

        private void iniciarCierreConexion(InetAddress ip, int puerto, SesionCliente sesion, long seq)
                throws IOException {
            // Enviar FIN con reintentos
            String fin = "FIN:" + seq;
            boolean finAceptado = false;

            for (int intento = 0; intento < MAX_REINTENTOS && !finAceptado; intento++) {
                enviarPaquete(ip, puerto, fin);
                System.out.println("FIN enviado a [" + ip.getHostAddress() + ":" +
                        puerto + "] (intento " + (intento + 1) + ")");

                // Esperar ACK
                try {
                    socketServidor.setSoTimeout(TIMEOUT_MS);
                    byte[] buffer = new byte[TAMANO_BUFFER];
                    DatagramPacket paqueteRespuesta = new DatagramPacket(buffer, buffer.length);
                    socketServidor.receive(paqueteRespuesta);

                    if (paqueteRespuesta.getAddress().equals(ip) &&
                            paqueteRespuesta.getPort() == puerto) {

                        String respuesta = new String(paqueteRespuesta.getData(),
                                0, paqueteRespuesta.getLength());
                        if (respuesta.startsWith("ACK:")) {
                            finAceptado = true;
                            sesion.setEstado(SesionCliente.Estado.FIN_WAIT_2);
                            System.out.println("ACK recibido para FIN de [" +
                                    ip.getHostAddress() + ":" + puerto + "]");
                        }
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout esperando ACK para FIN, reintentando...");
                }
            }

            if (!finAceptado) {
                sesion.setEstado(SesionCliente.Estado.CLOSED);
                sesionesActivas.remove(ip.getHostAddress() + ":" + puerto);
                System.err.println("No se recibió ACK para FIN después de " +
                        MAX_REINTENTOS + " intentos");
            } else {
                sesion.setEstado(SesionCliente.Estado.FIN_WAIT_1);
                System.out.println("Iniciado cierre de conexión con [" +
                        ip.getHostAddress() + ":" + puerto + "]");
            }
        }

        private void enviarACKConReintentos(InetAddress ip, int puerto, long numeroSecuencia, String claveSesion)
                throws IOException {
            String ack = "ACK:" + numeroSecuencia;
            for (int intento = 0; intento < MAX_REINTENTOS; intento++) {
                try {
                    enviarPaquete(ip, puerto, ack);
                    // No esperamos respuesta para ACKs normales
                    break;
                } catch (IOException e) {
                    System.err.println("Error enviando ACK a [" + claveSesion +
                            "] (intento " + (intento + 1) + "): " + e.getMessage());
                    if (intento == MAX_REINTENTOS - 1) {
                        throw e;
                    }
                }
            }
        }

        private void guardarArchivo(SesionCliente sesion) {
            try {
                // Crear directorio para archivos recibidos
                Path directorio = Paths.get("archivos_recibidos");
                if (!Files.exists(directorio)) {
                    Files.createDirectories(directorio);
                }

                // Guardar archivo
                String nombreArchivo = "cliente_" +
                        sesion.getIpCliente().getHostAddress().replace('.', '_') + "_" +
                        sesion.getNombreArchivo();

                Path rutaArchivo = directorio.resolve(nombreArchivo);
                Files.write(rutaArchivo, sesion.getDatos().getBytes());

                System.out.println("Archivo guardado: " + rutaArchivo.toString());

            } catch (IOException e) {
                System.err.println("Error al guardar archivo: " + e.getMessage());
            }
        }

        private void enviarPaquete(InetAddress ip, int puerto, String mensaje) throws IOException {
            byte[] buffer = mensaje.getBytes();
            DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
            socketServidor.send(paquete);
        }

        private void enviarError(InetAddress ip, int puerto, String mensajeError) throws IOException {
            String error = "ERROR:" + mensajeError;
            enviarPaquete(ip, puerto, error);
        }
    }

    // Clase para mantener el estado de cada sesión de cliente
    static class SesionCliente {
        enum Estado {
            SYN_RECEIVED,
            ESTABLISHED,
            FIN_WAIT_1,
            FIN_WAIT_2,
            CLOSED
        }

        private InetAddress ipCliente;
        private int puertoCliente;
        private String nombreArchivo;
        private StringBuilder datos;
        private long secuenciaEsperada;
        private Estado estado;
        private long timestampUltimaActividad;

        public SesionCliente(InetAddress ipCliente, int puertoCliente, String nombreArchivo) {
            this.ipCliente = ipCliente;
            this.puertoCliente = puertoCliente;
            this.nombreArchivo = nombreArchivo;
            this.datos = new StringBuilder();
            this.secuenciaEsperada = 1;
            this.estado = Estado.SYN_RECEIVED;
            this.timestampUltimaActividad = System.currentTimeMillis();
        }

        // Getters y Setters
        public InetAddress getIpCliente() {
            return ipCliente;
        }

        public int getPuertoCliente() {
            return puertoCliente;
        }

        public String getNombreArchivo() {
            return nombreArchivo;
        }

        public String getDatos() {
            return datos.toString();
        }

        public long getSecuenciaEsperada() {
            return secuenciaEsperada;
        }

        public Estado getEstado() {
            return estado;
        }

        public long getTimestampUltimaActividad() {
            return timestampUltimaActividad;
        }

        public void setSecuenciaEsperada(long secuencia) {
            this.secuenciaEsperada = secuencia;
            actualizarTimestamp();
        }

        public void setEstado(Estado estado) {
            this.estado = estado;
            actualizarTimestamp();
        }

        public void agregarDatos(String nuevosDatos) {
            datos.append(nuevosDatos);
            actualizarTimestamp();
            if (estado == Estado.SYN_RECEIVED) {
                estado = Estado.ESTABLISHED;
            }
        }

        private void actualizarTimestamp() {
            this.timestampUltimaActividad = System.currentTimeMillis();
        }
    }
}