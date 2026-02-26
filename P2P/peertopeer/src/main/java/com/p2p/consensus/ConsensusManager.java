package com.p2p.consensus;

import com.p2p.metadata.FileMetadata;
import com.p2p.metadata.MetadataStore;
import com.p2p.network.Message;
import com.p2p.network.MessageType;
import com.p2p.network.TCPNetworkModule;
import com.p2p.shared.LogRegistry;
import com.p2p.shared.SharedList;
import com.p2p.utils.FileUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConsensusManager implements TCPNetworkModule.MessageListener {
    private final TCPNetworkModule networkModule;
    private final MetadataStore metadataStore;
    private final SharedList sharedList;
    private final LogRegistry logRegistry;

    private final Map<String, ConsensusVote> activeVotes;
    private final AtomicInteger consensusCounter;

    public ConsensusManager(TCPNetworkModule networkModule, MetadataStore metadataStore,
            SharedList sharedList, LogRegistry logRegistry) {
        this.networkModule = networkModule;
        this.metadataStore = metadataStore;
        this.sharedList = sharedList;
        this.logRegistry = logRegistry;
        this.activeVotes = new ConcurrentHashMap<>();
        this.consensusCounter = new AtomicInteger(0);

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

        logRegistry.info("ConsensusManager",
                "Recibida consulta de consenso de " + source.getPeerId() +
                        " para: " + filename);

        // Verificar si tenemos el archivo
        boolean hasFile = metadataStore.hasMetadata(filename) ||
                sharedList.isShared(filename);

        Message response = new Message(MessageType.CONSENSUS_RESPONSE,
                networkModule.getNodeId());
        response.addPayload("voteId", voteId);
        response.addPayload("filename", filename);
        response.addPayload("hasFile", hasFile);
        response.addPayload("responder", networkModule.getNodeId());

        networkModule.sendMessage(response, source.getPeerId());

        logRegistry.info("ConsensusManager",
                "Respuesta enviada: hasFile=" + hasFile);
    }

    private void handleConsensusResponse(Message message, TCPNetworkModule.PeerConnection source) {
        String voteId = (String) message.getPayload("voteId");
        ConsensusVote vote = activeVotes.get(voteId);

        if (vote != null) {
            boolean hasFile = (boolean) message.getPayload("hasFile");
            String responder = (String) message.getPayload("responder");

            vote.addVote(responder, hasFile);

            logRegistry.info("ConsensusManager",
                    "Voto recibido de " + responder + ": hasFile=" + hasFile +
                            " [Total: " + vote.getVoteCount() + "]");
        }
    }

    private void finalizeVote(String voteId) {
        ConsensusVote vote = activeVotes.remove(voteId);
        if (vote != null) {
            logRegistry.info("ConsensusManager",
                    "Finalizando votación " + voteId + " - Total votos: " + vote.getVoteCount());

            boolean shouldRemove = vote.shouldRemove();

            logRegistry.info("ConsensusManager",
                    "Resultado: " + (shouldRemove ? "ELIMINAR" : "CONSERVAR"));

            if (shouldRemove) {
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
        java.io.File sharedFile = FileUtils.getSharedFile(filename);
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
        private final Map<String, Boolean> votes;
        private final long startTime;

        public ConsensusVote(String voteId, String filename, String initiator) {
            this.voteId = voteId;
            this.filename = filename;
            this.initiator = initiator;
            this.votes = new HashMap<>();
            this.startTime = System.currentTimeMillis();
        }

        public void addVote(String peerId, boolean hasFile) {
            votes.put(peerId, hasFile);
        }

        public boolean shouldRemove() {
            if (votes.isEmpty())
                return false;

            long yesVotes = votes.values().stream().filter(v -> !v).count();
            return yesVotes > votes.size() / 2;
        }

        public int getVoteCount() {
            return votes.size();
        }

        public String getFilename() {
            return filename;
        }
    }
}