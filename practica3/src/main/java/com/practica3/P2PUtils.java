package com.practica3;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Utilidades compartidas para el sistema P2P
 */
public class P2PUtils {

    // Clase para metadatos de archivo
    public static class FileInfo implements Serializable {
        public String nombre;
        public String extension;
        public long tamaño;
        public LocalDateTime fechaCreacion;
        public LocalDateTime fechaModificacion;
        public int ttl;
        public String propietario;
        public boolean autoritativo;

        public FileInfo(String nombre, String extension, long tamaño,
                LocalDateTime fechaCreacion, LocalDateTime fechaModificacion,
                int ttl, String propietario, boolean autoritativo) {
            this.nombre = nombre;
            this.extension = extension;
            this.tamaño = tamaño;
            this.fechaCreacion = fechaCreacion;
            this.fechaModificacion = fechaModificacion;
            this.ttl = ttl;
            this.propietario = propietario;
            this.autoritativo = autoritativo;
        }

        public String getNombreCompleto() {
            return nombre + (extension != null && !extension.isEmpty() ? "." + extension : "");
        }

        @Override
        public String toString() {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return String.format("Archivo: %s | Tamaño: %d bytes | Creado: %s | " +
                    "Modificado: %s | TTL: %d s | Propietario: %s | Autoritativo: %s",
                    getNombreCompleto(), tamaño, fechaCreacion.format(fmt),
                    fechaModificacion.format(fmt), ttl, propietario,
                    autoritativo ? "SÍ" : "NO");
        }
    }

    // Clase para solicitudes
    public static class Solicitud implements Serializable {
        public enum Tipo {
            GET_INFO, // Pedir info de archivo
            GET_LISTA, // Pedir lista de archivos
            DESCARGAR, // Descargar archivo
            SUBIR, // Subir archivo
            PUBLICAR // Publicar a otros nodos (interno)
        }

        public Tipo tipo;
        public String nombre;
        public String extension;
        public byte[] contenido;
        public FileInfo metadata;

        public Solicitud(Tipo tipo) {
            this.tipo = tipo;
        }

        public Solicitud(Tipo tipo, String nombre, String extension) {
            this.tipo = tipo;
            this.nombre = nombre;
            this.extension = extension;
        }
    }

    // Clase para respuestas
    public static class Respuesta implements Serializable {
        public enum Estado {
            EXITO,
            ARCHIVO_NO_ENCONTRADO,
            NACK,
            ERROR
        }

        public Estado estado;
        public String mensaje;
        public FileInfo metadata;
        public List<FileInfo> lista;
        public byte[] contenido;
        public String ipPropietario;
        public int puertoPropietario;
        public boolean autoritativo;

        public Respuesta(Estado estado) {
            this.estado = estado;
        }

        public Respuesta(Estado estado, String mensaje) {
            this.estado = estado;
            this.mensaje = mensaje;
        }
    }

    // ==================== FUNCIONES DE LOGGING ====================

    /**
     * Registra un evento en el log
     */
    public static void registrarLog(String archivo, String evento) {
        try (FileWriter fw = new FileWriter(archivo, true);
                BufferedWriter bw = new BufferedWriter(fw)) {

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            String linea = String.format("[%s] %s", timestamp, evento);

            bw.write(linea);
            bw.newLine();
            System.out.println(linea);

        } catch (IOException e) {
            System.err.println("Error escribiendo log: " + e.getMessage());
        }
    }

    /**
     * Registra una operación de archivo
     */
    public static void registrarOperacionArchivo(String logFile, String operacion,
            String nombreArchivo, String detalles) {
        String evento = String.format("[ARCHIVO] %s | %s | %s",
                operacion, nombreArchivo, detalles);
        registrarLog(logFile, evento);
    }

    /**
     * Registra una operación de servidor
     */
    public static void registrarOperacionServidor(String logFile, String operacion,
            String nombreArchivo, boolean autoritativo,
            String detalles) {
        String evento = String.format("[SERVIDOR] %s | %s | Autoritativo: %s | %s",
                operacion, nombreArchivo, autoritativo ? "SÍ" : "NO", detalles);
        registrarLog(logFile, evento);
    }

    /**
     * Registra un evento de red
     */
    public static void registrarEventoRed(String logFile, String evento,
            String ip, int puerto, String detalles) {
        String msg = String.format("[RED] %s | %s:%d | %s",
                evento, ip, puerto, detalles);
        registrarLog(logFile, msg);
    }

    // ==================== FUNCIONES DE ARCHIVO ====================

    /**
     * Guarda un archivo en disco
     */
    public static boolean guardarArchivo(String carpetaCompartida, String nombreCompleto,
            byte[] contenido) {
        try {
            File carpeta = new File(carpetaCompartida);
            if (!carpeta.exists()) {
                carpeta.mkdirs();
            }

            File archivo = new File(carpeta, nombreCompleto);
            try (FileOutputStream fos = new FileOutputStream(archivo)) {
                fos.write(contenido);
            }
            return true;
        } catch (IOException e) {
            System.err.println("Error guardando archivo: " + e.getMessage());
            return false;
        }
    }

    /**
     * Carga un archivo del disco
     */
    public static byte[] cargarArchivo(String carpetaCompartida, String nombreCompleto) {
        try {
            File archivo = new File(carpetaCompartida, nombreCompleto);
            if (!archivo.exists()) {
                return null;
            }

            byte[] contenido = new byte[(int) archivo.length()];
            try (FileInputStream fis = new FileInputStream(archivo)) {
                fis.read(contenido);
            }
            return contenido;
        } catch (IOException e) {
            System.err.println("Error cargando archivo: " + e.getMessage());
            return null;
        }
    }

    /**
     * Obtiene información de un archivo
     */
    public static FileInfo obtenerInfoArchivo(String carpetaCompartida, String nombreCompleto,
            String propietario) {
        try {
            File archivo = new File(carpetaCompartida, nombreCompleto);
            if (!archivo.exists()) {
                return null;
            }

            String[] partes = nombreCompleto.split("\\.");
            String nombre = partes[0];
            String extension = partes.length > 1 ? partes[1] : "";

            LocalDateTime ahora = LocalDateTime.now();

            return new FileInfo(
                    nombre,
                    extension,
                    archivo.length(),
                    ahora,
                    ahora,
                    3600, // TTL por defecto: 1 hora
                    propietario,
                    true // Es autoritativo en su propio nodo
            );
        } catch (Exception e) {
            System.err.println("Error obteniendo info: " + e.getMessage());
            return null;
        }
    }

    /**
     * Lista todos los archivos compartidos
     */
    public static List<FileInfo> listarArchivos(String carpetaCompartida, String propietario) {
        List<FileInfo> lista = new ArrayList<>();

        try {
            File carpeta = new File(carpetaCompartida);
            if (!carpeta.exists() || !carpeta.isDirectory()) {
                return lista;
            }

            File[] archivos = carpeta.listFiles();
            if (archivos != null) {
                for (File archivo : archivos) {
                    if (archivo.isFile()) {
                        FileInfo info = obtenerInfoArchivo(carpetaCompartida,
                                archivo.getName(), propietario);
                        if (info != null) {
                            lista.add(info);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error listando archivos: " + e.getMessage());
        }

        return lista;
    }

    /**
     * Elimina un archivo
     */
    public static boolean eliminarArchivo(String carpetaCompartida, String nombreCompleto) {
        try {
            File archivo = new File(carpetaCompartida, nombreCompleto);
            return archivo.delete();
        } catch (Exception e) {
            System.err.println("Error eliminando archivo: " + e.getMessage());
            return false;
        }
    }
}