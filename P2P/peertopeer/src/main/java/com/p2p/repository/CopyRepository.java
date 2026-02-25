package com.p2p.repository;

import com.p2p.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CopyRepository {
    private static final String REPO_DIR = "repository";
    private final Map<String, List<FileCopy>> copies;

    public CopyRepository() {
        this.copies = new ConcurrentHashMap<>();
        new File(REPO_DIR).mkdirs();
        loadExistingCopies();
    }

    public void createCopy(String filename, String sourcePath, String owner) throws IOException {
        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) {
            throw new IOException("Archivo fuente no existe: " + sourcePath);
        }

        String copyId = UUID.randomUUID().toString();
        String copyPath = REPO_DIR + "/" + copyId + "_" + filename;
        File destFile = new File(copyPath);

        // Copiar archivo
        Files.copy(sourceFile.toPath(), destFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        FileCopy copy = new FileCopy(copyId, filename, copyPath, owner,
                System.currentTimeMillis(), sourceFile.length());

        copies.computeIfAbsent(filename, k -> new ArrayList<>()).add(copy);

        System.out.println("Copia creada: " + copyId + " para " + filename);
    }

    public FileCopy getCopy(String copyId) {
        for (List<FileCopy> fileCopies : copies.values()) {
            for (FileCopy copy : fileCopies) {
                if (copy.getCopyId().equals(copyId)) {
                    return copy;
                }
            }
        }
        return null;
    }

    public List<FileCopy> getCopies(String filename) {
        return copies.getOrDefault(filename, new ArrayList<>());
    }

    public FileCopy getLatestCopy(String filename) {
        List<FileCopy> fileCopies = copies.get(filename);
        if (fileCopies == null || fileCopies.isEmpty()) {
            return null;
        }

        return fileCopies.stream()
                .max(Comparator.comparingLong(FileCopy::getTimestamp))
                .orElse(null);
    }

    public void deleteCopy(String copyId) {
        for (List<FileCopy> fileCopies : copies.values()) {
            fileCopies.removeIf(copy -> {
                if (copy.getCopyId().equals(copyId)) {
                    new File(copy.getPath()).delete();
                    return true;
                }
                return false;
            });
        }
    }

    public void deleteCopies(String filename) {
        List<FileCopy> fileCopies = copies.remove(filename);
        if (fileCopies != null) {
            for (FileCopy copy : fileCopies) {
                new File(copy.getPath()).delete();
            }
        }
    }

    public File restoreCopy(String copyId) throws IOException {
        FileCopy copy = getCopy(copyId);
        if (copy == null) {
            throw new IOException("Copia no encontrada: " + copyId);
        }

        File sourceFile = new File(copy.getPath());
        if (!sourceFile.exists()) {
            throw new IOException("Archivo de copia no existe: " + copy.getPath());
        }

        // Restaurar a directorio compartido
        File destFile = FileUtils.getSharedFile(copy.getFilename());
        Files.copy(sourceFile.toPath(), destFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        return destFile;
    }

    private void loadExistingCopies() {
        File repoDir = new File(REPO_DIR);
        if (!repoDir.exists() || !repoDir.isDirectory()) {
            return;
        }

        for (File file : repoDir.listFiles()) {
            if (file.isFile()) {
                String name = file.getName();
                int underscoreIndex = name.indexOf('_');
                if (underscoreIndex > 0) {
                    String copyId = name.substring(0, underscoreIndex);
                    String filename = name.substring(underscoreIndex + 1);

                    FileCopy copy = new FileCopy(copyId, filename, file.getPath(),
                            "unknown", file.lastModified(), file.length());

                    copies.computeIfAbsent(filename, k -> new ArrayList<>()).add(copy);
                }
            }
        }
    }

    public void cleanup() {
        long oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 3600000);

        for (List<FileCopy> fileCopies : copies.values()) {
            fileCopies.removeIf(copy -> {
                if (copy.getTimestamp() < oneWeekAgo) {
                    new File(copy.getPath()).delete();
                    return true;
                }
                return false;
            });
        }

        copies.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    // Clase interna para representar una copia
    public static class FileCopy {
        private final String copyId;
        private final String filename;
        private final String path;
        private final String owner;
        private final long timestamp;
        private final long size;

        public FileCopy(String copyId, String filename, String path,
                String owner, long timestamp, long size) {
            this.copyId = copyId;
            this.filename = filename;
            this.path = path;
            this.owner = owner;
            this.timestamp = timestamp;
            this.size = size;
        }

        public String getCopyId() {
            return copyId;
        }

        public String getFilename() {
            return filename;
        }

        public String getPath() {
            return path;
        }

        public String getOwner() {
            return owner;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public long getSize() {
            return size;
        }
    }
}
