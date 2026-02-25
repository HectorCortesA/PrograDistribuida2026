package com.p2p.utils;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileUtils {
    private static final String SHARED_DIR = "shared";
    private static final String LOCAL_DIR = "local";

    static {
        createDirectories();
    }

    private static void createDirectories() {
        new File(SHARED_DIR).mkdirs();
        new File(LOCAL_DIR).mkdirs();
    }

    public static String calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        byte[] digest = md.digest();
        return bytesToHex(digest);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void copyFile(File source, File dest) throws IOException {
        Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static File getSharedFile(String filename) {
        return new File(SHARED_DIR, filename);
    }

    public static File getLocalFile(String filename) {
        return new File(LOCAL_DIR, filename);
    }
}
