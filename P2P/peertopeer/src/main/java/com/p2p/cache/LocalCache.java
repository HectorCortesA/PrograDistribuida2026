package com.p2p.cache;

import com.p2p.metadata.FileMetadata;

import java.util.concurrent.ConcurrentHashMap;

/**
 * FIX: La clase original referenciaba NameServer.FileInfo que no existe.
 * Se reemplaza por FileMetadata, que es la clase correcta.
 */
public class LocalCache {
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // TTL por defecto: 1 hora
    private static final long DEFAULT_TTL_MS = 3_600_000L;

    public void put(String key, FileMetadata value) {
        cache.put(key, new CacheEntry(value, System.currentTimeMillis() + DEFAULT_TTL_MS));
    }

    // FIX: Sobrecarga que permite especificar TTL personalizado
    public void put(String key, FileMetadata value, long ttlMs) {
        cache.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlMs));
    }

    public FileMetadata get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.getValue();
        }
        // FIX: Si está expirada, eliminarla en el acto (lazy eviction)
        if (entry != null) {
            cache.remove(key);
        }
        return null;
    }

    public void remove(String key) {
        cache.remove(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public void cleanup() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
}