package com.practica1;

import java.net.InetAddress;
import java.util.concurrent.*;
import java.util.*;

public class GestorSesiones {
    // Clase interna para representar una sesión de cliente
    public static class SesionCliente {
        public enum Estado {
            SYN_RECEIVED,
            ESTABLISHED,
            FIN_WAIT_1,
            FIN_WAIT_2,
            CLOSED
        }

        private InetAddress ipCliente;
        private int puertoCliente;
        private String nombreArchivo;
        private StringBuilder datos;
        private long secuenciaEsperada;
        private Estado estado;
        private long timestampUltimaActividad;

        public SesionCliente(InetAddress ipCliente, int puertoCliente, String nombreArchivo) {
            this.ipCliente = ipCliente;
            this.puertoCliente = puertoCliente;
            this.nombreArchivo = nombreArchivo;
            this.datos = new StringBuilder();
            this.secuenciaEsperada = 1;
            this.estado = Estado.SYN_RECEIVED;
            this.timestampUltimaActividad = System.currentTimeMillis();
        }

        // Getters
        public InetAddress getIpCliente() {
            return ipCliente;
        }

        public int getPuertoCliente() {
            return puertoCliente;
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

        public Estado getEstado() {
            return estado;
        }

        public long getTimestampUltimaActividad() {
            return timestampUltimaActividad;
        }

        public String getClaveSesion() {
            return ipCliente.getHostAddress() + ":" + puertoCliente;
        }

        // Setters
        public void setSecuenciaEsperada(long secuencia) {
            this.secuenciaEsperada = secuencia;
            actualizarTimestamp();
        }

        public void setEstado(Estado estado) {
            this.estado = estado;
            actualizarTimestamp();
        }

        // Métodos de operación
        public void agregarDatos(String nuevosDatos) {
            datos.append(nuevosDatos);
            actualizarTimestamp();
            if (estado == Estado.SYN_RECEIVED) {
                estado = Estado.ESTABLISHED;
            }
        }

        private void actualizarTimestamp() {
            this.timestampUltimaActividad = System.currentTimeMillis();
        }
    }

    // Mapa para almacenar sesiones activas
    private static ConcurrentHashMap<String, SesionCliente> sesionesActivas = new ConcurrentHashMap<>();

    // Clase interna para limpieza de sesiones
    public static class LimpiadorSesiones implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(Configuracion.TIMEOUT_LIMPIEZA_SESIONES);
                    limpiarSesionesInactivas();
                    System.out.println("Estado del servidor - Sesiones activas: " + getNumeroSesionesActivas());
                } catch (InterruptedException e) {
                    System.out.println("Limpiador de sesiones interrumpido");
                    break;
                }
            }
        }
    }

    // Métodos estáticos para gestión de sesiones

    public static void agregarSesion(String clave, SesionCliente sesion) {
        sesionesActivas.put(clave, sesion);
        System.out.println("Nueva sesión creada: " + clave);
    }

    public static SesionCliente obtenerSesion(String clave) {
        return sesionesActivas.get(clave);
    }

    public static void eliminarSesion(String clave) {
        sesionesActivas.remove(clave);
        System.out.println("Sesión eliminada: " + clave);
    }

    public static void limpiarSesionesInactivas() {
        long tiempoActual = System.currentTimeMillis();
        long tiempoLimite = Configuracion.TIMEOUT_LIMPIEZA_SESIONES;

        List<String> sesionesAEliminar = new ArrayList<>();

        for (String clave : sesionesActivas.keySet()) {
            SesionCliente sesion = sesionesActivas.get(clave);
            long tiempoInactivo = tiempoActual - sesion.getTimestampUltimaActividad();

            if (tiempoInactivo > tiempoLimite) {
                sesionesAEliminar.add(clave);
            }
        }

        for (String clave : sesionesAEliminar) {
            eliminarSesion(clave);
        }

        if (!sesionesAEliminar.isEmpty()) {
            System.out.println("Sesiones eliminadas: " + sesionesAEliminar.size() +
                    ", Sesiones activas: " + sesionesActivas.size());
        }
    }

    public static int getNumeroSesionesActivas() {
        return sesionesActivas.size();
    }
}