package com.practica3;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Nodo P2P - Funciona como cliente y servidor simultáneamente
 * Cada instancia es un nodo independiente que puede actuar como ambos
 */
public class NodoP2P {

    private int numeroNodo;
    private int puerto;
    private String carpetaCompartida;
    private String archivoLog;
    private Map<String, P2PUtils.FileInfo> indiceArchivos;
    private List<String> peersConocidos;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean ejecutando;

    // Constantes
    private static final int PUERTO_BASE = 5000;
    private static final int INCREMENTO_PUERTO = 100;
    private static final int TTL_DEFECTO = 3600;
    private static final int TIMEOUT_SOCKET = 5000;

    public NodoP2P(int numeroNodo) {
        this.numeroNodo = numeroNodo;
        this.puerto = PUERTO_BASE + (numeroNodo * INCREMENTO_PUERTO);
        this.carpetaCompartida = "shared_" + numeroNodo;
        this.archivoLog = "nodo_" + numeroNodo + ".log";
        this.indiceArchivos = Collections.synchronizedMap(new HashMap<>());
        this.peersConocidos = Collections.synchronizedList(new ArrayList<>());
        this.threadPool = Executors.newFixedThreadPool(10);
        this.ejecutando = false;

        // Crear carpeta compartida
        new File(carpetaCompartida).mkdirs();

        log("=== NODO P2P #" + numeroNodo + " INICIADO ===");
        log("Puerto: " + puerto + " | Carpeta: " + carpetaCompartida);
    }

    /**
     * Inicia el servidor del nodo (escucha conexiones entrantes)
     */
    public void iniciar() {
        try {
            serverSocket = new ServerSocket(puerto);
            ejecutando = true;

            log("Servidor escuchando en puerto " + puerto);

            // Thread para aceptar conexiones
            new Thread(() -> aceptarConexiones()).start();

            // Thread para sincronizar con otros peers
            new Thread(() -> sincronizarPeriodicamente()).start();

        } catch (IOException e) {
            log("ERROR al iniciar servidor: " + e.getMessage());
        }
    }

    /**
     * Acepta conexiones de otros nodos
     */
    private void aceptarConexiones() {
        while (ejecutando) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(TIMEOUT_SOCKET);
                threadPool.execute(() -> procesarConexion(socket));
            } catch (IOException e) {
                if (ejecutando) {
                    log("ERROR aceptando conexión: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Procesa una conexión entrante (maneja solicitud y envía respuesta)
     */
    private void procesarConexion(Socket socket) {
        try {
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());

            // Leer solicitud
            P2PUtils.Solicitud solicitud = (P2PUtils.Solicitud) ois.readObject();

            // Procesar y responder
            P2PUtils.Respuesta respuesta = procesarSolicitud(solicitud);

            // Enviar respuesta
            oos.writeObject(respuesta);
            oos.flush();

            ois.close();
            oos.close();

        } catch (Exception e) {
            log("ERROR procesando conexión: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                log("ERROR cerrando socket: " + e.getMessage());
            }
        }
    }

    /**
     * Procesa una solicitud del cliente
     */
    private P2PUtils.Respuesta procesarSolicitud(P2PUtils.Solicitud solicitud) {
        P2PUtils.Respuesta respuesta = new P2PUtils.Respuesta(P2PUtils.Respuesta.Estado.EXITO);

        switch (solicitud.tipo) {
            case GET_INFO:
                return manejarGetInfo(solicitud);

            case GET_LISTA:
                return manejarGetLista(solicitud);

            case DESCARGAR:
                return manejarDescargar(solicitud);

            case SUBIR:
                return manejarSubir(solicitud);

            case PUBLICAR:
                return manejarPublicar(solicitud);

            default:
                respuesta.estado = P2PUtils.Respuesta.Estado.ERROR;
                respuesta.mensaje = "Tipo de solicitud desconocido";
                return respuesta;
        }
    }

    /**
     * Maneja solicitud GET_INFO
     */
    private P2PUtils.Respuesta manejarGetInfo(P2PUtils.Solicitud solicitud) {
        P2PUtils.Respuesta respuesta = new P2PUtils.Respuesta(P2PUtils.Respuesta.Estado.EXITO);

        String nombreCompleto = solicitud.nombre +
                (solicitud.extension != null && !solicitud.extension.isEmpty() ? "." + solicitud.extension : "");

        P2PUtils.FileInfo info = indiceArchivos.get(nombreCompleto);

        if (info != null) {
            respuesta.metadata = info;
            respuesta.autoritativo = info.autoritativo;
            P2PUtils.registrarOperacionArchivo(archivoLog, "GET_INFO_OK",
                    nombreCompleto, "Autoritativo: " + info.autoritativo);
        } else {
            respuesta.estado = P2PUtils.Respuesta.Estado.NACK;
            respuesta.mensaje = "Archivo no encontrado";
            respuesta.autoritativo = false;
            P2PUtils.registrarOperacionArchivo(archivoLog, "GET_INFO_NACK",
                    nombreCompleto, "No encontrado");
        }

        return respuesta;
    }

    /**
     * Maneja solicitud GET_LISTA
     */
    private P2PUtils.Respuesta manejarGetLista(P2PUtils.Solicitud solicitud) {
        P2PUtils.Respuesta respuesta = new P2PUtils.Respuesta(P2PUtils.Respuesta.Estado.EXITO);
        respuesta.lista = new ArrayList<>(indiceArchivos.values());
        respuesta.autoritativo = true;

        P2PUtils.registrarOperacionArchivo(archivoLog, "GET_LISTA",
                "N/A", "Archivos devueltos: " + respuesta.lista.size());

        return respuesta;
    }

    /**
     * Maneja solicitud DESCARGAR
     */
    private P2PUtils.Respuesta manejarDescargar(P2PUtils.Solicitud solicitud) {
        P2PUtils.Respuesta respuesta = new P2PUtils.Respuesta(P2PUtils.Respuesta.Estado.EXITO);

        String nombreCompleto = solicitud.nombre +
                (solicitud.extension != null && !solicitud.extension.isEmpty() ? "." + solicitud.extension : "");

        byte[] contenido = P2PUtils.cargarArchivo(carpetaCompartida, nombreCompleto);

        if (contenido != null) {
            respuesta.contenido = contenido;
            respuesta.autoritativo = true;
            P2PUtils.registrarOperacionArchivo(archivoLog, "DESCARGAR_OK",
                    nombreCompleto, "Bytes: " + contenido.length);
        } else {
            respuesta.estado = P2PUtils.Respuesta.Estado.ARCHIVO_NO_ENCONTRADO;
            respuesta.mensaje = "Archivo no encontrado en servidor";
            respuesta.autoritativo = false;
            P2PUtils.registrarOperacionArchivo(archivoLog, "DESCARGAR_NACK",
                    nombreCompleto, "No encontrado");
        }

        return respuesta;
    }

    /**
     * Maneja solicitud SUBIR (archivo nuevo)
     */
    private P2PUtils.Respuesta manejarSubir(P2PUtils.Solicitud solicitud) {
        P2PUtils.Respuesta respuesta = new P2PUtils.Respuesta(P2PUtils.Respuesta.Estado.EXITO);

        String nombreCompleto = solicitud.nombre +
                (solicitud.extension != null && !solicitud.extension.isEmpty() ? "." + solicitud.extension : "");

        if (P2PUtils.guardarArchivo(carpetaCompartida, nombreCompleto, solicitud.contenido)) {
            // Crear metadata como AUTORITATIVA
            P2PUtils.FileInfo metadata = P2PUtils.obtenerInfoArchivo(
                    carpetaCompartida, nombreCompleto, "NODO_" + numeroNodo);

            indiceArchivos.put(nombreCompleto, metadata);

            // Publicar a otros nodos
            publicarAOtrosNodos(metadata);

            respuesta.mensaje = "Archivo subido exitosamente";
            P2PUtils.registrarOperacionArchivo(archivoLog, "SUBIR_OK",
                    nombreCompleto, "Bytes: " + solicitud.contenido.length);
        } else {
            respuesta.estado = P2PUtils.Respuesta.Estado.ERROR;
            respuesta.mensaje = "Error guardando archivo";
            P2PUtils.registrarOperacionArchivo(archivoLog, "SUBIR_ERROR",
                    nombreCompleto, "Error de I/O");
        }

        return respuesta;
    }

    /**
     * Maneja solicitud PUBLICAR (recibir publicación de otro nodo)
     */
    private P2PUtils.Respuesta manejarPublicar(P2PUtils.Solicitud solicitud) {
        P2PUtils.Respuesta respuesta = new P2PUtils.Respuesta(P2PUtils.Respuesta.Estado.EXITO);

        String nombreCompleto = solicitud.metadata.getNombreCompleto();

        // Agregar al índice pero COMO NO-AUTORITATIVO
        P2PUtils.FileInfo copia = new P2PUtils.FileInfo(
                solicitud.metadata.nombre,
                solicitud.metadata.extension,
                solicitud.metadata.tamaño,
                solicitud.metadata.fechaCreacion,
                solicitud.metadata.fechaModificacion,
                solicitud.metadata.ttl,
                solicitud.metadata.propietario,
                false // NO es autoritativo aquí
        );

        indiceArchivos.put(nombreCompleto, copia);

        P2PUtils.registrarOperacionServidor(archivoLog, "PUBLICACION_RECIBIDA",
                nombreCompleto, false, "De: " + solicitud.metadata.propietario);

        return respuesta;
    }

    /**
     * Publica un archivo a otros nodos conocidos
     */
    private void publicarAOtrosNodos(P2PUtils.FileInfo metadata) {
        for (String peer : peersConocidos) {
            new Thread(() -> {
                try {
                    String[] partes = peer.split(":");
                    String ip = partes[0];
                    int puerto = Integer.parseInt(partes[1]);

                    Socket socket = new Socket(ip, puerto);
                    socket.setSoTimeout(TIMEOUT_SOCKET);
                    ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                    ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

                    P2PUtils.Solicitud solicitud = new P2PUtils.Solicitud(P2PUtils.Solicitud.Tipo.PUBLICAR);
                    solicitud.metadata = metadata;

                    oos.writeObject(solicitud);
                    oos.flush();

                    P2PUtils.Respuesta respuesta = (P2PUtils.Respuesta) ois.readObject();

                    oos.close();
                    ois.close();
                    socket.close();

                } catch (Exception e) {
                    P2PUtils.registrarEventoRed(archivoLog, "PUBLICACION_FALLO",
                            peer.split(":")[0], Integer.parseInt(peer.split(":")[1]),
                            e.getMessage());
                }
            }).start();
        }
    }

    /**
     * Sincroniza periódicamente con otros nodos
     */
    private void sincronizarPeriodicamente() {
        while (ejecutando) {
            try {
                Thread.sleep(10000); // Cada 10 segundos

                // Publicar todos nuestros archivos autoritativos
                for (P2PUtils.FileInfo info : indiceArchivos.values()) {
                    if (info.autoritativo) {
                        publicarAOtrosNodos(info);
                    }
                }

                log("Sincronización completada");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Registra un peer conocido
     */
    public void registrarPeer(String ip, int puerto) {
        String peer = ip + ":" + puerto;
        if (!peersConocidos.contains(peer) && puerto != this.puerto) {
            peersConocidos.add(peer);
            P2PUtils.registrarEventoRed(archivoLog, "PEER_REGISTRADO",
                    ip, puerto, "Nuevo peer agregado");
        }
    }

    /**
     * CLIENTE: Solicita información de un archivo
     */
    public P2PUtils.Respuesta solicitarInfo(String ip, int puerto, String nombre, String extension) {
        try {
            Socket socket = new Socket(ip, puerto);
            socket.setSoTimeout(TIMEOUT_SOCKET);
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

            P2PUtils.Solicitud solicitud = new P2PUtils.Solicitud(
                    P2PUtils.Solicitud.Tipo.GET_INFO, nombre, extension);

            oos.writeObject(solicitud);
            oos.flush();

            P2PUtils.Respuesta respuesta = (P2PUtils.Respuesta) ois.readObject();

            oos.close();
            ois.close();
            socket.close();

            return respuesta;
        } catch (Exception e) {
            log("ERROR solicitando info: " + e.getMessage());
            P2PUtils.Respuesta respuesta = new P2PUtils.Respuesta(
                    P2PUtils.Respuesta.Estado.ERROR, e.getMessage());
            return respuesta;
        }
    }

    /**
     * CLIENTE: Solicita lista de archivos
     */
    public P2PUtils.Respuesta solicitarLista(String ip, int puerto) {
        try {
            Socket socket = new Socket(ip, puerto);
            socket.setSoTimeout(TIMEOUT_SOCKET);
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

            P2PUtils.Solicitud solicitud = new P2PUtils.Solicitud(P2PUtils.Solicitud.Tipo.GET_LISTA);

            oos.writeObject(solicitud);
            oos.flush();

            P2PUtils.Respuesta respuesta = (P2PUtils.Respuesta) ois.readObject();

            oos.close();
            ois.close();
            socket.close();

            return respuesta;
        } catch (Exception e) {
            log("ERROR solicitando lista: " + e.getMessage());
            P2PUtils.Respuesta respuesta = new P2PUtils.Respuesta(
                    P2PUtils.Respuesta.Estado.ERROR, e.getMessage());
            return respuesta;
        }
    }

    /**
     * CLIENTE: Descarga un archivo
     */
    public boolean descargar(String ip, int puerto, String nombre, String extension, String rutaGuardado) {
        try {
            Socket socket = new Socket(ip, puerto);
            socket.setSoTimeout(TIMEOUT_SOCKET);
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

            P2PUtils.Solicitud solicitud = new P2PUtils.Solicitud(
                    P2PUtils.Solicitud.Tipo.DESCARGAR, nombre, extension);

            oos.writeObject(solicitud);
            oos.flush();

            P2PUtils.Respuesta respuesta = (P2PUtils.Respuesta) ois.readObject();

            oos.close();
            ois.close();
            socket.close();

            if (respuesta.contenido != null) {
                Files.write(Paths.get(rutaGuardado), respuesta.contenido);
                log("✓ Archivo descargado: " + rutaGuardado);
                return true;
            } else {
                log("✗ Error descargando: " + respuesta.mensaje);
                return false;
            }
        } catch (Exception e) {
            log("ERROR descargando: " + e.getMessage());
            return false;
        }
    }

    /**
     * CLIENTE: Sube un archivo
     */
    public boolean subir(String ip, int puerto, String rutaLocal, String nombre, String extension) {
        try {
            byte[] contenido = Files.readAllBytes(Paths.get(rutaLocal));

            Socket socket = new Socket(ip, puerto);
            socket.setSoTimeout(TIMEOUT_SOCKET);
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

            P2PUtils.Solicitud solicitud = new P2PUtils.Solicitud(
                    P2PUtils.Solicitud.Tipo.SUBIR, nombre, extension);
            solicitud.contenido = contenido;

            oos.writeObject(solicitud);
            oos.flush();

            P2PUtils.Respuesta respuesta = (P2PUtils.Respuesta) ois.readObject();

            oos.close();
            ois.close();
            socket.close();

            if (respuesta.estado == P2PUtils.Respuesta.Estado.EXITO) {
                log("✓ Archivo subido: " + nombre + "." + extension);
                return true;
            } else {
                log("✗ Error subiendo: " + respuesta.mensaje);
                return false;
            }
        } catch (Exception e) {
            log("ERROR subiendo: " + e.getMessage());
            return false;
        }
    }

    /**
     * Detiene el nodo
     */
    public void detener() {
        ejecutando = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            threadPool.shutdownNow();
            log("Nodo detenido");
        } catch (IOException e) {
            log("ERROR deteniendo: " + e.getMessage());
        }
    }

    /**
     * Registra mensaje en log
     */
    private void log(String mensaje) {
        P2PUtils.registrarLog(archivoLog, mensaje);
    }

    public int getPuerto() {
        return puerto;
    }

    public int getNumero() {
        return numeroNodo;
    }
}
