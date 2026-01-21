package com.practica1;

import java.net.InetAddress;

public class Sesion {
    private InetAddress ip;
    private int puerto;
    private String nombreArchivo;
    private StringBuilder datos;
    private long secuenciaEsperada;
    private String estado;
    private long secuenciaCliente;

    public Sesion(InetAddress ip, int puerto, String nombreArchivo) {
        this.ip = ip;
        this.puerto = puerto;
        this.nombreArchivo = nombreArchivo;
        this.datos = new StringBuilder();
        this.secuenciaEsperada = 1;
        this.secuenciaCliente = 0;
        this.estado = Configuracion.ESTADO_SYN_RECIBIDO;
    }

    // Getters
    public InetAddress getIp() {
        return ip;
    }

    public int getPuerto() {
        return puerto;
    }

    public String getNombreArchivo() {
        return nombreArchivo;
    }

    public String getDatos() {
        return datos.toString();
    }

    public long getSecuenciaEsperada() {
        return secuenciaEsperada;
    }

    public long getSecuenciaCliente() {
        return secuenciaCliente;
    }

    public String getEstado() {
        return estado;
    }

    public String getClave() {
        return ip.getHostAddress() + ":" + puerto;
    }

    // Setters
    public void setSecuenciaEsperada(long secuencia) {
        this.secuenciaEsperada = secuencia;
    }

    public void setSecuenciaCliente(long secuencia) {
        this.secuenciaCliente = secuencia;
    }

    public void setEstado(String estado) {
        this.estado = estado;
    }

    // Métodos de operación
    public void agregarDatos(String nuevosDatos) {
        // Solo agregar si no es un paquete FIN vacío o línea vacía
        if (nuevosDatos != null && !nuevosDatos.trim().isEmpty() &&
                !nuevosDatos.trim().equals("FIN")) {
            datos.append(nuevosDatos).append("\n");
        }
    }

    public void incrementarSecuencia() {
        secuenciaEsperada++;
    }

    public void incrementarSecuenciaCliente() {
        secuenciaCliente++;
    }

    // Limpiar datos duplicados y vacíos
    public void limpiarDatos() {
        String contenido = datos.toString();
        String[] lineas = contenido.split("\n");
        datos = new StringBuilder();
        for (String linea : lineas) {
            String lineaLimpia = linea.trim();
            if (!lineaLimpia.isEmpty() && !lineaLimpia.equals("FIN")) {
                datos.append(lineaLimpia).append("\n");
            }
        }
    }
}