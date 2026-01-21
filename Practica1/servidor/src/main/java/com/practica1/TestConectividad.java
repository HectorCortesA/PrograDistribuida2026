package com.practica1;

import java.net.*;

public class TestConectividad {

    public static void main(String[] args) {
        System.out.println("=== TEST DE CONECTIVIDAD UDP ===");
        System.out.println("Este programa prueba la conectividad con el servidor UDP");
        System.out.println();

        String ipServidor;
        if (args.length >= 1) {
            ipServidor = args[0];
        } else {
            ipServidor = "127.0.0.1"; // Default
            System.out.println("Usando servidor por defecto: " + ipServidor);
            System.out.println("Para probar otro servidor: java TestConectividad <ip-servidor>");
        }

        int puerto = 5000;

        System.out.println("Servidor objetivo: " + ipServidor + ":" + puerto);
        System.out.println("==================================\n");

        // Mostrar configuración local
        System.out.println("1. CONFIGURACIÓN LOCAL DEL CLIENTE:");
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            System.out.println("   Nombre del host: " + localHost.getHostName());
            System.out.println("   IP local: " + localHost.getHostAddress());

            // Mostrar todas las interfaces
            System.out.println("\n   Interfaces de red disponibles:");
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            System.out.println("     - " + iface.getDisplayName() + ": " + addr.getHostAddress());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("   Error obteniendo configuración local: " + e.getMessage());
        }

        // Test 1: Resolución DNS
        System.out.println("\n2. RESOLUCIÓN DNS:");
        try {
            InetAddress address = InetAddress.getByName(ipServidor);
            System.out.println("   IP resuelta: " + address.getHostAddress());
            System.out.println("   Nombre canónico: " + address.getCanonicalHostName());
        } catch (Exception e) {
            System.out.println("   ✗ Error resolviendo DNS: " + e.getMessage());
            System.out.println("   Posible solución: Verifica la IP o nombre del servidor");
        }

        // Test 2: Ping (ICMP)
        System.out.println("\n3. PRUEBA PING (ICMP):");
        try {
            InetAddress address = InetAddress.getByName(ipServidor);
            boolean reachable = address.isReachable(5000);
            System.out.println(
                    "   Resultado: " + (reachable ? "✓ El host responde a ping" : "✗ El host no responde a ping"));

            if (!reachable) {
                System.out.println("   Nota: Algunos servidores bloquean ICMP, esto no significa");
                System.out.println("         que el puerto UDP 5000 esté cerrado");
            }
        } catch (Exception e) {
            System.out.println("   Error en ping: " + e.getMessage());
        }

        // Test 3: Puerto UDP
        System.out.println("\n4. PRUEBA PUERTO UDP 5000:");
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5000);

            // Enviar paquete de prueba
            String mensaje = "TEST_UDP_CONNECTIVITY:" + System.currentTimeMillis();
            byte[] buffer = mensaje.getBytes();
            DatagramPacket paquete = new DatagramPacket(
                    buffer,
                    buffer.length,
                    InetAddress.getByName(ipServidor),
                    puerto);

            System.out.println("   Enviando paquete de prueba...");
            socket.send(paquete);
            System.out.println("   ✓ Paquete enviado correctamente");

            // Intentar recibir respuesta
            System.out.println("   Esperando respuesta (timeout 5s)...");
            byte[] bufferRx = new byte[1024];
            DatagramPacket respuesta = new DatagramPacket(bufferRx, bufferRx.length);

            try {
                socket.receive(respuesta);
                String respuestaStr = new String(respuesta.getData(), 0, respuesta.getLength());
                System.out.println("   ✓ ¡CONEXIÓN EXITOSA!");
                System.out.println("   Respuesta recibida desde: " +
                        respuesta.getAddress().getHostAddress() + ":" + respuesta.getPort());
                System.out.println("   Contenido: " + respuestaStr);

                // Analizar respuesta
                if (respuestaStr.contains("SYN-ACK") || respuestaStr.contains("ACK") ||
                        respuestaStr.contains("ERROR")) {
                    System.out.println("   Parece ser un servidor UDP Confiable válido");
                }

            } catch (SocketTimeoutException e) {
                System.out.println("   ⏱ Timeout - No se recibió respuesta");
                System.out.println("   Esto podría significar:");
                System.out.println("     a) El servidor no está ejecutándose");
                System.out.println("     b) El puerto 5000 está bloqueado por firewall");
                System.out.println("     c) El servidor está ocupado");
                System.out.println("   Nota: El paquete UDP se envió, por lo que la ruta existe");
            }

        } catch (Exception e) {
            System.out.println("   ✗ Error en conexión UDP: " + e.getMessage());
            System.out.println("   Posibles causas:");
            System.out.println("     - Firewall bloqueando salida UDP");
            System.out.println("     - No hay ruta a la red destino");
            System.out.println("     - Problemas de enrutamiento");
        }

        // Test 4: Puertos alternativos
        System.out.println("\n5. PRUEBA PUERTOS ALTERNATIVOS:");
        int[] puertosAlternativos = { 5001, 5005, 5010, 5050 };
        for (int puertoAlt : puertosAlternativos) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(1000);
                String mensaje = "TEST";
                byte[] buffer = mensaje.getBytes();
                DatagramPacket paquete = new DatagramPacket(
                        buffer, buffer.length,
                        InetAddress.getByName(ipServidor), puertoAlt);
                socket.send(paquete);
                System.out.println("   Puerto " + puertoAlt + ": Enviado (sin respuesta esperada)");
            } catch (Exception e) {
                // Ignorar errores para puertos alternativos
            }
        }

        // Recomendaciones
        System.out.println("\n=== RECOMENDACIONES ===");

        if (ipServidor.equals("127.0.0.1") || ipServidor.equals("localhost")) {
            System.out.println("✅ Estás probando localhost. Para probar con clientes remotos:");
            System.out.println("   1. Ejecuta el servidor en una IP visible en la red");
            System.out.println("   2. Usa esa IP en los clientes remotos");
            System.out.println("   3. Ejemplo: java ClienteUDPConfiable 0.0.0.0 192.168.1.100 archivo.txt");
        } else if (ipServidor.startsWith("192.168.") ||
                ipServidor.startsWith("10.") ||
                ipServidor.startsWith("172.16.")) {
            System.out.println("✅ IP de red local detectada.");
            System.out.println("   Para clientes en la MISMA red:");
            System.out.println("     java ClienteUDPConfiable 0.0.0.0 " + ipServidor + " archivo.txt");
            System.out.println();
            System.out.println("   Para clientes en OTRA red (internet):");
            System.out.println("     Necesitas port forwarding en el router:");
            System.out.println("     1. Accede al router (generalmente 192.168.1.1)");
            System.out.println("     2. Configura port forwarding:");
            System.out.println("        - Protocolo: UDP");
            System.out.println("        - Puerto externo: 5000");
            System.out.println("        - IP interna: " + ipServidor);
            System.out.println("        - Puerto interno: 5000");
            System.out.println("     3. Usa la IP PÚBLICA del router en clientes remotos");
        } else {
            System.out.println("✅ IP pública o externa detectada.");
            System.out.println("   Para clientes remotos:");
            System.out.println("     java ClienteUDPConfiable 0.0.0.0 " + ipServidor + " archivo.txt");
            System.out.println();
            System.out.println("   Nota: Asegúrate de que:");
            System.out.println("     - El servidor tenga puerto 5000 UDP abierto en firewall");
            System.out.println("     - El router tenga port forwarding si el servidor está en red local");
        }

        System.out.println("\n=== COMANDOS ÚTILES ===");
        System.out.println("Para verificar puerto en servidor (Linux/Mac):");
        System.out.println("  sudo netstat -tuln | grep 5000");
        System.out.println("  sudo lsof -i :5000");
        System.out.println();
        System.out.println("Para abrir puerto en firewall (Linux ufw):");
        System.out.println("  sudo ufw allow 5000/udp");
        System.out.println();
        System.out.println("Para probar manualmente (desde cliente):");
        System.out.println("  echo \"SYN:test.txt:1234\" | nc -u " + ipServidor + " 5000");

        System.out.println("\n✅ Test completado. Basado en los resultados:");
        System.out.println("   Si el test 4 mostró 'CONEXIÓN EXITOSA':");
        System.out.println("     El servidor está funcionando correctamente");
        System.out.println("     Ejecuta: java ClienteUDPConfiable 0.0.0.0 " + ipServidor + " archivo.txt");
        System.out.println();
        System.out.println("   Si hubo timeout pero el paquete se envió:");
        System.out.println("     El servidor podría no estar ejecutándose o");
        System.out.println("     no está respondiendo a mensajes de prueba");
        System.out.println("     Verifica que el servidor esté corriendo");
        System.out.println();
        System.out.println("   Si hubo error en el envío:");
        System.out.println("     Hay problemas de red o firewall");
        System.out.println("     Revisa la configuración de red");
    }
}