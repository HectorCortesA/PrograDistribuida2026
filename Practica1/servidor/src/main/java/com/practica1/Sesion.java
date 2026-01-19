package com.practica1;

import java.net.InetAddress;

public class Sesion {
    private InetAddress ip;
    private int puerto;
    private String nombreArchivo;
    private StringBuilder datos;
    private long secuenciaEsperada;
    private String estado;

    public Sesion(InetAddress ip, int puerto, String nombreArchivo) {
        this.ip = ip;
        this.puerto = puerto;
        this.nombreArchivo = nombreArchivo;
        this.datos = new StringBuilder();
        this.secuenciaEsperada = 1;
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

    public void setEstado(String estado) {
        this.estado = estado;
    }

    // Métodos de operación
    public void agregarDatos(String nuevosDatos) {
        datos.append(nuevosDatos);
    }

    public void incrementarSecuencia() {
        secuenciaEsperada++;
    }
}