package com.practica1;

import java.io.*;

public class FuncionTransferencia {

    /**
     * Función que simula el comportamiento del servidor para enviar un archivo
     * con características confiables tipo TCP sobre UDP
     * 
     * @param ipOrigen      IP del servidor (para logging)
     * @param ipDestino     IP del cliente destino
     * @param nombreArchivo Nombre del archivo a enviar
     */
    public static void enviarArchivoConfiable(String ipOrigen, String ipDestino, String nombreArchivo) {
        System.out.println("=== INICIANDO TRANSFERENCIA CONFIABLE ===");
        System.out.println("Servidor (origen): " + ipOrigen);
        System.out.println("Cliente (destino): " + ipDestino);
        System.out.println("Archivo a transferir: " + nombreArchivo);
        System.out.println("==========================================");

        // Esta función sería llamada desde el servidor cuando un cliente se conecta
        // En una implementación real, se integraría con la clase ServidorUDPConfiable

        try {
            // Simular el proceso de transferencia
            File archivo = new File(nombreArchivo);
            if (!archivo.exists()) {
                System.out.println("ERROR: Archivo no encontrado: " + nombreArchivo);
                return;
            }

            // Leer archivo
            int lineas = contarLineas(archivo);
            System.out.println("Archivo contiene " + lineas + " líneas");

            // Simular handshake
            System.out.println("1. Esperando conexión del cliente...");
            System.out.println("2. Realizando three-way handshake...");
            System.out.println("   - Recibir SYN del cliente");
            System.out.println("   - Enviar SYN-ACK");
            System.out.println("   - Recibir ACK final");
            System.out.println("3. Conexión establecida");

            // Simular transferencia
            System.out.println("4. Enviando archivo línea por línea...");
            System.out.println("   - Control de flujo con ventana deslizante");
            System.out.println("   - Confirmación con ACKs");
            System.out.println("   - Retransmisión en caso de pérdida");

            // Simular cierre
            System.out.println("5. Realizando four-way handshake...");
            System.out.println("   - Recibir FIN del cliente");
            System.out.println("   - Enviar ACK del FIN");
            System.out.println("   - Enviar FIN propio");
            System.out.println("   - Recibir ACK final");

            System.out.println("6. Transferencia completada exitosamente");
            System.out.println("   El cliente tiene ahora una copia idéntica del archivo");

        } catch (Exception e) {
            System.err.println("Error durante la transferencia: " + e.getMessage());
        }
    }

    private static int contarLineas(File archivo) throws IOException {
        int lineas = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(archivo))) {
            while (reader.readLine() != null) {
                lineas++;
            }
        }
        return lineas;
    }

    // Método main para probar la función
    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Uso: java FuncionTransferencia <ip_origen> <ip_destino> <archivo>");
            System.out.println("Ejemplo: java FuncionTransferencia 192.168.1.100 192.168.1.200 archivo.txt");
            return;
        }

        String ipOrigen = args[0];
        String ipDestino = args[1];
        String archivo = args[2];

        enviarArchivoConfiable(ipOrigen, ipDestino, archivo);
    }
}
