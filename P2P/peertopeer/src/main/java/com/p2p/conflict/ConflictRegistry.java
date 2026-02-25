package com.p2p.conflict;

import com.p2p.shared.LogRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ConflictRegistry {
    private final Map<String, ConflictEntry> activeConflicts;
    private final Map<String, List<BorrowRecord>> borrowRecords;
    private final LogRegistry logRegistry;
    private final AtomicInteger conflictCounter;

    public ConflictRegistry() {
        this.activeConflicts = new ConcurrentHashMap<>();
        this.borrowRecords = new ConcurrentHashMap<>();
        this.logRegistry = new LogRegistry();
        this.conflictCounter = new AtomicInteger(0);
    }

    public void registerBorrow(String filename, String borrowerId) {
        List<BorrowRecord> records = borrowRecords.computeIfAbsent(filename,
                k -> Collections.synchronizedList(new ArrayList<>()));

        BorrowRecord record = new BorrowRecord(borrowerId, System.currentTimeMillis());
        records.add(record);

        logRegistry.info("ConflictRegistry", "Préstamo registrado: " + filename +
                " para " + borrowerId);

        // Verificar si hay conflicto (múltiples prestatarios activos)
        detectConflict(filename);
    }

    public void returnBorrow(String filename, String borrowerId) {
        List<BorrowRecord> records = borrowRecords.get(filename);
        if (records != null) {
            records.removeIf(r -> r.getBorrowerId().equals(borrowerId));
            logRegistry.info("ConflictRegistry", "Devolución registrada: " + filename +
                    " de " + borrowerId);

            // Si no quedan prestatarios, resolver conflicto si existía
            if (records.isEmpty()) {
                borrowRecords.remove(filename);
                resolveConflict(filename);
            }
        }
    }

    private void detectConflict(String filename) {
        List<BorrowRecord> records = borrowRecords.get(filename);
        if (records != null && records.size() > 1) {
            // Hay múltiples prestatarios activos -> CONFLICTO
            if (!activeConflicts.containsKey(filename)) {
                List<String> borrowers = new ArrayList<>();
                for (BorrowRecord record : records) {
                    borrowers.add(record.getBorrowerId());
                }

                int conflictId = conflictCounter.incrementAndGet();
                ConflictEntry conflict = new ConflictEntry(
                        conflictId,
                        filename,
                        borrowers,
                        System.currentTimeMillis(),
                        ConflictEntry.ConflictStatus.DETECTED);

                activeConflicts.put(filename, conflict);

                logRegistry.warning("ConflictRegistry",
                        "🚨 CONFLICTO DETECTADO #" + conflictId +
                                " para archivo: " + filename +
                                " entre: " + String.join(", ", borrowers));

                // Aquí se podría notificar a los involucrados
                notifyConflict(conflict);
            }
        }
    }

    public void resolveConflict(String filename, String resolutionStrategy) {
        ConflictEntry conflict = activeConflicts.get(filename);
        if (conflict != null) {
            conflict.setStatus(ConflictEntry.ConflictStatus.RESOLVED);
            conflict.setResolution(resolutionStrategy);
            conflict.setResolutionTime(System.currentTimeMillis());

            logRegistry.info("ConflictRegistry",
                    "✅ Conflicto #" + conflict.getId() + " resuelto: " + resolutionStrategy);

            activeConflicts.remove(filename);
        }
    }

    public void resolveConflict(String filename) {
        resolveConflict(filename, "Resolución automática - Préstamo finalizado");
    }

    private void notifyConflict(ConflictEntry conflict) {
        // En una implementación real, aquí se notificaría a los peers involucrados
        logRegistry.info("ConflictRegistry",
                "Notificación de conflicto #" + conflict.getId() + " enviada a involucrados");
    }

    public boolean hasConflict(String filename) {
        return activeConflicts.containsKey(filename);
    }

    public ConflictEntry getConflict(String filename) {
        return activeConflicts.get(filename);
    }

    public List<ConflictEntry> getActiveConflicts() {
        return new ArrayList<>(activeConflicts.values());
    }

    public List<ConflictEntry> getConflictHistory(String filename) {
        // En una implementación real, aquí se consultaría un historial persistente
        List<ConflictEntry> history = new ArrayList<>();
        if (activeConflicts.containsKey(filename)) {
            history.add(activeConflicts.get(filename));
        }
        return history;
    }

    public Map<String, Object> getConflictStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeConflicts", activeConflicts.size());
        stats.put("totalConflicts", conflictCounter.get());
        stats.put("borrowedFiles", borrowRecords.size());

        int totalBorrows = 0;
        for (List<BorrowRecord> records : borrowRecords.values()) {
            totalBorrows += records.size();
        }
        stats.put("totalBorrows", totalBorrows);

        return stats;
    }

    public void cleanup() {
        long oneHourAgo = System.currentTimeMillis() - 3600000;

        // Limpiar registros de préstamo antiguos
        borrowRecords.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(r -> r.getTimestamp() < oneHourAgo);
            return entry.getValue().isEmpty();
        });

        logRegistry.info("ConflictRegistry", "Limpieza completada");
    }

    // Clase interna para registro de préstamo
    private static class BorrowRecord {
        private final String borrowerId;
        private final long timestamp;

        public BorrowRecord(String borrowerId, long timestamp) {
            this.borrowerId = borrowerId;
            this.timestamp = timestamp;
        }

        public String getBorrowerId() {
            return borrowerId;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
