package com.p2p;

import com.p2p.client.ClientGUI;
import com.p2p.nameserver.NameServer;
import com.p2p.network.TCPNetworkModule;
import com.p2p.utils.ThreadManager;
import com.p2p.shared.SharedList;
import com.p2p.shared.LogRegistry;

import javax.swing.*;
import java.io.File;

public class Main {
    public static void main(String[] args) {
        System.out.println("=====================================");
        System.out.println("Iniciando Nodo P2P File Sharing...");
        System.out.println("=====================================");

        // Crear directorios
        new File("shared").mkdirs();
        new File("local").mkdirs();

        // Componentes
        ThreadManager threadManager = new ThreadManager();
        TCPNetworkModule networkModule = new TCPNetworkModule(threadManager);
        SharedList sharedList = new SharedList();
        LogRegistry logRegistry = new LogRegistry();

        // Cargar archivos locales
        sharedList.loadFromDirectory();
        System.out.println("📋 Archivos locales: " + sharedList.getSharedFiles());

        // Pasar SharedList al módulo de red
        networkModule.setSharedList(sharedList);

        // NameServer
        NameServer nameServer = new NameServer(networkModule, sharedList, logRegistry);
        networkModule.addListener(nameServer);

        // Iniciar red
        networkModule.start();

        // Conectar a peers
        if (args.length > 0) {
            for (String peer : args) {
                networkModule.connectToPeer(peer);
            }
        }

        // GUI
        SwingUtilities.invokeLater(() -> {
            ClientGUI clientGUI = new ClientGUI(nameServer, networkModule, sharedList, logRegistry);
            clientGUI.setVisible(true);
        });

        System.out.println("✅ Nodo listo: " + networkModule.getNodeId());
        System.out.println("=====================================");
    }
}