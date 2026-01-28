package com.practica2;

public class ConfiguracionCliente {
    // ===== CONFIGURACIÓN RED =====
    public static final int PUERTO_SERVIDOR = 22000;
    public static final int TIMEOUT_CONEXION = 5000;      // 5 segundos
    public static final int TIMEOUT_DATOS = 15000;        // 15 segundos
    public static final int TIMEOUT_CIFRADO = 2000;       // 2 segundos
    
    // ===== CONFIGURACIÓN TRANSFERENCIA =====
    public static final int MAX_REINTENTOS = 3;
    public static final int BUFFER_SIZE = 65507;
    public static final String SUFIJO_RECIBIDO = "_recibido.txt";
    
    // ===== CONFIGURACIÓN CIFRADO TLS =====
    public static final String ALGORITMO_RSA = "RSA";
    public static final String ALGORITMO_AES = "AES";
    public static final String CIPHER_RSA = "RSA/ECB/PKCS1Padding";
    public static final String CIPHER_AES = "AES";
    public static final int TAMANIO_CLAVE_RSA = 2048;      // bits
    public static final int TAMANIO_CLAVE_AES = 256;       // bits
    
    // ===== CONFIGURACIÓN CLAVES COMPARTIDAS =====
    public static final long TIEMPO_EXPIRACION_CLAVE = 300000; // 5 minutos
    
    // ===== TIPOS DE MENSAJES =====
    public static final String TIPO_CLIENTHELLO = "CLIENTHELLO";
    public static final String TIPO_SERVERHELLO = "SERVERHELLO";
    public static final String TIPO_RENEGOCIAR = "RENEGOCIAR";
    public static final String TIPO_SYN = "SYN";
    public static final String TIPO_SYN_ACK = "SYN-ACK";
    public static final String TIPO_ACK = "ACK";
    public static final String TIPO_DATA = "DATA";
    public static final String TIPO_FIN = "FIN";
    public static final String TIPO_FIN_DATA = "FIN-DATA";
    public static final String TIPO_ERROR = "ERROR";
    
    // ===== ESTADOS CONEXIÓN =====
    public static final String ESTADO_INICIAL = "INICIAL";
    public static final String ESTADO_HANDSHAKE_TLS = "HANDSHAKE_TLS";
    public static final String ESTADO_HANDSHAKE_3W = "HANDSHAKE_3W";
    public static final String ESTADO_CONECTADO = "CONECTADO";
    public static final String ESTADO_TRANSFIRIENDO = "TRANSFIRIENDO";
    public static final String ESTADO_CERRANDO = "CERRANDO";
    public static final String ESTADO_CERRADO = "CERRADO";
}