package com.practica1;

public class ConfiguracionCliente {
    // Configuración del cliente
    public static final int PUERTO_SERVIDOR = 5000;
    public static final int TIMEOUT_RECEPCION = 5000; // 5 segundos
    public static final int MAX_REINTENTOS = 3;
    public static final int TAMANO_BUFFER = 1024;

    // Valores por defecto para pruebas
    public static final String IP_LOCALHOST = "127.0.0.1";
    public static final String ARCHIVO_PRUEBA = "prueba.txt";
}