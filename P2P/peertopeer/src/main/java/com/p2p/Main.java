package com.p2p;

import com.p2p.cache.LocalCache;
import com.p2p.client.ClientGUI;
import com.p2p.conflict.ConflictRegistry;
import com.p2p.consensus.ConsensusManager;
import com.p2p.metadata.MetadataStore;
import com.p2p.monitor.Synchronizer;
import com.p2p.monitor.TTLMonitor;
import com.p2p.nameserver.NameServer;
import com.p2p.network.FileTransferTCP;
import com.p2p.network.TCPNetworkModule;
import com.p2p.repository.CopyRepository;
import com.p2p.shared.ActiveCopies;
import com.p2p.shared.LogRegistry;
import com.p2p.shared.SharedList;
import com.p2p.utils.ThreadManager;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        System.out.println("Iniciando Nodo P2P...");

        ThreadManager     threadManager  = new ThreadManager();
        TCPNetworkModule  networkModule  = new TCPNetworkModule(threadManager);

        SharedList        sharedList     = new SharedList();
        MetadataStore     metadataStore  = new MetadataStore();
        LocalCache        localCache     = new LocalCache();
        ConflictRegistry  conflictReg    = new ConflictRegistry();
        ActiveCopies      activeCopies   = new ActiveCopies();
        CopyRepository    copyRepository = new CopyRepository();  // ← Unit of Work
        LogRegistry       logRegistry    = new LogRegistry();

        // ── NameServer ahora recibe ActiveCopies y CopyRepository ──
        NameServer nameServer = new NameServer(networkModule, sharedList, metadataStore,
                localCache, conflictReg, activeCopies, copyRepository, logRegistry);

        // ── ConsensusManager (debe crearse ANTES de TTLMonitor) ──
        ConsensusManager consensus = new ConsensusManager(networkModule, metadataStore,
                sharedList, logRegistry);

        // ── TTLMonitor ahora recibe ConsensusManager ──
        TTLMonitor   ttlMonitor   = new TTLMonitor(localCache, metadataStore,
                threadManager, consensus);
        Synchronizer synchronizer = new Synchronizer(metadataStore, conflictReg,
                activeCopies, networkModule, threadManager);

        // Registrar listeners (una sola vez)
        networkModule.addListener(nameServer);
        networkModule.addListener(consensus);

        // Iniciar servidor de mensajes (puerto 8888)
        networkModule.start();
        System.out.println("✓ Servidor de mensajes en puerto 8888");

        // Iniciar servidor de archivos (puerto 8889)
        FileTransferTCP.startServer("shared");

        // Cargar archivos locales
        sharedList.loadFromDirectory();
        metadataStore.discoverFiles();
        System.out.println("✓ Archivos locales: " + sharedList.getSharedFiles().size());

        // Conectar a peers pasados como argumentos
        if (args.length > 0)
            for (String peer : args) networkModule.connectToPeer(peer);

        networkModule.discoverLocalPeers();

        nameServer.start();
        ttlMonitor.start();
        synchronizer.start();

        SwingUtilities.invokeLater(() -> {
            ClientGUI gui = new ClientGUI(nameServer, networkModule, sharedList,
                    metadataStore, logRegistry);
            gui.setVisible(true);
        });

        System.out.println("Nodo P2P inicializado");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ttlMonitor.stop();
            synchronizer.stop();
            FileTransferTCP.stopServer();
            networkModule.shutdown();
            threadManager.shutdown();
            LogRegistry.close();
        }));
    }
}
