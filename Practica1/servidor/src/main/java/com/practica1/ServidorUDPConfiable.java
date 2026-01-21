package com.practica1;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class ServidorUDPConfiable {
    private static final int PUERTO = 22000;
    private static final ConcurrentHashMap<String, SesionCliente> sesiones = new ConcurrentHashMap<>();
    private static final String DIRECTORIO_ARCHIVOS = "archivos_recibidos";

    public static void main(String[] args) {
        System.out.println("=== SERVIDOR UDP CONFIABLE ===");
        System.out.println("Puerto: " + PUERTO);
        System.out.println("Escuchando en todas las interfaces");
        System.out.println("Directorio archivos: " + DIRECTORIO_ARCHIVOS);
        System.out.println("==============================\n");

        try (DatagramSocket socket = new DatagramSocket(PUERTO, InetAddress.getByName("0.0.0.0"))) {

            System.out.println("✓ Servidor iniciado correctamente");
            System.out.println("  IP: " + socket.getLocalAddress().getHostAddress());
            System.out.println("  Puerto: " + socket.getLocalPort());
            System.out.println("\nEsperando conexiones de clientes...\n");

            // Crear directorio para archivos
            new File(DIRECTORIO_ARCHIVOS).mkdirs();

            while (true) {
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);

                    // Crear hilo para manejar cliente
                    new Thread(new ManejadorCliente(paquete, socket)).start();

                } catch (IOException e) {
                    System.err.println("Error recibiendo paquete: " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            System.err.println("Error creando socket: " + e.getMessage());
            System.err.println("Posibles causas:");
            System.err.println("  1. Puerto " + PUERTO + " ya está en uso");
            System.err.println("  2. No hay permisos (en Linux: sudo)");
            System.err.println("  3. Firewall bloqueando el puerto");
        } catch (Exception e) {
            System.err.println("Error inesperado: " + e.getMessage());
        }
    }

    // ===== CLASE PARA MANEJAR CLIENTES =====

    static class ManejadorCliente implements Runnable {
        private DatagramPacket paqueteInicial;
        private DatagramSocket socket;

        public ManejadorCliente(DatagramPacket paquete, DatagramSocket socket) {
            this.paqueteInicial = paquete;
            this.socket = socket;
        }

        @Override
        public void run() {
            InetAddress ipCliente = paqueteInicial.getAddress();
            int puertoCliente = paqueteInicial.getPort();
            String claveCliente = ipCliente.getHostAddress() + ":" + puertoCliente;

            System.out.println("\n[+] Nuevo cliente: " + claveCliente);

            try {
                String mensaje = new String(paqueteInicial.getData(), 0, paqueteInicial.getLength());

                if (mensaje.startsWith("SYN:")) {
                    manejarSYN(ipCliente, puertoCliente, mensaje, claveCliente);
                } else if (mensaje.startsWith("DATA:")) {
                    manejarDATA(ipCliente, puertoCliente, mensaje, claveCliente);
                } else if (mensaje.startsWith("FIN:")) {
                    manejarFIN(ipCliente, puertoCliente, mensaje, claveCliente);
                } else if (mensaje.startsWith("ACK:")) {
                    manejarACK(ipCliente, puertoCliente, mensaje, claveCliente);
                }
            } catch (Exception e) {
                System.err.println("Error con cliente " + claveCliente + ": " + e.getMessage());
                sesiones.remove(claveCliente);
            }
        }

        private void manejarSYN(InetAddress ip, int puerto, String mensaje, String clave)
                throws IOException {
            String[] partes = mensaje.split(":");
            if (partes.length < 3) {
                enviarError(ip, puerto, "SYN inválido");
                return;
            }

            String nombreArchivo = partes[1];
            long seq = Long.parseLong(partes[2]);

            System.out.println("  SYN recibido para archivo: " + nombreArchivo);
            System.out.println("  Secuencia inicial: " + seq);

            // Crear nueva sesión
            SesionCliente sesion = new SesionCliente(ip, puerto, nombreArchivo);
            sesion.setSecuenciaEsperada(seq + 2);
            sesiones.put(clave, sesion);

            // Enviar SYN-ACK
            String synAck = "SYN-ACK:" + nombreArchivo + ":" + (seq + 1);
            enviar(ip, puerto, synAck);
            System.out.println("  SYN-ACK enviado a " + clave);
        }

        private void manejarDATA(InetAddress ip, int puerto, String mensaje, String clave)
                throws IOException {
            SesionCliente sesion = sesiones.get(clave);
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

            // Verificar secuencia
            if (seq == sesion.getSecuenciaEsperada()) {
                if (tamano == 0) { // FIN-DATA
                    System.out.println("  FIN-DATA recibido de " + clave);
                    System.out.println("  Guardando archivo y preparando respuesta...");

                    // Guardar archivo recibido
                    guardarArchivo(sesion);

                    // Reenviar archivo al cliente
                    reenviarArchivo(ip, puerto, sesion);

                } else {
                    // Agregar datos a la sesión
                    sesion.agregarDatos(datos + "\n");
                    sesion.incrementarSecuencia();
                    System.out.println("  DATA recibido: " + datos.substring(0, Math.min(30, datos.length())) +
                            "... (seq=" + seq + ")");
                }

                // Enviar ACK
                String ack = "ACK:" + seq;
                enviar(ip, puerto, ack);

            } else {
                // Secuencia incorrecta, reenviar ACK anterior
                System.out.println("  Secuencia incorrecta, esperaba: " + sesion.getSecuenciaEsperada());
                long ultimoAck = sesion.getSecuenciaEsperada() - 1;
                String ack = "ACK:" + ultimoAck;
                enviar(ip, puerto, ack);
            }
        }

        private void manejarFIN(InetAddress ip, int puerto, String mensaje, String clave)
                throws IOException {
            SesionCliente sesion = sesiones.get(clave);
            if (sesion == null)
                return;

            String[] partes = mensaje.split(":");
            if (partes.length < 2)
                return;

            long seq = Long.parseLong(partes[1]);

            if ("ESTABLISHED".equals(sesion.getEstado())) {
                // Paso 2: ACK del FIN del cliente
                String ack = "ACK:" + seq;
                enviar(ip, puerto, ack);

                // Paso 3: Enviar nuestro FIN
                String fin = "FIN:" + (seq + 1);
                enviar(ip, puerto, fin);
                sesion.setEstado("FIN_SENT");

                System.out.println("  Four-way handshake iniciado con " + clave);
            } else if ("FIN_SENT".equals(sesion.getEstado())) {
                // Paso 4: ACK de nuestro FIN
                if (mensaje.startsWith("ACK:")) {
                    sesiones.remove(clave);
                    System.out.println("  ✓ Conexión cerrada con " + clave);
                }
            }
        }

        private void manejarACK(InetAddress ip, int puerto, String mensaje, String clave)
                throws IOException {
            SesionCliente sesion = sesiones.get(clave);
            if (sesion == null)
                return;

            if ("SYN_RCVD".equals(sesion.getEstado())) {
                sesion.setEstado("ESTABLISHED");
                System.out.println("  ✓ Conexión establecida con " + clave);
            }
        }

        private void guardarArchivo(SesionCliente sesion) {
            try {
                String nombreArchivo = "cliente_" +
                        sesion.getIp().getHostAddress().replace('.', '_') + "_" +
                        sesion.getNombreArchivo();

                Path ruta = Paths.get(DIRECTORIO_ARCHIVOS, nombreArchivo);
                Files.write(ruta, sesion.getDatos().getBytes());

                System.out.println("  Archivo guardado: " + ruta);
                System.out.println("  Tamaño: " + sesion.getDatos().length() + " bytes");

            } catch (IOException e) {
                System.err.println("  Error guardando archivo: " + e.getMessage());
            }
        }

        private void reenviarArchivo(InetAddress ip, int puerto, SesionCliente sesion)
                throws IOException {
            String contenido = sesion.getDatos();
            String[] lineas = contenido.split("\n");

            System.out.println("  Reenviando " + lineas.length + " líneas al cliente");

            long seqReenvio = 10000; // Secuencia diferente para reenvío

            for (int i = 0; i < lineas.length; i++) {
                String linea = lineas[i].trim();
                if (linea.isEmpty())
                    continue;

                String dataPacket = "DATA:" + seqReenvio + ":" + linea.length() + ":" + linea;

                boolean ackRecibido = false;
                for (int intento = 1; intento <= 3 && !ackRecibido; intento++) {
                    try {
                        enviar(ip, puerto, dataPacket);

                        // Esperar ACK
                        byte[] buffer = new byte[1024];
                        DatagramPacket ackPacket = new DatagramPacket(buffer, buffer.length);
                        socket.setSoTimeout(1000);

                        try {
                            socket.receive(ackPacket);
                            String ackMsg = new String(ackPacket.getData(), 0, ackPacket.getLength());

                            if (ackMsg.equals("ACK:" + seqReenvio)) {
                                ackRecibido = true;
                                System.out.println("    Línea " + (i + 1) + " reenviada correctamente");
                            }
                        } catch (SocketTimeoutException e) {
                            System.out.println("    Timeout línea " + (i + 1) + ", reintento " + intento);
                        } finally {
                            socket.setSoTimeout(0);
                        }
                    } catch (Exception e) {
                        System.err.println("Error reenviando línea: " + e.getMessage());
                    }
                }

                seqReenvio++;
            }

            // Enviar FIN del reenvío
            String finReenvio = "DATA:" + seqReenvio + ":0:FIN";
            enviar(ip, puerto, finReenvio);
            System.out.println("  ✓ Reenvío completado");
        }

        private void enviar(InetAddress ip, int puerto, String mensaje) throws IOException {
            byte[] buffer = mensaje.getBytes();
            DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
            socket.send(paquete);
        }

        private void enviarError(InetAddress ip, int puerto, String error) throws IOException {
            enviar(ip, puerto, "ERROR:" + error);
        }
    }

    // ===== CLASE PARA SESIONES DE CLIENTE =====

    static class SesionCliente {
        private InetAddress ip;
        private int puerto;
        private String nombreArchivo;
        private StringBuilder datos;
        private long secuenciaEsperada;
        private String estado;

        public SesionCliente(InetAddress ip, int puerto, String nombreArchivo) {
            this.ip = ip;
            this.puerto = puerto;
            this.nombreArchivo = nombreArchivo;
            this.datos = new StringBuilder();
            this.secuenciaEsperada = 1;
            this.estado = "SYN_RCVD";
        }

        public InetAddress getIp() {
            return ip;
        }

        public int getPuerto() {
            return puerto;
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

        public String getEstado() {
            return estado;
        }

        public void setSecuenciaEsperada(long seq) {
            this.secuenciaEsperada = seq;
        }

        public void setEstado(String estado) {
            this.estado = estado;
        }

        public void agregarDatos(String nuevosDatos) {
            datos.append(nuevosDatos);
        }

        public void incrementarSecuencia() {
            secuenciaEsperada++;
        }
    }
}