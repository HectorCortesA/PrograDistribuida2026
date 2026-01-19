package com.practica1;

public class Configuracion {
    // Configuración del servidor
    public static final int PUERTO_SERVIDOR = 5000;
    public static final int TAMANO_BUFFER = 1024;
    public static final int TIMEOUT_MS = 3000;
    public static final int MAX_REINTENTOS = 5;
    public static final int TIMEOUT_LIMPIEZA_SESIONES = TIMEOUT_MS * 10;
    public static final String DIRECTORIO_ARCHIVOS = "archivos_recibidos";

    // Formatos de mensajes
    public static final String FORMATO_SYN = "SYN";
    public static final String FORMATO_SYN_ACK = "SYN-ACK";
    public static final String FORMATO_ACK = "ACK";
    public static final String FORMATO_DATA = "DATA";
    public static final String FORMATO_FIN = "FIN";
    public static final String FORMATO_ERROR = "ERROR";

    // Método para construir mensajes
    public static String construirMensaje(String tipo, Object... parametros) {
        StringBuilder mensaje = new StringBuilder(tipo);
        for (Object param : parametros) {
            mensaje.append(":").append(param.toString());
        }
        return mensaje.toString();
    }
}