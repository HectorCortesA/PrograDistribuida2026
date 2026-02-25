package com.p2p.metadata;

import com.p2p.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.security.NoSuchAlgorithmException;

public class FileMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String filename;
    private String checksum;
    private String owner;
    private long expiration;
    private long size;
    private long lastModified;
    private String fileType;
    private boolean isShared;

    public FileMetadata(String filename, String checksum, String owner,
            long expiration, long size) {
        this.filename = filename;
        this.checksum = checksum;
        this.owner = owner;
        this.expiration = expiration;
        this.size = size;
        this.lastModified = System.currentTimeMillis();
        this.fileType = getFileExtension(filename);
        this.isShared = false;
    }

    public static FileMetadata fromFile(File file) {
        try {
            String filename = file.getName();
            String checksum = FileUtils.calculateChecksum(file);
            long size = file.length();
            long lastModified = file.lastModified();

            FileMetadata metadata = new FileMetadata(filename, checksum, null,
                    Long.MAX_VALUE, size);
            metadata.setLastModified(lastModified);
            return metadata;
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(lastDot + 1).toLowerCase();
        }
        return "unknown";
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiration;
    }

    // Getters y setters
    public String getFilename() {
        return filename;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public long getExpiration() {
        return expiration;
    }

    public void setExpiration(long expiration) {
        this.expiration = expiration;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public String getFileType() {
        return fileType;
    }

    public boolean isShared() {
        return isShared;
    }

    public void setShared(boolean shared) {
        isShared = shared;
    }

    @Override
    public String toString() {
        return String.format("%s [%s] %d bytes - dueño: %s",
                filename, fileType, size, owner != null ? owner : "local");
    }
}
