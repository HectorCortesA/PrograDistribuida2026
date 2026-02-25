package com.p2p.metadata;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MetadataStore {
    private final Map<String, FileMetadata> metadataMap;
    private final Map<String, Long> accessTimes;

    public MetadataStore() {
        this.metadataMap = new ConcurrentHashMap<>();
        this.accessTimes = new ConcurrentHashMap<>();
    }

    public void addMetadata(FileMetadata metadata) {
        if (metadata != null) {
            metadataMap.put(metadata.getFilename(), metadata);
            accessTimes.put(metadata.getFilename(), System.currentTimeMillis());
        }
    }

    public void updateMetadata(FileMetadata metadata) {
        if (metadata != null && metadataMap.containsKey(metadata.getFilename())) {
            metadataMap.put(metadata.getFilename(), metadata);
            accessTimes.put(metadata.getFilename(), System.currentTimeMillis());
        }
    }

    public FileMetadata getMetadata(String filename) {
        accessTimes.put(filename, System.currentTimeMillis());
        return metadataMap.get(filename);
    }

    public void removeMetadata(String filename) {
        metadataMap.remove(filename);
        accessTimes.remove(filename);
    }

    public boolean hasMetadata(String filename) {
        return metadataMap.containsKey(filename);
    }

    public Collection<FileMetadata> getAllMetadata() {
        return metadataMap.values();
    }

    public List<FileMetadata> getRecentlyAccessed(int limit) {
        return accessTimes.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(limit)
                .map(e -> metadataMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .toList();
    }

    public void discoverFiles() {
        File sharedDir = new File("shared");
        if (sharedDir.exists() && sharedDir.isDirectory()) {
            for (File file : sharedDir.listFiles()) {
                if (file.isFile()) {
                    FileMetadata metadata = FileMetadata.fromFile(file);
                    if (metadata != null) {
                        addMetadata(metadata);
                    }
                }
            }
        }

        File localDir = new File("local");
        if (localDir.exists() && localDir.isDirectory()) {
            for (File file : localDir.listFiles()) {
                if (file.isFile() && !metadataMap.containsKey(file.getName())) {
                    FileMetadata metadata = FileMetadata.fromFile(file);
                    if (metadata != null) {
                        addMetadata(metadata);
                    }
                }
            }
        }
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalFiles", metadataMap.size());
        stats.put("sharedFiles", metadataMap.values().stream()
                .filter(m -> m.getOwner() != null).count());
        stats.put("localOnly", metadataMap.values().stream()
                .filter(m -> m.getOwner() == null).count());
        return stats;
    }

    public void clear() {
        metadataMap.clear();
        accessTimes.clear();
    }
}
