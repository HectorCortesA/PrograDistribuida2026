package com.practica3;

import java.util.*;

/**
 * Main - Interfaz para crear y usar nodos P2P
 */
public class Main {

    static Map<Integer, NodoP2P> nodos = new HashMap<>();
    static Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("    SISTEMA P2P - MENÚ PRINCIPAL        ");

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
                    crearNodo();
                    break;
                case 2:
                    iniciarNodo();
                    break;
                case 3:
                    registrarPeer();
                    break;
                case 4:
                    subirArchivo();
                    break;
                case 5:
                    descargarArchivo();
                    break;
                case 6:
                    listarArchivos();
                    break;
                case 7:
                    obtenerInfo();
                    break;
                case 8:
                    listarNodos();
                    break;
                case 9:
                    ejecutarDemo();
                    break;
                case 10:
                    System.out.println("\n✓ ¡Hasta luego!\n");
                    ejecutando = false;
                    break;
                default:
                    System.out.println("❌ Opción no válida (1-10)");
            }
        }

        scanner.close();
    }

    static void mostrarMenu() {
        System.out.println("┌─────────────────────────────────────────┐");
        System.out.println("│ 1. Crear nuevo nodo                     │");
        System.out.println("│ 2. Iniciar servidor de un nodo          │");
        System.out.println("│ 3. Registrar peer en un nodo            │");
        System.out.println("│ 4. Subir archivo                        │");
        System.out.println("│ 5. Descargar archivo                    │");
        System.out.println("│ 6. Listar archivos                      │");
        System.out.println("│ 7. Obtener información de archivo       │");
        System.out.println("│ 8. Ver nodos creados                    │");
        System.out.println("│ 9. Ejecutar demostración                │");
        System.out.println("│ 10. Salir                               │");
        System.out.println("└─────────────────────────────────────────┘");
    }

    static void crearNodo() {
        System.out.print("\nNúmero del nodo (ej: 0, 1, 2...): ");
        try {
            int numero = Integer.parseInt(scanner.nextLine().trim());

            if (nodos.containsKey(numero)) {
                System.out.println("❌ El nodo " + numero + " ya existe");
                return;
            }

            NodoP2P nodo = new NodoP2P(numero);
            nodos.put(numero, nodo);
            System.out.println("✓ Nodo " + numero + " creado (puerto " + nodo.getPuerto() + ")");

        } catch (NumberFormatException e) {
            System.out.println("❌ Número de nodo inválido");
        }
    }

    static void iniciarNodo() {
        System.out.print("\nNúmero del nodo a iniciar: ");
        try {
            int numero = Integer.parseInt(scanner.nextLine().trim());

            if (!nodos.containsKey(numero)) {
                System.out.println("❌ El nodo " + numero + " no existe");
                return;
            }

            NodoP2P nodo = nodos.get(numero);
            nodo.iniciar();
            System.out.println("✓ Nodo " + numero + " iniciado (escuchando en puerto " +
                    nodo.getPuerto() + ")");

        } catch (NumberFormatException e) {
            System.out.println("❌ Número de nodo inválido");
        }
    }

    static void registrarPeer() {
        System.out.print("\nNúmero del nodo que registra: ");
        try {
            int numeroNodo = Integer.parseInt(scanner.nextLine().trim());

            if (!nodos.containsKey(numeroNodo)) {
                System.out.println("❌ El nodo " + numeroNodo + " no existe");
                return;
            }

            System.out.print("Número del peer a registrar: ");
            int numeroPeer = Integer.parseInt(scanner.nextLine().trim());

            if (!nodos.containsKey(numeroPeer)) {
                System.out.println("❌ El nodo " + numeroPeer + " no existe");
                return;
            }

            NodoP2P nodo = nodos.get(numeroNodo);
            NodoP2P peer = nodos.get(numeroPeer);

            nodo.registrarPeer("127.0.0.1", peer.getPuerto());
            System.out.println("✓ Peer registrado: Nodo " + numeroNodo +
                    " ahora conoce a Nodo " + numeroPeer);

        } catch (NumberFormatException e) {
            System.out.println("❌ Entrada inválida");
        }
    }

    static void subirArchivo() {
        System.out.print("\nNúmero del nodo: ");
        try {
            int numero = Integer.parseInt(scanner.nextLine().trim());

            if (!nodos.containsKey(numero)) {
                System.out.println("❌ El nodo " + numero + " no existe");
                return;
            }

            NodoP2P nodo = nodos.get(numero);

            System.out.print("Ruta del archivo local: ");
            String ruta = scanner.nextLine().trim();

            System.out.print("Nombre del archivo (sin extensión): ");
            String nombre = scanner.nextLine().trim();

            System.out.print("Extensión: ");
            String extension = scanner.nextLine().trim();

            boolean resultado = nodo.subir("127.0.0.1", nodo.getPuerto(), ruta, nombre, extension);

            if (resultado) {
                System.out.println("✓ Archivo subido exitosamente");
            } else {
                System.out.println("❌ Error al subir el archivo");
            }

        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }

    static void descargarArchivo() {
        System.out.print("\nNúmero del nodo: ");
        try {
            int numero = Integer.parseInt(scanner.nextLine().trim());

            if (!nodos.containsKey(numero)) {
                System.out.println("❌ El nodo " + numero + " no existe");
                return;
            }

            NodoP2P nodo = nodos.get(numero);

            System.out.print("Nombre del archivo (sin extensión): ");
            String nombre = scanner.nextLine().trim();

            System.out.print("Extensión: ");
            String extension = scanner.nextLine().trim();

            System.out.print("Ruta de guardado: ");
            String ruta = scanner.nextLine().trim();

            boolean resultado = nodo.descargar("127.0.0.1", nodo.getPuerto(), nombre, extension, ruta);

            if (resultado) {
                System.out.println("✓ Archivo descargado");
            } else {
                System.out.println("❌ Error al descargar");
            }

        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }

    static void listarArchivos() {
        System.out.print("\nNúmero del nodo: ");
        try {
            int numero = Integer.parseInt(scanner.nextLine().trim());

            if (!nodos.containsKey(numero)) {
                System.out.println("❌ El nodo " + numero + " no existe");
                return;
            }

            NodoP2P nodo = nodos.get(numero);
            P2PUtils.Respuesta respuesta = nodo.solicitarLista("127.0.0.1", nodo.getPuerto());

            if (respuesta.lista != null && !respuesta.lista.isEmpty()) {
                System.out.println("\n✓ Archivos encontrados: " + respuesta.lista.size());
                for (P2PUtils.FileInfo info : respuesta.lista) {
                    System.out.println("  - " + info.getNombreCompleto() +
                            " (" + info.tamaño + " bytes)");
                }
            } else {
                System.out.println("✓ No hay archivos compartidos");
            }

        } catch (NumberFormatException e) {
            System.out.println("❌ Número de nodo inválido");
        }
    }

    static void obtenerInfo() {
        System.out.print("\nNúmero del nodo: ");
        try {
            int numero = Integer.parseInt(scanner.nextLine().trim());

            if (!nodos.containsKey(numero)) {
                System.out.println("❌ El nodo " + numero + " no existe");
                return;
            }

            NodoP2P nodo = nodos.get(numero);

            System.out.print("Nombre del archivo (sin extensión): ");
            String nombre = scanner.nextLine().trim();

            System.out.print("Extensión: ");
            String extension = scanner.nextLine().trim();

            P2PUtils.Respuesta respuesta = nodo.solicitarInfo("127.0.0.1", nodo.getPuerto(),
                    nombre, extension);

            if (respuesta.metadata != null) {
                System.out.println("\n✓ Información del archivo:");
                System.out.println("  " + respuesta.metadata);
                System.out.println("  Autoritativo: " + (respuesta.autoritativo ? "SÍ" : "NO"));
            } else {
                System.out.println("❌ Archivo no encontrado");
            }

        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }

    static void listarNodos() {
        if (nodos.isEmpty()) {
            System.out.println("\n✓ No hay nodos creados");
            return;
        }

        System.out.println("\n✓ Nodos creados:");
        for (Integer numero : nodos.keySet()) {
            NodoP2P nodo = nodos.get(numero);
            System.out.println("  Nodo " + numero + " (Puerto: " + nodo.getPuerto() + ")");
        }
    }

    static void ejecutarDemo() {
        System.out.println("\n╔═════════════════════════════════════════╗");
        System.out.println("║     EJECUTANDO DEMOSTRACIÓN...          ║");
        System.out.println("╚═════════════════════════════════════════╝\n");

        try {
            // Crear 3 nodos
            System.out.println("1. Creando 3 nodos...");
            NodoP2P nodo0 = new NodoP2P(10);
            NodoP2P nodo1 = new NodoP2P(11);
            NodoP2P nodo2 = new NodoP2P(12);

            nodos.put(10, nodo0);
            nodos.put(11, nodo1);
            nodos.put(12, nodo2);

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

            // Crear archivo de prueba
            System.out.println("4. Creando archivo de prueba...");
            String contenido = "Contenido de prueba para demostración P2P";
            java.nio.file.Files.write(java.nio.file.Paths.get("demo.txt"), contenido.getBytes());

            // Subir
            System.out.println("5. Subiendo archivo desde Nodo 10...");
            nodo0.subir("127.0.0.1", nodo0.getPuerto(), "demo.txt", "prueba", "txt");
            Thread.sleep(2000);

            // Listar
            System.out.println("6. Listando archivos desde Nodo 11...");
            P2PUtils.Respuesta resp = nodo1.solicitarLista("127.0.0.1", nodo1.getPuerto());
            if (resp.lista != null) {
                System.out.println("   ✓ Encontrados: " + resp.lista.size() + " archivos");
            }

            // Descargar
            System.out.println("7. Descargando desde Nodo 12...");
            nodo2.descargar("127.0.0.1", nodo2.getPuerto(), "prueba", "txt", "demo_descargado.txt");

            System.out.println("\n✓ Demostración completada\n");

        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
        }
    }
}