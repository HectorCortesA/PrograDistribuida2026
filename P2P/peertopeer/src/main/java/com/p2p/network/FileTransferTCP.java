package com.p2p.network;

import java.io.*;
import java.nio.file.*;

public class FileTransferTCP {

    public static void sendFile(TCPNetworkModule.PeerConnection conn,
            String filename, File file) throws IOException {
        // Anunciar transferencia
        Message fileMsg = new Message(MessageType.FILE_TRANSFER, conn.getPeerId());
        fileMsg.addPayload("filename", filename);
        fileMsg.addPayload("size", file.length());

        conn.getOos().writeObject(fileMsg);
        conn.getOos().flush();

        // Enviar datos
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            int sequence = 0;

            while ((bytesRead = fis.read(buffer)) != -1) {
                Message dataMsg = new Message(MessageType.FILE_DATA, conn.getPeerId());
                dataMsg.addPayload("filename", filename);
                dataMsg.addPayload("data", buffer);
                dataMsg.addPayload("offset", sequence);
                dataMsg.addPayload("length", bytesRead);
                dataMsg.addPayload("sequence", sequence++);

                conn.getOos().writeObject(dataMsg);
                conn.getOos().flush();
            }
        }

        // Completado
        Message completeMsg = new Message(MessageType.FILE_COMPLETE, conn.getPeerId());
        completeMsg.addPayload("filename", filename);
        conn.getOos().writeObject(completeMsg);
        conn.getOos().flush();
    }

    public static void receiveFile(TCPNetworkModule.PeerConnection conn,
            String filename, long size) throws IOException {
        File file = new File("shared/" + filename);
        file.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(file)) {
            boolean receiving = true;
            long received = 0;
            int expectedSequence = 0;

            while (receiving && received < size) {
                Message msg = (Message) conn.getOis().readObject();

                switch (msg.getType()) {
                    case FILE_DATA:
                        byte[] data = (byte[]) msg.getPayload("data");
                        int length = (int) msg.getPayload("length");
                        int sequence = (int) msg.getPayload("sequence");

                        // Verificar secuencia
                        if (sequence == expectedSequence) {
                            fos.write(data, 0, length);
                            received += length;
                            expectedSequence++;
                        }
                        break;

                    case FILE_COMPLETE:
                        receiving = false;
                        break;
                }
            }
        } catch (ClassNotFoundException e) {
            throw new IOException("Error en protocolo", e);
        }
    }
}