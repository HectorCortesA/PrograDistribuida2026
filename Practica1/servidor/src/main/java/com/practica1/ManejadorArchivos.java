package com.practica1;

import java.io.*;
import java.nio.file.*;

public class ManejadorArchivos {

    public static void guardarArchivo(Sesion sesion) {
        try {
            // Crear directorio si no existe
            Files.createDirectories(Paths.get(Configuracion.DIRECTORIO_ARCHIVOS));

            // Nombre único para cada cliente
            String nombreArchivo = "cliente_" +
                    sesion.getIp().getHostAddress().replace('.', '_') + "_" +
                    sesion.getNombreArchivo();

            // Guardar archivo
            Path ruta = Paths.get(Configuracion.DIRECTORIO_ARCHIVOS, nombreArchivo);
            Files.write(ruta, sesion.getDatos().getBytes());

            System.out.println("Archivo guardado: " + ruta);

        } catch (IOException e) {
            System.err.println("Error guardando archivo: " + e.getMessage());
        }
    }
}