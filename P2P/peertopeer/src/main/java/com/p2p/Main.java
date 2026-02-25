package com.p2p;

import com.p2p.client.ClientGUI;
import com.p2p.nameserver.NameServer;
import com.p2p.network.TCPNetworkModule;
import com.p2p.utils.ThreadManager;
import com.p2p.shared.SharedList;
import com.p2p.metadata.MetadataStore;
import com.p2p.cache.LocalCache;
import com.p2p.conflict.ConflictRegistry;
import com.p2p.monitor.TTLMonitor;
import com.p2p.monitor.Synchronizer;
import com.p2p.consensus.ConsensusManager;
import com.p2p.shared.ActiveCopies;
import com.p2p.shared.LogRegistry;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        System.out.println("=====================================");
        System.out.println("Iniciando Nodo P2P File Sharing...");
        System.out.println("=====================================");

        // Cada nodo tiene TODOS los componentes (Peer puro)
        ThreadManager threadManager = new ThreadManager();

        // Módulo de red: ESCUCHA (servidor) y CONECTA (cliente)
        TCPNetworkModule networkModule = new TCPNetworkModule(threadManager);

        // Componentes locales de este nodo
        SharedList sharedList = new SharedList();
        MetadataStore metadataStore = new MetadataStore();
        LocalCache localCache = new LocalCache();
        ConflictRegistry conflictRegistry = new ConflictRegistry();
        ActiveCopies activeCopies = new ActiveCopies();
        LogRegistry logRegistry = new LogRegistry();

        // Servidor de nombres local
        NameServer nameServer = new NameServer(networkModule, sharedList, metadataStore,
                localCache, conflictRegistry, logRegistry);

        // Monitores y sincronizadores
        TTLMonitor ttlMonitor = new TTLMonitor(localCache, metadataStore, threadManager);
        Synchronizer synchronizer = new Synchronizer(metadataStore, conflictRegistry,
                activeCopies, networkModule);
        ConsensusManager consensusManager = new ConsensusManager(networkModule, metadataStore,
                sharedList, logRegistry);

        // Registrar listeners
        networkModule.addListener(nameServer);
        networkModule.addListener(consensusManager);

        // Iniciar el módulo de red (empieza a escuchar como servidor)
        networkModule.start();
        System.out.println("✓ Nodo escuchando como servidor en puerto 8888");

        // Cargar archivos compartidos locales
        sharedList.loadFromDirectory();
        metadataStore.discoverFiles();
        System.out.println("✓ Archivos locales cargados: " + sharedList.getSharedFiles().size());

        // Conectar a otros peers (actuar como cliente)
        if (args.length > 0) {
            System.out.println("→ Actuando como cliente: Conectando a otros peers...");
            for (String peerAddress : args) {
                networkModule.connectToPeer(peerAddress);
            }
        }

        // Descubrir peers automáticamente (broadcast local)
        networkModule.discoverLocalPeers();

        // Iniciar monitores
        ttlMonitor.start();

        // Iniciar GUI
        SwingUtilities.invokeLater(() -> {
            ClientGUI clientGUI = new ClientGUI(nameServer, networkModule, sharedList,
                    metadataStore, logRegistry);
            clientGUI.setVisible(true);
        });

        System.out.println("✓ Nodo P2P completamente inicializado");
        System.out.println("=====================================");

        // Hook para shutdown graceful
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("→ Nodo saliendo de la red P2P...");
            networkModule.shutdown();
            threadManager.shutdown();
            logRegistry.close();
            System.out.println("✓ Nodo desconectado correctamente");
        }));
    }
}