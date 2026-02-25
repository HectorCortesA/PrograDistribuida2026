package com.p2p.cache;

import com.p2p.nameserver.NameServer;

public class CacheEntry {
    private final NameServer.FileInfo value;
    private final long expiration;

    public CacheEntry(NameServer.FileInfo value, long expiration) {
        this.value = value;
        this.expiration = expiration;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiration;
    }

    public NameServer.FileInfo getValue() {
        return value;
    }
}
