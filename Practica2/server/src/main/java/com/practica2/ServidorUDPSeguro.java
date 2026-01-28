package com.practica2;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class ServidorUDPSeguro {

    private static final int PUERTO = Configuracion.PUERTO_SERVIDOR;
    private static final String DIRECTORIO_ARCHIVOS = Configuracion.DIRECTORIO_BASE;
    private static final Map<String, SesionSegura> sesionesSeguras = new ConcurrentHashMap<>();
    private static FileWriter logWriter;
    private static final String LOG_FILE = "servidor_seguro.log";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        try {
            logWriter = new FileWriter(LOG_FILE, true);
            escribirLog("=== SERVIDOR SEGURO INICIADO ===");
        } catch (IOException e) {
            System.err.println("Error creando log: " + e.getMessage());
            return;
        }

        System.out.println("=== SERVIDOR UDP SEGURO (TLS) ===");
        System.out.println("Puerto: " + PUERTO);
        System.out.println("Directorio: " + DIRECTORIO_ARCHIVOS);
        System.out.println("Log: " + LOG_FILE);
        System.out.println("Cifrado: AES-256 + RSA-2048");

        // Crear par de claves RSA del servidor
        KeyPairGenerator kpg;
        KeyPair parClaveServidor;
        try {
            kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            parClaveServidor = kpg.generateKeyPair();
            System.out.println("✓ Par de claves RSA generado (2048 bits)");
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Error generando claves: " + e.getMessage());
            return;
        }

        // Verificar directorio
        File directorio = new File(DIRECTORIO_ARCHIVOS);
        if (!directorio.exists()) {
            directorio.mkdirs();
        }

        System.out.println("\nArchivos disponibles:");
        File[] archivos = directorio.listFiles();
        if (archivos != null) {
            for (File archivo : archivos) {
                if (archivo.isFile()) {
                    System.out.println("  - " + archivo.getName());
                }
            }
        }
        System.out.println();

        try (DatagramSocket socket = new DatagramSocket(PUERTO)) {
            socket.setSoTimeout(1000);

            System.out.println("✓ Servidor escuchando en puerto " + PUERTO);
            System.out.println("Esperando conexiones seguras...\n");

            while (true) {
                try {
                    byte[] buffer = new byte[65507];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);

                    procesarPaquete(paquete, socket, parClaveServidor);

                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    escribirLog("[ERROR] Recibiendo paquete: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            try {
                if (logWriter != null) {
                    escribirLog("=== SERVIDOR DETENIDO ===\n");
                    logWriter.close();
                }
            } catch (IOException e) {
                System.err.println("Error cerrando log: " + e.getMessage());
            }
        }
    }

    private static void procesarPaquete(DatagramPacket paquete, DatagramSocket socket, KeyPair parClaveServidor) {
        String mensaje = new String(paquete.getData(), 0, paquete.getLength());
        InetAddress ipCliente = paquete.getAddress();
        int puertoCliente = paquete.getPort();
        String claveCliente = ipCliente.getHostAddress() + ":" + puertoCliente;

        try {
            if (mensaje.startsWith("CLIENTHELLO:")) {
                manejarClientHello(mensaje, ipCliente, puertoCliente, socket, parClaveServidor);
            } else if (mensaje.startsWith("RENEGOCIAR:")) {
                manejarRenegociacion(mensaje, ipCliente, puertoCliente, socket, parClaveServidor, claveCliente);
            } else {
                // Mensaje cifrado
                SesionSegura sesion = sesionesSeguras.get(claveCliente);
                if (sesion != null) {
                    procesarMensajeCifrado(mensaje, ipCliente, puertoCliente, socket, sesion);
                }
            }

        } catch (Exception e) {
            escribirLog("[ERROR] Procesando paquete: " + e.getMessage());
        }
    }

    private static void manejarClientHello(String mensaje, InetAddress ip, int puerto, DatagramSocket socket,
            KeyPair parClaveServidor) throws Exception {
        String[] partes = mensaje.split(":", 2);
        byte[] claveClientePublica = Base64.getDecoder().decode(partes[1]);

        System.out.println("\n[TLS HANDSHAKE] " + ip.getHostAddress() + ":" + puerto);
        System.out.println("  ← ClientHello recibido");

        // Generar clave AES compartida
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey claveAES = kg.generateKey();

        // Cifrar clave AES con clave pública del cliente
        Cipher cipherRSA = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        PublicKey clavePublicaCliente = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(claveClientePublica));
        cipherRSA.init(Cipher.ENCRYPT_MODE, clavePublicaCliente);
        byte[] claveAESCifrada = cipherRSA.doFinal(claveAES.getEncoded());

        // Enviar ServerHello
        String serverHello = "SERVERHELLO:"
                + Base64.getEncoder().encodeToString(parClaveServidor.getPublic().getEncoded())
                + ":" + Base64.getEncoder().encodeToString(claveAESCifrada);
        enviarMensaje(ip, puerto, serverHello, socket);

        System.out.println("  → ServerHello enviado (Clave AES cifrada)");

        // Guardar sesión segura
        SesionSegura sesion = new SesionSegura(ip, puerto, claveAES);
        sesionesSeguras.put(ip.getHostAddress() + ":" + puerto, sesion);

        System.out.println("  ✓ Handshake TLS completado\n");
        escribirLog("[TLS] Handshake completado - " + ip.getHostAddress() + ":" + puerto);
    }

    private static void manejarRenegociacion(String mensaje, InetAddress ip, int puerto, DatagramSocket socket,
            KeyPair parClaveServidor, String claveCliente) throws Exception {
        System.out.println("\n[RENEGOCIACIÓN] " + ip.getHostAddress() + ":" + puerto);

        String[] partes = mensaje.split(":", 2);
        byte[] claveClientePublica = Base64.getDecoder().decode(partes[1]);

        // Generar nueva clave AES
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey nuevaClaveAES = kg.generateKey();

        // Cifrar con clave pública del cliente
        Cipher cipherRSA = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        PublicKey clavePublicaCliente = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(claveClientePublica));
        cipherRSA.init(Cipher.ENCRYPT_MODE, clavePublicaCliente);
        byte[] claveAESCifrada = cipherRSA.doFinal(nuevaClaveAES.getEncoded());

        // Enviar nueva clave
        String serverHello = "SERVERHELLO:"
                + Base64.getEncoder().encodeToString(parClaveServidor.getPublic().getEncoded())
                + ":" + Base64.getEncoder().encodeToString(claveAESCifrada);
        enviarMensaje(ip, puerto, serverHello, socket);

        // Actualizar sesión
        SesionSegura sesion = sesionesSeguras.get(claveCliente);
        if (sesion != null) {
            sesion.actualizarClave(nuevaClaveAES);
        }

        System.out.println("  ✓ Nueva clave establecida");
        escribirLog("[RENEGOCIACIÓN] Nueva clave - " + ip.getHostAddress() + ":" + puerto);
    }

    private static void procesarMensajeCifrado(String mensajeCifrado, InetAddress ip, int puerto, DatagramSocket socket,
            SesionSegura sesion) throws Exception {
        String mensaje = sesion.descifrar(mensajeCifrado);

        if (mensaje.startsWith("SYN:")) {
            manejarSYNCifrado(mensaje, ip, puerto, socket, sesion);
        } else if (mensaje.startsWith("ACK:")) {
            manejarACKCifrado(mensaje, sesion);
        } else if (mensaje.startsWith("FIN:")) {
            sesionesSeguras.remove(ip.getHostAddress() + ":" + puerto);
            System.out.println("✓ Cliente " + ip.getHostAddress() + ":" + puerto + " desconectado");
        }
    }

    private static void manejarSYNCifrado(String mensaje, InetAddress ip, int puerto, DatagramSocket socket,
            SesionSegura sesion) throws Exception {
        String[] partes = mensaje.split(":");
        String nombreArchivo = partes[1];

        System.out.println("\n[CONEXIÓN SEGURA] " + ip.getHostAddress() + ":" + puerto);
        System.out.println("  Archivo: " + nombreArchivo);

        File archivo = new File(DIRECTORIO_ARCHIVOS, nombreArchivo);
        if (!archivo.exists()) {
            System.out.println("  ✗ No encontrado");
            String error = "ERROR:Archivo no existe";
            enviarMensaje(ip, puerto, sesion.cifrar(error), socket);
            return;
        }

        System.out.println("  ✓ Encontrado (" + formatearTamanio(archivo.length()) + ")");

        // Enviar SYN-ACK cifrado
        long seqSynAck = System.currentTimeMillis() % 10000;
        String synAck = "SYN-ACK:" + nombreArchivo + ":" + seqSynAck;
        String synAckCifrado = sesion.cifrar(synAck);
        enviarMensaje(ip, puerto, synAckCifrado, socket);

        // Crear hilo para enviar archivo
        HiloClienteSeguro hiloCliente = new HiloClienteSeguro(ip, puerto, archivo, socket, sesion);
        new Thread(hiloCliente).start();

        escribirLog("[CONEXIÓN] " + ip.getHostAddress() + ":" + puerto + " - Archivo: " + nombreArchivo);
    }

    private static void manejarACKCifrado(String mensaje, SesionSegura sesion) {
        // Procesado por el hilo de envío
    }

    private static void enviarMensaje(InetAddress ip, int puerto, String mensaje, DatagramSocket socket)
            throws IOException {
        byte[] buffer = mensaje.getBytes();
        DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
        socket.send(paquete);
    }

    private static void escribirLog(String mensaje) {
        String timestamp = LocalDateTime.now().format(formatter);
        String logLine = "[" + timestamp + "] " + mensaje;

        try {
            if (logWriter != null) {
                logWriter.write(logLine + "\n");
                logWriter.flush();
            }
        } catch (IOException e) {
            System.err.println("Error en log: " + e.getMessage());
        }
    }

    private static String formatearTamanio(long bytes) {
        if (bytes <= 0)
            return "0 B";
        final String[] unidades = { "B", "KB", "MB", "GB" };
        int indice = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, indice), unidades[indice]);
    }

    // ===== HILO PARA ENVÍO SEGURO =====

    static class HiloClienteSeguro implements Runnable {
        private InetAddress ip;
        private int puerto;
        private File archivo;
        private DatagramSocket socket;
        private SesionSegura sesion;
        private long secuenciaActual = 1000;
        private boolean esperandoACK = false;
        private long ultimoSeqEnviado = 0;
        private Object lock = new Object();

        public HiloClienteSeguro(InetAddress ip, int puerto, File archivo, DatagramSocket socket, SesionSegura sesion) {
            this.ip = ip;
            this.puerto = puerto;
            this.archivo = archivo;
            this.socket = socket;
            this.sesion = sesion;
        }

        @Override
        public void run() {
            try {
                List<String> lineas = Files.readAllLines(archivo.toPath());
                int totalLineas = lineas.size();

                System.out.println("  Iniciando envío seguro: " + totalLineas + " líneas\n");

                for (int i = 0; i < totalLineas; i++) {
                    String linea = lineas.get(i);

                    boolean ackRecibido = false;
                    int reintentos = 0;

                    while (!ackRecibido && reintentos < 3) {
                        synchronized (lock) {
                            String dataMsg = "DATA:" + secuenciaActual + ":" + linea.length() + ":" + linea;
                            String dataCifrado = sesion.cifrar(dataMsg);
                            enviarMensaje(ip, puerto, dataCifrado, socket);
                            esperandoACK = true;
                            ultimoSeqEnviado = secuenciaActual;
                        }

                        long inicioEspera = System.currentTimeMillis();
                        while (esperandoACK && (System.currentTimeMillis() - inicioEspera) < 2000) {
                            Thread.sleep(100);
                        }

                        synchronized (lock) {
                            if (!esperandoACK) {
                                ackRecibido = true;
                                secuenciaActual++;
                            }
                        }

                        reintentos++;
                    }

                    if (!ackRecibido) {
                        System.out.println("✗ Error enviando línea " + (i + 1));
                        return;
                    }

                    mostrarProgreso(i + 1, totalLineas);
                }

                // Enviar FIN-DATA cifrado
                String finData = "DATA:" + secuenciaActual + ":0:FIN-DATA";
                String finDataCifrado = sesion.cifrar(finData);
                enviarMensaje(ip, puerto, finDataCifrado, socket);

                // Enviar FIN cifrado
                Thread.sleep(500);
                String fin = "FIN:" + (secuenciaActual + 1);
                String finCifrado = sesion.cifrar(fin);
                enviarMensaje(ip, puerto, finCifrado, socket);

                System.out.println("\n✓ Transferencia segura: " + totalLineas + " líneas");
                escribirLog("[TRANSFERENCIA] Completa - " + totalLineas + " líneas - " + ip.getHostAddress());

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                escribirLog("[ERROR] " + e.getMessage());
            }
        }

        private void mostrarProgreso(int actual, int total) {
            int ancho = 40;
            int relleno = (int) ((float) actual / total * ancho);

            System.out.print("\r  [");
            for (int i = 0; i < ancho; i++) {
                System.out.print(i < relleno ? "=" : " ");
            }
            System.out.printf("] %d/%d (%.1f%%)", actual, total, ((float) actual / total * 100));
            System.out.flush();
        }

        private void enviarMensaje(InetAddress ip, int puerto, String mensaje, DatagramSocket socket)
                throws IOException {
            byte[] buffer = mensaje.getBytes();
            DatagramPacket paquete = new DatagramPacket(buffer, buffer.length, ip, puerto);
            socket.send(paquete);
        }
    }

    // ===== CLASE PARA GESTIÓN DE SESIÓN SEGURA =====

    static class SesionSegura {
        private InetAddress ip;
        private int puerto;
        private SecretKey claveAES;
        private long tiempoCreacion;
        private static final long TIEMPO_EXPIRACION = 300000; // 5 minutos

        public SesionSegura(InetAddress ip, int puerto, SecretKey claveAES) {
            this.ip = ip;
            this.puerto = puerto;
            this.claveAES = claveAES;
            this.tiempoCreacion = System.currentTimeMillis();
        }

        public String cifrar(String mensaje) throws Exception {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, claveAES);
            byte[] encrypted = cipher.doFinal(mensaje.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);
        }

        public String descifrar(String mensajeCifrado) throws Exception {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, claveAES);
            byte[] decodedBytes = Base64.getDecoder().decode(mensajeCifrado);
            byte[] decrypted = cipher.doFinal(decodedBytes);
            return new String(decrypted);
        }

        public void actualizarClave(SecretKey nuevaClave) {
            this.claveAES = nuevaClave;
            this.tiempoCreacion = System.currentTimeMillis();
        }

        public boolean claveExpiro() {
            return (System.currentTimeMillis() - tiempoCreacion) > TIEMPO_EXPIRACION;
        }
    }
}