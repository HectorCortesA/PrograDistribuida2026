package com.p2p.consensus;

import com.p2p.metadata.FileMetadata;
import com.p2p.metadata.MetadataStore;
import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.shared.LogRegistry;
import com.p2p.shared.SharedList;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConsensusManager implements com.p2p.network.MessageListener {
    private final TCPNetworkModule networkModule;
    private final MetadataStore metadataStore;
    private final SharedList sharedList;
    private final LogRegistry logRegistry;

    private final Map<String, ConsensusVote> activeVotes;
    private final Map<String, List<String>> pendingRequests;
    private final AtomicInteger consensusCounter;

    public ConsensusManager(TCPNetworkModule networkModule, MetadataStore metadataStore,
            SharedList sharedList, LogRegistry logRegistry) {
        this.networkModule = networkModule;
        this.metadataStore = metadataStore;
        this.sharedList = sharedList;
        this.logRegistry = logRegistry;
        this.activeVotes = new ConcurrentHashMap<>();
        this.pendingRequests = new ConcurrentHashMap<>();
        this.consensusCounter = new AtomicInteger(0);

        networkModule.addListener(this);
    }

    public void initiateFileRemovalConsensus(String filename) {
        String voteId = "VOTE-" + consensusCounter.incrementAndGet() + "-" +
                System.currentTimeMillis();

        logRegistry.info("ConsensusManager",
                "Iniciando consenso para eliminar archivo: " + filename +
                        " [Voto ID: " + voteId + "]");

        ConsensusVote vote = new ConsensusVote(voteId, filename, networkModule.getNodeId());
        activeVotes.put(voteId, vote);

        // Enviar consulta de consenso a todos los peers
        Message query = new Message(MessageType.CONSENSUS_QUERY, networkModule.getNodeId());
        query.addPayload("voteId", voteId);
        query.addPayload("filename", filename);
        query.addPayload("initiator", networkModule.getNodeId());
        query.addPayload("timestamp", System.currentTimeMillis());

        networkModule.broadcast(query);
        logRegistry.info("ConsensusManager",
                "Consulta de consenso enviada a " + networkModule.getPeerCount() + " peers");

        // Programar cierre de votación
        networkModule.getThreadManager().getScheduler().schedule(
                () -> finalizeVote(voteId),
                10, TimeUnit.SECONDS);
    }

    @Override
    public void onMessage(Message message, TCPNetworkModule.PeerConnection source) {
        switch (message.getType()) {
            case CONSENSUS_QUERY:
                handleConsensusQuery(message, source);
                break;
            case CONSENSUS_RESPONSE:
                handleConsensusResponse(message, source);
                break;
            default:
                // Ignorar otros tipos
                break;
        }
    }

    private void handleConsensusQuery(Message message, TCPNetworkModule.PeerConnection source) {
        String filename = (String) message.getPayload("filename");
        String voteId = (String) message.getPayload("voteId");
        String initiator = (String) message.getPayload("initiator");

        logRegistry.info("ConsensusManager",
                "Recibida consulta de consenso de " + source.getPeerId() +
                        " para: " + filename);

        // Verificar si tenemos el archivo
        boolean hasFile = metadataStore.hasMetadata(filename) ||
                sharedList.isShared(filename);

        // Verificar si el archivo está en uso (préstamos activos)
        boolean inUse = false; // Aquí se consultaría al registro de conflictos

        Message response = new Message(MessageType.CONSENSUS_RESPONSE,
                networkModule.getNodeId());
        response.addPayload("voteId", voteId);
        response.addPayload("filename", filename);
        response.addPayload("hasFile", hasFile);
        response.addPayload("inUse", inUse);
        response.addPayload("responder", networkModule.getNodeId());

        networkModule.sendMessage(response, source.getPeerId());

        logRegistry.info("ConsensusManager",
                "Respuesta enviada: hasFile=" + hasFile + ", inUse=" + inUse);
    }

    private void handleConsensusResponse(Message message, TCPNetworkModule.PeerConnection source) {
        String voteId = (String) message.getPayload("voteId");
        ConsensusVote vote = activeVotes.get(voteId);

        if (vote != null) {
            boolean hasFile = (boolean) message.getPayload("hasFile");
            boolean inUse = (boolean) message.getPayload("inUse");
            String responder = (String) message.getPayload("responder");

            vote.addVote(responder, hasFile, inUse);

            logRegistry.info("ConsensusManager",
                    "Voto recibido de " + responder + ": hasFile=" + hasFile +
                            ", inUse=" + inUse + " [Total: " + vote.getVoteCount() + "]");
        }
    }

    private void finalizeVote(String voteId) {
        ConsensusVote vote = activeVotes.remove(voteId);
        if (vote != null) {
            logRegistry.info("ConsensusManager",
                    "Finalizando votación " + voteId + " - Total votos: " + vote.getVoteCount());

            ConsensusResult result = vote.getResult();

            logRegistry.info("ConsensusManager",
                    "Resultados: A favor=" + result.yesCount +
                            ", En contra=" + result.noCount +
                            ", Abstención=" + result.abstainCount);

            if (result.shouldRemove()) {
                // Consenso alcanzado: eliminar archivo
                String filename = vote.getFilename();
                executeFileRemoval(filename);
            } else {
                logRegistry.info("ConsensusManager",
                        "No se alcanzó consenso para eliminar: " + vote.getFilename());
            }
        }
    }

    private void executeFileRemoval(String filename) {
        logRegistry.warning("ConsensusManager",
                "⚠ CONSENSO ALCANZADO: Eliminando archivo: " + filename);

        // Eliminar de metadatos
        metadataStore.removeMetadata(filename);

        // Eliminar de lista compartida
        sharedList.removeFromList(filename);

        // Eliminar archivo físico
        java.io.File sharedFile = com.p2p.utils.FileUtils.getSharedFile(filename);
        if (sharedFile.exists()) {
            if (sharedFile.delete()) {
                logRegistry.info("ConsensusManager",
                        "Archivo físico eliminado: " + filename);
            } else {
                logRegistry.error("ConsensusManager",
                        "No se pudo eliminar archivo físico: " + filename);
            }
        }

        // Notificar a la red
        Message removalNotice = new Message(MessageType.PEER_LEAVE,
                networkModule.getNodeId());
        removalNotice.addPayload("filename", filename);
        removalNotice.addPayload("action", "FILE_REMOVED_BY_CONSENSUS");

        networkModule.broadcast(removalNotice);
    }

    @Override
    public void onPeerConnected(String peerId) {
        // No hacer nada especial
    }

    @Override
    public void onPeerDisconnected(String peerId) {
        logRegistry.info("ConsensusManager", "Peer desconectado: " + peerId);
    }

    // Clase para manejar la votación
    private static class ConsensusVote {
        private final String voteId;
        private final String filename;
        private final String initiator;
        private final Map<String, VoteRecord> votes;
        private final long startTime;

        public ConsensusVote(String voteId, String filename, String initiator) {
            this.voteId = voteId;
            this.filename = filename;
            this.initiator = initiator;
            this.votes = new HashMap<>();
            this.startTime = System.currentTimeMillis();
        }

        public void addVote(String peerId, boolean hasFile, boolean inUse) {
            votes.put(peerId, new VoteRecord(hasFile, inUse, System.currentTimeMillis()));
        }

        public ConsensusResult getResult() {
            int yes = 0, no = 0, abstain = 0;

            for (VoteRecord record : votes.values()) {
                if (record.hasFile) {
                    yes++;
                } else {
                    if (record.inUse) {
                        no++; // En uso = voto en contra
                    } else {
                        abstain++; // No tiene archivo ni en uso = abstención
                    }
                }
            }

            return new ConsensusResult(yes, no, abstain);
        }

        public int getVoteCount() {
            return votes.size();
        }

        public String getFilename() {
            return filename;
        }

        private static class VoteRecord {
            private final boolean hasFile;
            private final boolean inUse;
            private final long timestamp;

            public VoteRecord(boolean hasFile, boolean inUse, long timestamp) {
                this.hasFile = hasFile;
                this.inUse = inUse;
                this.timestamp = timestamp;
            }
        }
    }

    // Resultado de la votación
    private static class ConsensusResult {
        private final int yesCount;
        private final int noCount;
        private final int abstainCount;

        public ConsensusResult(int yes, int no, int abstain) {
            this.yesCount = yes;
            this.noCount = no;
            this.abstainCount = abstain;
        }

        public boolean shouldRemove() {
            // Regla: mayoría simple de votos positivos (excluyendo abstenciones)
            int totalVotes = yesCount + noCount;
            if (totalVotes == 0)
                return false;

            return yesCount > totalVotes / 2;
        }
    }
}