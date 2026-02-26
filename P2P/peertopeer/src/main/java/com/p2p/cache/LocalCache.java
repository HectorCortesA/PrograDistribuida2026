package com.p2p.cache;

import com.p2p.nameserver.NameServer;
import java.util.concurrent.ConcurrentHashMap;

public class LocalCache {
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public void put(String key, NameServer.FileInfo value) {
        cache.put(key, new CacheEntry(value, System.currentTimeMillis() + 3600000));
    }

    public NameServer.FileInfo get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.getValue();
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
