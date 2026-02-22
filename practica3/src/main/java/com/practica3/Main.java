package com.practica3;

import java.util.*;

/**
 * Main - Sistema P2P con UN nodo (autoseleccionado)
 * El usuario solo maneja su propio nodo
 */
public class Main {

    static NodoP2P miNodo;
    static Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) throws Exception {

        System.out.println("     SISTEMA P2P - MI NODO             ");

        // Crear el nodo automáticamente
        System.out.print("¿Cuál es el número de MI nodo? (0, 1, 2...): ");
        int numeroNodo = 0;
        try {
            numeroNodo = Integer.parseInt(scanner.nextLine().trim());
        } catch (NumberFormatException e) {
            numeroNodo = 0;
        }

        miNodo = new NodoP2P(numeroNodo);
        miNodo.iniciar();

        System.out.println("\n✓ Mi nodo iniciado: Nodo " + numeroNodo + " (Puerto: " + miNodo.getPuerto() + ")");
        System.out.println("✓ Carpeta compartida: shared_" + numeroNodo + "/");
        System.out.println("✓ Log: nodo_" + numeroNodo + ".log\n");

        Thread.sleep(500);

        boolean ejecutando = true;
        while (ejecutando) {
            mostrarMenu();
            System.out.print("\nSeleccione opción: ");

            int opcion = -1;
            try {
                opcion = Integer.parseInt(scanner.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("❌ Opción no válida");
                continue;
            }

            switch (opcion) {
                case 1:
                    agregarPeer();
                    break;
                case 2:
                    subirArchivo();
                    break;
                case 3:
                    descargarArchivo();
                    break;
                case 4:
                    listarArchivos();
                    break;
                case 5:
                    obtenerInfo();
                    break;
                case 6:
                    verPeers();
                    break;
                case 7:
                    ejecutarDemo();
                    break;
                case 8:
                    System.out.println("\n✓ ¡Hasta luego!\n");
                    ejecutando = false;
                    break;
                default:
                    System.out.println("❌ Opción no válida (1-8)");
            }
        }

        scanner.close();
        miNodo.detener();
    }

    static void mostrarMenu() {

        System.out.println("│ 1. Agregar un peer (otro nodo)         │");
        System.out.println("│ 2. Subir archivo                       │");
        System.out.println("│ 3. Descargar archivo                   │");
        System.out.println("│ 4. Listar archivos                     │");
        System.out.println("│ 5. Obtener información de archivo      │");
        System.out.println("│ 6. Ver mis peers conectados            │");
        System.out.println("│ 7. Ejecutar demostración (3 nodos)     │");
        System.out.println("│ 8. Salir                               │");

    }

    // ======================== OPCIÓN 1: AGREGAR PEER ========================
    static void agregarPeer() {
        System.out.print("\nNúmero del otro nodo (0, 1, 2...): ");
        try {
            int numeroPeer = Integer.parseInt(scanner.nextLine().trim());
            int puertoPeer = 5000 + (numeroPeer * 100);

            miNodo.registrarPeer("127.0.0.1", puertoPeer);
            System.out.println("✓ Peer agregado: Nodo " + numeroPeer + " (127.0.0.1:" + puertoPeer + ")");

        } catch (NumberFormatException e) {
            System.out.println("❌ Número inválido");
        }
    }

    // ======================== OPCIÓN 2: SUBIR ARCHIVO ========================
    static void subirArchivo() {
        System.out.print("\nRuta del archivo (ej: archivo.txt): ");
        String ruta = scanner.nextLine().trim();

        System.out.print("Nombre del archivo (sin extensión): ");
        String nombre = scanner.nextLine().trim();

        System.out.print("Extensión (ej: txt, pdf, etc): ");
        String extension = scanner.nextLine().trim();

        boolean resultado = miNodo.subir("127.0.0.1", miNodo.getPuerto(), ruta, nombre, extension);

        if (resultado) {
            System.out.println("\n✓ Archivo subido exitosamente");
            System.out.println("✓ Publicado a todos mis peers");
        } else {
            System.out.println("\n❌ Error al subir el archivo");
        }
    }

    // ======================== OPCIÓN 3: DESCARGAR ARCHIVO ========================
    static void descargarArchivo() {
        System.out.print("\nNombre del archivo (sin extensión): ");
        String nombre = scanner.nextLine().trim();

        System.out.print("Extensión (ej: txt, pdf, etc): ");
        String extension = scanner.nextLine().trim();

        System.out.print("Ruta de guardado (ej: descargado.txt): ");
        String ruta = scanner.nextLine().trim();

        boolean resultado = miNodo.descargar("127.0.0.1", miNodo.getPuerto(), nombre, extension, ruta);

        if (resultado) {
            System.out.println("\n✓ Archivo descargado: " + ruta);
        } else {
            System.out.println("\n❌ Error al descargar");
        }
    }

    // ======================== OPCIÓN 4: LISTAR ARCHIVOS ========================
    static void listarArchivos() {
        System.out.println("\nObteniendo lista de archivos...");
        P2PUtils.Respuesta respuesta = miNodo.solicitarLista("127.0.0.1", miNodo.getPuerto());

        if (respuesta.lista != null && !respuesta.lista.isEmpty()) {
            System.out.println("\n✓ Archivos compartidos (" + respuesta.lista.size() + "):");
            System.out.println("════════════════════════════════════════");
            for (P2PUtils.FileInfo info : respuesta.lista) {
                System.out.println("\n📄 " + info.getNombreCompleto());
                System.out.println("   Tamaño: " + info.tamaño + " bytes");
                System.out.println("   Propietario: " + info.propietario);
                System.out.println("   Autoritativo: " + (info.autoritativo ? "SÍ ✓" : "NO"));
            }
            System.out.println("\n════════════════════════════════════════");
        } else {
            System.out.println("\n✓ No hay archivos compartidos");
        }
    }

    // ======================== OPCIÓN 5: OBTENER INFO ========================
    static void obtenerInfo() {
        System.out.print("\nNombre del archivo (sin extensión): ");
        String nombre = scanner.nextLine().trim();

        System.out.print("Extensión: ");
        String extension = scanner.nextLine().trim();

        P2PUtils.Respuesta respuesta = miNodo.solicitarInfo("127.0.0.1", miNodo.getPuerto(),
                nombre, extension);

        if (respuesta.metadata != null) {
            System.out.println("\n✓ Información del archivo:");
            System.out.println("════════════════════════════════════════");
            System.out.println("📄 Nombre: " + respuesta.metadata.getNombreCompleto());
            System.out.println("   Tamaño: " + respuesta.metadata.tamaño + " bytes");
            System.out.println("   Propietario: " + respuesta.metadata.propietario);
            System.out.println("   Autoritativo: " + (respuesta.autoritativo ? "SÍ ✓" : "NO"));
            System.out.println("   TTL: " + respuesta.metadata.ttl + " segundos");
            System.out.println("════════════════════════════════════════");
        } else {
            System.out.println("\n❌ Archivo no encontrado");
        }
    }

    // ======================== OPCIÓN 6: VER PEERS ========================
    static void verPeers() {
        System.out.println("\n✓ Mis peers conectados:");
        System.out.println("════════════════════════════════════════");
        // Los peers se ven en los logs
        System.out.println("Revise el archivo nodo_X.log para ver los peers registrados");
        System.out.println("════════════════════════════════════════");
    }

    // ======================== OPCIÓN 7: DEMO ========================
    static void ejecutarDemo() {
        System.out.println("\n╔════════════════════════════════════════╗");
        System.out.println("║    DEMOSTRACIÓN CON 3 NODOS             ║");
        System.out.println("╚════════════════════════════════════════╝\n");

        try {
            // Crear 3 nodos
            System.out.println("1. Creando 3 nodos...");
            NodoP2P nodo0 = new NodoP2P(20);
            NodoP2P nodo1 = new NodoP2P(21);
            NodoP2P nodo2 = new NodoP2P(22);

            // Iniciar
            System.out.println("2. Iniciando servidores...");
            nodo0.iniciar();
            nodo1.iniciar();
            nodo2.iniciar();
            Thread.sleep(1000);

            // Registrar peers
            System.out.println("3. Registrando peers...");
            nodo0.registrarPeer("127.0.0.1", nodo1.getPuerto());
            nodo0.registrarPeer("127.0.0.1", nodo2.getPuerto());
            nodo1.registrarPeer("127.0.0.1", nodo0.getPuerto());
            nodo1.registrarPeer("127.0.0.1", nodo2.getPuerto());
            nodo2.registrarPeer("127.0.0.1", nodo0.getPuerto());
            nodo2.registrarPeer("127.0.0.1", nodo1.getPuerto());

            // Crear archivo
            System.out.println("4. Creando archivo de prueba...");
            String contenido = "Contenido de prueba para demostración P2P";
            java.nio.file.Files.write(java.nio.file.Paths.get("demo.txt"), contenido.getBytes());

            // Subir
            System.out.println("5. NODO 20 sube archivo...");
            nodo0.subir("127.0.0.1", nodo0.getPuerto(), "demo.txt", "prueba", "txt");
            Thread.sleep(2000);

            // Listar
            System.out.println("6. NODO 21 lista archivos...");
            P2PUtils.Respuesta resp = nodo1.solicitarLista("127.0.0.1", nodo1.getPuerto());
            if (resp.lista != null) {
                System.out.println("   ✓ Encontrados: " + resp.lista.size() + " archivos");
                for (P2PUtils.FileInfo f : resp.lista) {
                    System.out.println("   - " + f.getNombreCompleto());
                }
            }

            // Descargar
            System.out.println("7. NODO 22 descarga...");
            nodo2.descargar("127.0.0.1", nodo2.getPuerto(), "prueba", "txt", "demo_descargado.txt");

            System.out.println("\n✓ Demostración completada");
            System.out.println("✓ Revise los archivos: shared_20/, shared_21/, shared_22/");
            System.out.println("✓ Revise los logs: nodo_20.log, nodo_21.log, nodo_22.log\n");

            nodo0.detener();
            nodo1.detener();
            nodo2.detener();

        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }
}