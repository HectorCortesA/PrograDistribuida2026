package com.p2p.metadata;

import com.p2p.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.security.NoSuchAlgorithmException;

public class FileMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Valor especial de expiración que indica TTL = 0 (siempre disponible).
     * Un archivo con este valor NUNCA debe ser expirado ni actualizado.
     */
    public static final long TTL_FOREVER = Long.MAX_VALUE;

    private final String filename;
    private String checksum;
    private String owner;
    private long expiration;   // System.currentTimeMillis() + TTL, o TTL_FOREVER
    private long size;
    private long lastModified;
    private long creationDate; // ← NUEVO: fecha de creación del archivo
    private String fileType;
    private boolean isShared;

    public FileMetadata(String filename, String checksum, String owner,
            long expiration, long size) {
        this.filename     = filename;
        this.checksum     = checksum;
        this.owner        = owner;
        this.expiration   = expiration;
        this.size         = size;
        this.lastModified = System.currentTimeMillis();
        this.creationDate = System.currentTimeMillis(); // se sobreescribe en fromFile()
        this.fileType     = getFileExtension(filename);
        this.isShared     = false;
    }

    public static FileMetadata fromFile(File file) {
        try {
            String filename = file.getName();
            String checksum = FileUtils.calculateChecksum(file);
            long size       = file.length();
            long lastMod    = file.lastModified();

            FileMetadata metadata = new FileMetadata(filename, checksum, null,
                    TTL_FOREVER, size);
            metadata.setLastModified(lastMod);
            // Intentar obtener fecha real de creación (Java 7+ NIO)
            try {
                java.nio.file.attribute.BasicFileAttributes attrs =
                        java.nio.file.Files.readAttributes(
                                file.toPath(),
                                java.nio.file.attribute.BasicFileAttributes.class);
                metadata.creationDate = attrs.creationTime().toMillis();
            } catch (Exception e) {
                metadata.creationDate = lastMod; // fallback
            }
            return metadata;
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return (lastDot > 0) ? filename.substring(lastDot + 1).toLowerCase() : "unknown";
    }

    /** Un archivo con TTL_FOREVER nunca expira (TTL = 0 según el enunciado). */
    public boolean isForever() {
        return expiration == TTL_FOREVER;
    }

    public boolean isExpired() {
        if (isForever()) return false;
        return System.currentTimeMillis() > expiration;
    }

    // ── Getters y Setters ─────────────────────────────────────────────────

    public String getFilename()     { return filename; }
    public String getChecksum()     { return checksum; }
    public void   setChecksum(String c) { this.checksum = c; }

    public String getOwner()        { return owner; }
    public void   setOwner(String o){ this.owner = o; }

    public long getExpiration()     { return expiration; }
    public void setExpiration(long e){ this.expiration = e; }

    public long getSize()           { return size; }
    public void setSize(long s)     { this.size = s; }

    public long getLastModified()   { return lastModified; }
    public void setLastModified(long lm){ this.lastModified = lm; }

    public long getCreationDate()   { return creationDate; }
    public void setCreationDate(long cd){ this.creationDate = cd; }

    public String getFileType()     { return fileType; }
    public boolean isShared()       { return isShared; }
    public void setShared(boolean s){ this.isShared = s; }

    @Override
    public String toString() {
        return String.format("%s [%s] %d bytes - dueño: %s", filename, fileType, size,
                owner != null ? owner : "local");
    }
}
