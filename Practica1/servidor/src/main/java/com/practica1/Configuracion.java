package com.practica1;

public class Configuracion {
    // Configuración del sistema
    public static final int PUERTO_SERVIDOR = 22000;
    public static final int TIMEOUT_RECEPCION = 5000;
    public static final int MAX_REINTENTOS = 3;
    public static final int TAMANO_BUFFER = 1024;

    // Estados de conexión
    public static final String ESTADO_SYN_RECIBIDO = "SYN_RCVD";
    public static final String ESTADO_ESTABLISHED = "ESTABLISHED";
    public static final String ESTADO_FIN_ENVIADO = "FIN_SENT";

    // Tipos de mensajes
    public static final String TIPO_SYN = "SYN";
    public static final String TIPO_SYN_ACK = "SYN-ACK";
    public static final String TIPO_ACK = "ACK";
    public static final String TIPO_DATA = "DATA";
    public static final String TIPO_FIN = "FIN";
    public static final String TIPO_ERROR = "ERROR";
}