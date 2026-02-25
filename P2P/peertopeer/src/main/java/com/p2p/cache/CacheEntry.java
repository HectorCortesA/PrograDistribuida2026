package com.p2p.cache;

import com.p2p.metadata.FileMetadata;

/**
 * FIX: La clase original referenciaba NameServer.FileInfo que no existe.
 * Se reemplaza por FileMetadata, que es la clase que realmente se usa.
 */
public class CacheEntry {
    private final FileMetadata value;
    private final long expiration;

    public CacheEntry(FileMetadata value, long expiration) {
        this.value = value;
        this.expiration = expiration;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiration;
    }

    public FileMetadata getValue() {
        return value;
    }

    public long getExpiration() {
        return expiration;
    }
}