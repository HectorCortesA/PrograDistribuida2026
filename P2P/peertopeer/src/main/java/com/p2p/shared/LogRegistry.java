package com.p2p.shared;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * LogRegistry — una sola instancia real en toda la JVM.
 * Todos los "new LogRegistry()" comparten el mismo estado interno.
 * No hay deadlock porque synchronized está sobre el objeto singleton, no sobre
 * cada instancia wrapper.
 */
public class LogRegistry {

    // ── Estado real, compartido por todas las instancias ─────────────────
    private static final String LOG_FILE = "p2p_activity.log";
    private static final int MAX_ENTRIES = 1000;

    private static PrintWriter logWriter;
    private static final ConcurrentLinkedQueue<LogEntry> recentLogs = new ConcurrentLinkedQueue<>();
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static boolean initialized = false;

    // ── Constructor (todos los "new LogRegistry()" llegan aquí) ──────────
    public LogRegistry() {
        synchronized (LogRegistry.class) {
            if (!initialized) {
                initialized = true;
                try {
                    logWriter = new PrintWriter(new FileWriter(LOG_FILE, true));
                } catch (IOException e) {
                    System.err.println("No se pudo abrir log: " + e.getMessage());
                }
                // Mensaje de inicio — solo una vez
                doLog("INFO   ", "System", "Sistema de logs iniciado");
            }
        }
    }

    // ── API pública ───────────────────────────────────────────────────────
    public void info(String component, String message) {
        doLog("INFO   ", component, message);
    }

    public void warning(String component, String message) {
        doLog("WARNING", component, message);
    }

    public void error(String component, String message) {
        doLog("ERROR  ", component, message);
    }

    private static synchronized void doLog(String level, String component, String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        String line = String.format("[%s] %s %-15s: %s", timestamp, level, component, message);
        System.out.println(line);
        if (logWriter != null) {
            logWriter.println(line);
            logWriter.flush();
        }
        recentLogs.offer(new LogEntry(timestamp, level, component, message));
        while (recentLogs.size() > MAX_ENTRIES) recentLogs.poll();
    }

    public List<LogEntry> getRecentLogs() {
        return new ArrayList<>(recentLogs);
    }

    public static synchronized void close() {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
    }

    public static class LogEntry {
        public final String timestamp, level, component, message;
        public LogEntry(String ts, String lv, String comp, String msg) {
            timestamp = ts; level = lv; component = comp; message = msg;
        }
    }
}
