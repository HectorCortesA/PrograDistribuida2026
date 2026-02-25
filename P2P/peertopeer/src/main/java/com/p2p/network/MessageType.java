package com.p2p.network;

public enum MessageType {
    // TCP Handshake
    TCP_SYN,
    TCP_SYN_ACK,
    TCP_ACK,
    TCP_FIN,
    TCP_FIN_ACK,

    // Peticiones de nombres
    NAME_QUERY,
    NAME_RESPONSE,

    // Peticiones de archivos
    FILE_REQUEST,
    FILE_RESPONSE,
    FILE_TRANSFER,
    FILE_DATA,
    FILE_COMPLETE,

    // Descubrimiento
    PEER_DISCOVERY,
    PEER_ANNOUNCE,
    PEER_LIST,
    PEER_LEAVE,

    // Sincronización
    SYNC_REQUEST,
    SYNC_RESPONSE,

    // Conflictos
    CONFLICT_DETECTED,
    CONFLICT_RESOLVED,

    // Consenso
    CONSENSUS_QUERY,
    CONSENSUS_RESPONSE,

    // Heartbeat
    HEARTBEAT,

    // NACK
    NACK_RESPONSE
}
