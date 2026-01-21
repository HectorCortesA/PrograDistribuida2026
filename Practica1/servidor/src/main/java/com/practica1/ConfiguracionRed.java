package com.practica1;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.Enumeration;

public class ConfiguracionRed {

    // Método para obtener todas las IPs locales
    public static void mostrarInterfacesRed() {
        System.out.println("=== INTERFACES DE RED DISPONIBLES ===");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp()) {
                    System.out.println("\nInterfaz: " + iface.getDisplayName());
                    System.out.println("  Nombre: " + iface.getName());
                    System.out.println("  Direcciones:");

                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    boolean tieneDirecciones = false;
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        tieneDirecciones = true;
                        if (addr instanceof Inet4Address) {
                            String tipo = "";
                            if (addr.isLoopbackAddress())
                                tipo = " (Loopback)";
                            else if (addr.isSiteLocalAddress())
                                tipo = " (Red Local)";
                            else if (addr.isLinkLocalAddress())
                                tipo = " (Link Local)";
                            else
                                tipo = " (Pública)";
                            System.out.println("    IPv4: " + addr.getHostAddress() + tipo);
                        } else if (addr instanceof Inet6Address) {
                            System.out.println("    IPv6: " + addr.getHostAddress());
                        }
                    }

                    if (!tieneDirecciones) {
                        System.out.println("    Sin direcciones IP asignadas");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error obteniendo interfaces: " + e.getMessage());
        }
    }

    // Método para obtener IP pública (aproximada)
    public static String obtenerIPPublica() {
        try {
            // Conectar a un servicio público para obtener IP
            URL url = new URL("http://checkip.amazonaws.com");
            BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
            return in.readLine().trim();
        } catch (Exception e) {
            try {
                // Servicio alternativo
                URL url = new URL("http://ipecho.net/plain");
                BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
                return in.readLine().trim();
            } catch (Exception ex) {
                return "No disponible - " + ex.getMessage();
            }
        }
    }

    // Método para verificar conectividad con servidor
    public static boolean probarConexion(String ipServidor, int puerto) {
        System.out.println("Probando conexión con " + ipServidor + ":" + puerto + "...");
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);

            // Enviar paquete de prueba
            String mensaje = "TEST_CONEXION";
            byte[] buffer = mensaje.getBytes();
            DatagramPacket paquete = new DatagramPacket(
                    buffer,
                    buffer.length,
                    InetAddress.getByName(ipServidor),
                    puerto);

            socket.send(paquete);
            System.out.println("  Paquete de prueba enviado");

            // Intentar recibir respuesta
            byte[] bufferRx = new byte[1024];
            DatagramPacket respuesta = new DatagramPacket(bufferRx, bufferRx.length);

            try {
                socket.receive(respuesta);
                String respuestaStr = new String(respuesta.getData(), 0, respuesta.getLength());
                System.out.println("  ✓ Respuesta recibida: " + respuestaStr);
                return true;
            } catch (SocketTimeoutException e) {
                System.out.println("  ⏱ Timeout - El servidor no responde pero el puerto podría estar abierto");
                return true; // El paquete se envió, no hubo error de red
            }

        } catch (Exception e) {
            System.out.println("  ✗ Error de conexión: " + e.getMessage());
            return false;
        }
    }

    // Método para obtener IP local preferida (no loopback)
    public static String obtenerIPLocalPreferida() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }

            // Si no encuentra IP de sitio local, usar loopback
            return "127.0.0.1";
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // Método para comprobar si un puerto está disponible
    public static boolean puertoDisponible(int puerto) {
        try (DatagramSocket socket = new DatagramSocket(puerto)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}