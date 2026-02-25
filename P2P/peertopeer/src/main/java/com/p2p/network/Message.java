package com.p2p.network;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final MessageType type;
    private final String senderId;
    private final Map<String, Object> payload;
    private long timestamp;

    public Message(MessageType type, String senderId) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.senderId = senderId;
        this.payload = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public void addPayload(String key, Object value) {
        payload.put(key, value);
    }

    public Object getPayload(String key) {
        return payload.get(key);
    }

    // Getters
    public String getId() {
        return id;
    }

    public MessageType getType() {
        return type;
    }

    public String getSenderId() {
        return senderId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public long getTimestamp() {
        return timestamp;
    }
}