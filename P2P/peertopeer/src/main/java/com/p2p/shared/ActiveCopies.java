package com.p2p.shared;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActiveCopies {
    private final Map<String, Set<CopyInfo>> activeCopies;
    private final Map<String, Long> copyStartTimes;

    public ActiveCopies() {
        this.activeCopies = new ConcurrentHashMap<>();
        this.copyStartTimes = new ConcurrentHashMap<>();
    }

    public void registerCopy(String filename, String clientId, String clientAddress) {
        CopyInfo copyInfo = new CopyInfo(clientId, clientAddress, System.currentTimeMillis());

        activeCopies.computeIfAbsent(filename, k -> ConcurrentHashMap.newKeySet()).add(copyInfo);

        String copyKey = filename + ":" + clientId;
        copyStartTimes.put(copyKey, System.currentTimeMillis());

        System.out.println("Copia registrada: " + filename + " para " + clientId);
    }

    public void unregisterCopy(String filename, String clientId) {
        Set<CopyInfo> copies = activeCopies.get(filename);
        if (copies != null) {
            copies.removeIf(info -> info.getClientId().equals(clientId));
            if (copies.isEmpty()) {
                activeCopies.remove(filename);
            }
        }

        String copyKey = filename + ":" + clientId;
        copyStartTimes.remove(copyKey);
    }

    public boolean hasActiveCopies(String filename) {
        Set<CopyInfo> copies = activeCopies.get(filename);
        return copies != null && !copies.isEmpty();
    }

    public int getActiveCopyCount(String filename) {
        Set<CopyInfo> copies = activeCopies.get(filename);
        return copies != null ? copies.size() : 0;
    }

    public List<String> getActiveCopyHolders(String filename) {
        Set<CopyInfo> copies = activeCopies.get(filename);
        if (copies == null)
            return new ArrayList<>();

        return copies.stream()
                .map(CopyInfo::getClientId)
                .toList();
    }

    public List<CopyInfo> getDetailedCopyInfo(String filename) {
        Set<CopyInfo> copies = activeCopies.get(filename);
        return copies != null ? new ArrayList<>(copies) : new ArrayList<>();
    }

    public void notifyCopyHolders(String filename) {
        Set<CopyInfo> holders = activeCopies.get(filename);
        if (holders != null && !holders.isEmpty()) {
            System.out.println("📢 Notificando a " + holders.size() +
                    " holders de copia sobre cambios en: " + filename);

            for (CopyInfo holder : holders) {
                // Aquí iría la notificación real por red
                System.out.println("  → Notificando a " + holder.getClientId() +
                        " [" + holder.getClientAddress() + "]");
            }
        }
    }

    public long getCopyDuration(String filename, String clientId) {
        String copyKey = filename + ":" + clientId;
        Long startTime = copyStartTimes.get(copyKey);
        if (startTime != null) {
            return System.currentTimeMillis() - startTime;
        }
        return 0;
    }

    public void cleanup() {
        long oneHourAgo = System.currentTimeMillis() - 3600000;

        // Limpiar copias muy antiguas
        activeCopies.values().forEach(copies -> copies.removeIf(info -> info.getStartTime() < oneHourAgo));

        activeCopies.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void clear() {
        activeCopies.clear();
        copyStartTimes.clear();
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalFilesWithCopies", activeCopies.size());

        int totalCopies = 0;
        for (Set<CopyInfo> copies : activeCopies.values()) {
            totalCopies += copies.size();
        }
        stats.put("totalActiveCopies", totalCopies);

        return stats;
    }

    // Clase interna para información detallada de copia
    public static class CopyInfo {
        private final String clientId;
        private final String clientAddress;
        private final long startTime;

        public CopyInfo(String clientId, String clientAddress, long startTime) {
            this.clientId = clientId;
            this.clientAddress = clientAddress;
            this.startTime = startTime;
        }

        public String getClientId() {
            return clientId;
        }

        public String getClientAddress() {
            return clientAddress;
        }

        public long getStartTime() {
            return startTime;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            CopyInfo copyInfo = (CopyInfo) o;
            return Objects.equals(clientId, copyInfo.clientId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clientId);
        }
    }
}
