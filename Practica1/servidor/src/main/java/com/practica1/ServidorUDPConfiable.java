package com.practica1;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.*;

public class ServidorUDPConfiable {
    private static final int PUERTO = 22000;
    private static final ConcurrentHashMap<String, SesionCliente> sesiones = new ConcurrentHashMap<>();
    private static final String DIRECTORIO_BASE = "/Users/hectorcortes/Downloads";

    public static void main(String[] args) {
        System.out.println("=== SERVIDOR UDP CONFIABLE ===");
        System.out.println("Puerto: " + PUERTO);
        System.out.println("Directorio base: " + DIRECTORIO_BASE);
        System.out.println("Escuchando en todas las interfaces");
        System.out.println("==============================\n");

        // Verificar que el directorio existe
        File directorio = new File(DIRECTORIO_BASE);
        if (!directorio.exists() || !directorio.isDirectory()) {
            System.err.println("ERROR: El directorio base no existe o no es válido");
            System.err.println("Directorio: " + DIRECTORIO_BASE);
            return;
        }

        System.out.println("Archivos disponibles en el servidor:");
        listarArchivosDisponibles();

        try (DatagramSocket socket = new DatagramSocket(PUERTO, InetAddress.getByName("0.0.0.0"))) {

            System.out.println("\n✓ Servidor iniciado correctamente");
            System.out.println("  IP: " + socket.getLocalAddress().getHostAddress());
            System.out.println("  Puerto: " + socket.getLocalPort());
            System.out.println("\nEsperando solicitudes de clientes...\n");

            while (true) {
                try {
                    byte[] buffer = new byte[4096];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);

                    // Crear hilo para manejar cliente
                    new Thread(new ManejadorCliente(paquete, socket, directorio)).start();

                } catch (IOException e) {
                    System.err.println("Error recibiendo paquete: " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            System.err.println("Error creando socket: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error inesperado: " + e.getMessage());
        }
    }

    // Método para listar archivos disponibles
    private static void listarArchivosDisponibles() {
        File directorio = new File(DIRECTORIO_BASE);
        File[] archivos = directorio.listFiles();

        if (archivos == null || archivos.length == 0) {
            System.out.println("  No hay archivos en el directorio");
            return;
        }

        int contador = 0;
        for (File archivo : archivos) {
            if (archivo.isFile()) {
                contador++;
                System.out.printf("  %2d. %-40s (%d bytes)%n",
                        contador,
                        archivo.getName(),
                        archivo.length());
            }
        }
        System.out.println("  Total: " + contador + " archivos disponibles");
    }

    // ===== CLASE PARA MANEJAR CLIENTES =====

    static class ManejadorCliente implements Runnable {
        private DatagramPacket paqueteInicial;
        private DatagramSocket socket;
        private File directorioBase;

        public ManejadorCliente(DatagramPacket paquete, DatagramSocket socket, File directorio) {
            this.paqueteInicial = paquete;
            this.socket = socket;
            this.directorioBase = directorio;
        }

        @Override
        public void run() {
            InetAddress ipCliente = paqueteInicial.getAddress();
            int puertoCliente = paqueteInicial.getPort();
            String claveCliente = ipCliente.getHostAddress() + ":" + puertoCliente;

            System.out.println("\n[+] Cliente conectado: " + claveCliente);

            try {
                String mensaje = new String(paqueteInicial.getData(), 0, paqueteInicial.getLength());

                if (mensaje.startsWith("SYN:")) {
                    manejarSYN(ipCliente, puertoCliente, mensaje, claveCliente);
                } else if (mensaje.equals("LISTA")) {
                    manejarLista(ipCliente, puertoCliente);
                } else if (mensaje.startsWith("ACK:")) {
                    manejarACK(ipCliente, puertoCliente, mensaje, claveCliente);
                } else if (mensaje.startsWith("FIN:")) {
                    manejarFIN(ipCliente, puertoCliente, mensaje, claveCliente);
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

            System.out.println("  Cliente solicita archivo: " + nombreArchivo);
            System.out.println("  Secuencia inicial: " + seq);

            // Verificar si el archivo existe
            File archivoSolicitado = new File(directorioBase, nombreArchivo);
            if (!archivoSolicitado.exists() || !archivoSolicitado.isFile()) {
                System.out.println("  ✗ Archivo no encontrado: " + nombreArchivo);
                enviarError(ip, puerto, "Archivo '" + nombreArchivo + "' no encontrado en el servidor");
                return;
            }

            System.out.println("  ✓ Archivo encontrado: " + archivoSolicitado.getAbsolutePath());
            System.out.println("  Tamaño: " + archivoSolicitado.length() + " bytes");

            // Crear nueva sesión
            SesionCliente sesion = new SesionCliente(ip, puerto, nombreArchivo, archivoSolicitado);
            sesion.setSecuenciaEsperada(seq + 2);
            sesiones.put(clave, sesion);

            // Enviar SYN-ACK
            String synAck = "SYN-ACK:" + nombreArchivo + ":" + (seq + 1);
            enviar(ip, puerto, synAck);
            System.out.println("  SYN-ACK enviado a " + clave);
        }

        private void manejarLista(InetAddress ip, int puerto) throws IOException {
            System.out.println("  Solicitud de lista de archivos recibida");

            StringBuilder lista = new StringBuilder();
            lista.append("=== ARCHIVOS DISPONIBLES EN SERVIDOR ===\n");
            lista.append("Directorio: ").append(directorioBase.getName()).append("\n\n");

            File[] archivos = directorioBase.listFiles();
            if (archivos == null || archivos.length == 0) {
                lista.append("No hay archivos en este directorio.\n");
            } else {
                int contador = 0;
                for (File archivo : archivos) {
                    if (archivo.isFile()) {
                        contador++;
                        lista.append(String.format("%3d. %-40s (%d bytes)%n",
                                contador,
                                archivo.getName(),
                                archivo.length()));
                    }
                }
                lista.append("\nTotal: ").append(contador).append(" archivos\n");
            }

            lista.append("\nPara solicitar un archivo, use:");
            lista.append("\n  java ClienteUDPConfiable");
            lista.append("\n  y seleccione 'Solicitar archivo específico'");

            enviar(ip, puerto, lista.toString());
            System.out.println("  Lista enviada al cliente");
        }

        private void manejarACK(InetAddress ip, int puerto, String mensaje, String clave)
                throws IOException {
            SesionCliente sesion = sesiones.get(clave);
            if (sesion == null) {
                System.out.println("  Sesión no encontrada para ACK de " + clave);
                return;
            }

            System.out.println("  ACK recibido de " + clave + ": " + mensaje);

            if ("SYN_RCVD".equals(sesion.getEstado())) {
                sesion.setEstado("ESTABLISHED");
                System.out.println("  ✓ Conexión establecida con " + clave);

                // Iniciar envío del archivo
                System.out.println("  Iniciando envío del archivo: " + sesion.getNombreArchivo());
                enviarArchivo(ip, puerto, sesion);

            } else if ("ENVIANDO".equals(sesion.getEstado())) {
                // Continuar enviando archivo
                continuarEnvioArchivo(ip, puerto, sesion);
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

            if ("ESTABLISHED".equals(sesion.getEstado()) || "ENVIANDO".equals(sesion.getEstado())) {
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
                    System.out.println("  Total archivo enviado: " + sesion.getLineasEnviadas() + " líneas");
                }
            }
        }

        private void enviarArchivo(InetAddress ip, int puerto, SesionCliente sesion)
                throws IOException {
            sesion.setEstado("ENVIANDO");

            // Leer archivo
            BufferedReader lector = new BufferedReader(new FileReader(sesion.getArchivo()));
            String linea;
            int numeroLinea = 0;

            // Leer primeras líneas para enviar
            while ((linea = lector.readLine()) != null && numeroLinea < 10) {
                long seq = sesion.getSecuenciaEnvio();
                String dataPacket = "DATA:" + seq + ":" + linea.length() + ":" + linea;

                // Enviar línea
                enviar(ip, puerto, dataPacket);
                sesion.incrementarSecuenciaEnvio();
                sesion.incrementarLineasEnviadas();
                numeroLinea++;

                System.out.println("    Línea " + numeroLinea + " enviada (seq=" + seq + ")");
            }

            if (linea == null) {
                // Archivo completo enviado
                System.out.println("  Archivo completo enviado (" + sesion.getLineasEnviadas() + " líneas)");

                // Enviar FIN-DATA
                long finSeq = sesion.getSecuenciaEnvio();
                String finData = "DATA:" + finSeq + ":0:FIN";
                enviar(ip, puerto, finData);
                System.out.println("  FIN-DATA enviado (seq=" + finSeq + ")");

                sesion.setEstado("TRANSFER_COMPLETE");
            }

            lector.close();
        }

        private void continuarEnvioArchivo(InetAddress ip, int puerto, SesionCliente sesion)
                throws IOException {
            if ("TRANSFER_COMPLETE".equals(sesion.getEstado())) {
                return; // Transferencia ya completada
            }

            BufferedReader lector = new BufferedReader(new FileReader(sesion.getArchivo()));

            // Saltar líneas ya enviadas
            for (int i = 0; i < sesion.getLineasEnviadas(); i++) {
                lector.readLine();
            }

            String linea;
            int lineasEnEsteBloque = 0;

            // Enviar siguiente bloque de líneas
            while ((linea = lector.readLine()) != null && lineasEnEsteBloque < 10) {
                long seq = sesion.getSecuenciaEnvio();
                String dataPacket = "DATA:" + seq + ":" + linea.length() + ":" + linea;

                enviar(ip, puerto, dataPacket);
                sesion.incrementarSecuenciaEnvio();
                sesion.incrementarLineasEnviadas();
                lineasEnEsteBloque++;

                if (sesion.getLineasEnviadas() % 50 == 0) {
                    System.out.println("    " + sesion.getLineasEnviadas() + " líneas enviadas...");
                }
            }

            if (linea == null) {
                // Archivo completo enviado
                System.out.println("  ✓ Archivo completo enviado: " + sesion.getLineasEnviadas() + " líneas");

                // Enviar FIN-DATA
                long finSeq = sesion.getSecuenciaEnvio();
                String finData = "DATA:" + finSeq + ":0:FIN";
                enviar(ip, puerto, finData);
                System.out.println("  FIN-DATA enviado (seq=" + finSeq + ")");

                sesion.setEstado("TRANSFER_COMPLETE");
            }

            lector.close();
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
        private File archivo;
        private long secuenciaEnvio;
        private String estado;
        private int lineasEnviadas;

        public SesionCliente(InetAddress ip, int puerto, String nombreArchivo, File archivo) {
            this.ip = ip;
            this.puerto = puerto;
            this.nombreArchivo = nombreArchivo;
            this.archivo = archivo;
            this.secuenciaEnvio = 1000; // Secuencia inicial para envío
            this.estado = "SYN_RCVD";
            this.lineasEnviadas = 0;
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

        public File getArchivo() {
            return archivo;
        }

        public long getSecuenciaEnvio() {
            return secuenciaEnvio;
        }

        public String getEstado() {
            return estado;
        }

        public int getLineasEnviadas() {
            return lineasEnviadas;
        }

        public void setSecuenciaEsperada(long seq) {
            this.secuenciaEnvio = seq;
        }

        public void setEstado(String estado) {
            this.estado = estado;
        }

        public void incrementarSecuenciaEnvio() {
            secuenciaEnvio++;
        }

        public void incrementarLineasEnviadas() {
            lineasEnviadas++;
        }
    }
}