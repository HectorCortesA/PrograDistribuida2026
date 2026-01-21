package com.practica1;

import java.io.*;
import java.net.*;

public class ServidorUDPConfiable {
    private static final int PUERTO = 5000;

    public static void main(String[] args) {
        System.out.println("=== SERVIDOR UDP CONFIABLE ===");
        System.out.println("Puerto: " + PUERTO);
        System.out.println("Modo: Escuchando en TODAS las interfaces de red (0.0.0.0)");
        System.out.println("Directorio archivos: " + Configuracion.DIRECTORIO_ARCHIVOS);
        System.out.println("Esperando conexiones de clientes locales y remotos...\n");

        try {
            // Crear socket que escuche en TODAS las interfaces (0.0.0.0)
            DatagramSocket socket = new DatagramSocket(PUERTO, InetAddress.getByName("0.0.0.0"));

            // Obtener y mostrar todas las IPs disponibles
            System.out.println("✓ Servidor escuchando en:");
            System.out.println("  - Todas las interfaces (0.0.0.0)");
            System.out.println("  - Puerto: " + PUERTO + " (UDP)");
            System.out.println("  - Tiempo de espera: " + Configuracion.TIMEOUT_RECEPCION + "ms");

            // Listar todas las interfaces de red
            System.out.println("\n=== INTERFACES DE RED DISPONIBLES ===");
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp()) {
                    java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    boolean tieneIPv4 = false;
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            tieneIPv4 = true;
                            break;
                        }
                    }

                    if (tieneIPv4) {
                        System.out.println("\nInterfaz: " + iface.getDisplayName());
                        System.out.println("  Nombre: " + iface.getName());
                        System.out.println("  Direcciones IPv4:");

                        addresses = iface.getInetAddresses();
                        while (addresses.hasMoreElements()) {
                            InetAddress addr = addresses.nextElement();
                            if (addr instanceof Inet4Address) {
                                String tipo = addr.isLoopbackAddress() ? " (Loopback)"
                                        : addr.isSiteLocalAddress() ? " (Red Local)"
                                                : addr.isLinkLocalAddress() ? " (Link Local)" : " (Pública)";
                                System.out.println("    - " + addr.getHostAddress() + tipo);
                            }
                        }
                    }
                }
            }

            // Mostrar información importante para clientes remotos
            System.out.println("\n=== INFORMACIÓN PARA CLIENTES ===");
            System.out.println("Los clientes pueden conectarse usando cualquiera de estas IPs:");

            // Mostrar IPs no loopback
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            System.out.println(
                                    "  - " + addr.getHostAddress() + " (desde " + iface.getDisplayName() + ")");
                        }
                    }
                }
            }

            // Mostrar IP loopback para pruebas locales
            System.out.println("  - 127.0.0.1 (localhost, pruebas locales)");

            System.out.println("\n✓ Servidor listo para recibir conexiones");
            System.out.println("  Max clientes concurrentes: Ilimitado (hilos)");
            System.out.println("  Tamaño buffer: " + Configuracion.TAMANO_BUFFER + " bytes");
            System.out.println("  Archivos se guardan en: " + Configuracion.DIRECTORIO_ARCHIVOS);
            System.out.println("\n=== LOG DE CONEXIONES ===\n");

            // Crear directorio para archivos recibidos si no existe
            new File(Configuracion.DIRECTORIO_ARCHIVOS).mkdirs();

            while (true) {
                try {
                    byte[] buffer = new byte[Configuracion.TAMANO_BUFFER];
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socket.receive(paquete);

                    // Mostrar información del cliente
                    String ipCliente = paquete.getAddress().getHostAddress();
                    int puertoCliente = paquete.getPort();
                    String mensaje = new String(paquete.getData(), 0, paquete.getLength());

                    System.out.println("\n[CONEXIÓN ENTRANTE]");
                    System.out.println("  Cliente: " + ipCliente + ":" + puertoCliente);
                    System.out.println(
                            "  Mensaje: " + (mensaje.length() > 50 ? mensaje.substring(0, 50) + "..." : mensaje));
                    System.out.println("  Sesiones activas: " + GestorSesiones.getNumeroSesiones());

                    // Manejar cada cliente en hilo separado para concurrencia
                    new Thread(new ManejadorCliente(paquete, socket)).start();

                } catch (IOException e) {
                    System.err.println("[ERROR] Recibiendo paquete: " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            System.err.println("\n[ERROR] No se pudo crear el socket:");
            System.err.println("  Mensaje: " + e.getMessage());
            System.err.println("\nPosibles soluciones:");
            System.err.println("  1. Puerto " + PUERTO + " ya está en uso:");
            System.err.println("     netstat -tuln | grep " + PUERTO);
            System.err.println("     sudo lsof -i :" + PUERTO);
            System.err.println("  2. No tienes permisos (Linux/Mac):");
            System.err.println("     sudo java ServidorUDPConfiable");
            System.err.println("  3. Firewall bloqueando el puerto:");
            System.err.println("     sudo ufw allow " + PUERTO + "/udp");
            System.exit(1);
        } catch (UnknownHostException e) {
            System.err.println("[ERROR] Host desconocido: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[ERROR] Inesperado: " + e.getMessage());
            e.printStackTrace();
        }
    }
}