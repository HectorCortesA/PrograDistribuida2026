package com.practica1;

import java.util.concurrent.ConcurrentHashMap;

public class GestorSesiones {
    // Mapa para sesiones concurrentes
    private static ConcurrentHashMap<String, Sesion> sesiones = new ConcurrentHashMap<>();

    // Métodos estáticos para gestión de sesiones
    public static void agregarSesion(String clave, Sesion sesion) {
        sesiones.put(clave, sesion);
        System.out.println("Sesión creada: " + clave);
    }

    public static Sesion obtenerSesion(String clave) {
        return sesiones.get(clave);
    }

    public static void eliminarSesion(String clave) {
        sesiones.remove(clave);
        System.out.println("Sesión eliminada: " + clave);
    }

    public static boolean existeSesion(String clave) {
        return sesiones.containsKey(clave);
    }
}