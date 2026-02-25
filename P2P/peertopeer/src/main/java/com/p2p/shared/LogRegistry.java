package com.p2p.shared;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LogRegistry {
    private static final String LOG_FILE = "p2p_activity.log";
    private static final int MAX_LOG_ENTRIES = 1000;

    private final PrintWriter logWriter;
    private final ConcurrentLinkedQueue<LogEntry> recentLogs;
    private final DateTimeFormatter formatter;

    public LogRegistry() {
        this.recentLogs = new ConcurrentLinkedQueue<>();
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new FileWriter(LOG_FILE, true));
        } catch (IOException e) {
            System.err.println("No se pudo abrir archivo de log: " + e.getMessage());
        }
        this.logWriter = writer;

        // Log inicial
        info("System", "Sistema de logs iniciado");
    }

    public void log(String level, String component, String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        String logEntry = String.format("[%s] %-7s %-15s: %s",
                timestamp, level, component, message);

        // Mostrar en consola
        if (level.equals("ERROR")) {
            System.err.println(logEntry);
        } else {
            System.out.println(logEntry);
        }

        // Guardar en archivo
        if (logWriter != null) {
            logWriter.println(logEntry);
            logWriter.flush();
        }

        // Mantener logs recientes
        LogEntry entry = new LogEntry(timestamp, level, component, message);
        recentLogs.offer(entry);

        // Limitar tamaño
        while (recentLogs.size() > MAX_LOG_ENTRIES) {
            recentLogs.poll();
        }
    }

    public void info(String component, String message) {
        log("INFO", component, message);
    }

    public void warning(String component, String message) {
        log("WARNING", component, message);
    }

    public void error(String component, String message) {
        log("ERROR", component, message);
    }

    public void debug(String component, String message) {
        // Solo mostrar si está habilitado debug
        if (Boolean.parseBoolean(System.getProperty("debug", "false"))) {
            log("DEBUG", component, message);
        }
    }

    public void success(String component, String message) {
        log("SUCCESS", component, "✅ " + message);
    }

    public List<LogEntry> getRecentLogs(int count) {
        return recentLogs.stream()
                .limit(count)
                .toList();
    }

    public List<LogEntry> getLogsByLevel(String level, int count) {
        return recentLogs.stream()
                .filter(entry -> entry.level().equals(level))
                .limit(count)
                .toList();
    }

    public List<LogEntry> getLogsByComponent(String component, int count) {
        return recentLogs.stream()
                .filter(entry -> entry.component().equals(component))
                .limit(count)
                .toList();
    }

    public void exportLogs(String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            for (LogEntry entry : recentLogs) {
                writer.println(entry);
            }
            info("LogRegistry", "Logs exportados a: " + filename);
        } catch (IOException e) {
            error("LogRegistry", "Error exportando logs: " + e.getMessage());
        }
    }

    public void close() {
        info("System", "Sistema de logs detenido");
        if (logWriter != null) {
            logWriter.close();
        }
    }

    // Record para entradas de log
    public record LogEntry(String timestamp, String level,
            String component, String message) {
        @Override
        public String toString() {
            return String.format("[%s] %s %s: %s",
                    timestamp, level, component, message);
        }
    }
}