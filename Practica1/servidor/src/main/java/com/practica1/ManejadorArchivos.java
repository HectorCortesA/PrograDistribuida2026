package com.practica1;

import java.io.*;
import java.nio.file.*;

public class ManejadorArchivos {

    public static void guardarArchivo(GestorSesiones.SesionCliente sesion) {
        try {
            Path directorio = Paths.get(Configuracion.DIRECTORIO_ARCHIVOS);
            if (!Files.exists(directorio)) {
                Files.createDirectories(directorio);
            }

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
}