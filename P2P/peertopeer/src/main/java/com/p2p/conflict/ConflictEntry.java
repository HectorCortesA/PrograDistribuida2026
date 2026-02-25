package com.p2p.conflict;

import java.io.Serializable;
import java.util.List;

public class ConflictEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String filename;
    private final List<String> borrowers;
    private final long detectionTime;
    private ConflictStatus status;
    private String resolution;
    private long resolutionTime;

    public enum ConflictStatus {
        DETECTED,
        IN_PROGRESS,
        RESOLVED,
        IGNORED
    }

    public ConflictEntry(int id, String filename, List<String> borrowers,
            long detectionTime, ConflictStatus status) {
        this.id = id;
        this.filename = filename;
        this.borrowers = borrowers;
        this.detectionTime = detectionTime;
        this.status = status;
        this.resolution = null;
        this.resolutionTime = 0;
    }

    // Getters y setters
    public int getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public List<String> getBorrowers() {
        return borrowers;
    }

    public long getDetectionTime() {
        return detectionTime;
    }

    public ConflictStatus getStatus() {
        return status;
    }

    public void setStatus(ConflictStatus status) {
        this.status = status;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public long getResolutionTime() {
        return resolutionTime;
    }

    public void setResolutionTime(long resolutionTime) {
        this.resolutionTime = resolutionTime;
    }

    public long getDuration() {
        if (status == ConflictStatus.RESOLVED) {
            return resolutionTime - detectionTime;
        }
        return System.currentTimeMillis() - detectionTime;
    }

    @Override
    public String toString() {
        return String.format("Conflicto #%d: %s [%s] - %d ms",
                id, filename, status, getDuration());
    }
}
