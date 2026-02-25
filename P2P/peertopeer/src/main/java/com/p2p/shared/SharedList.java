package com.p2p.shared;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SharedList {
    private final Set<String> sharedFiles = ConcurrentHashMap.newKeySet();

    public void addFile(String filename) {
        sharedFiles.add(filename);
    }

    public void removeFromList(String filename) {
        sharedFiles.remove(filename);
    }

    public boolean isShared(String filename) {
        return sharedFiles.contains(filename);
    }

    public List<String> getSharedFiles() {
        return new ArrayList<>(sharedFiles);
    }

    public void loadFromDirectory() {
        File sharedDir = new File("shared");
        if (sharedDir.exists() && sharedDir.isDirectory()) {
            for (File file : sharedDir.listFiles()) {
                if (file.isFile()) {
                    sharedFiles.add(file.getName());
                }
            }
        }
    }
}
