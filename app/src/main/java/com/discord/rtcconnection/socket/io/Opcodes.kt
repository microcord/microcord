package com.discord.rtcconnection.socket.io

/**
 * Opcodes of the voice control WebSocket (`wss://<region>.media.discord.gg/?v=8`).
 *
 * This is a different protocol from the main gateway (`com.discord.gateway.opcodes.Opcode`) and the
 * numbers do not correspond; keep the two tables apart.
 *
 * Reference: `com.discord.rtcconnection.socket.io.Opcodes`.
 */
internal object Opcodes {
    const val IDENTIFY = 0
    const val SELECT_PROTOCOL = 1
    const val READY = 2
    const val HEARTBEAT = 3
    const val SELECT_PROTOCOL_ACK = 4
    const val SPEAKING = 5
    const val HEARTBEAT_ACK = 6
    const val RESUME = 7
    const val HELLO = 8
    const val RESUMED = 9
    const val CLIENTS_CONNECT = 11
    const val VIDEO = 12
    const val CLIENT_DISCONNECT = 13
    const val SESSION_UPDATE = 14
    const val MEDIA_SINK_WANTS = 15
    const val VOICE_BACKEND_VERSION = 16
    const val CLIENT_FLAGS = 18
    const val CLIENT_PLATFORM = 20
    const val DAVE_PREPARE_TRANSITION = 21
    const val DAVE_EXECUTE_TRANSITION = 22
    const val DAVE_TRANSITION_READY = 23
    const val DAVE_PREPARE_EPOCH = 24
    const val DAVE_MLS_EXTERNAL_SENDER = 25
    const val DAVE_MLS_KEY_PACKAGE = 26
    const val DAVE_MLS_PROPOSALS = 27
    const val DAVE_MLS_COMMIT_WELCOME = 28
    const val DAVE_MLS_ANNOUNCE_COMMIT_TRANSITION = 29
    const val DAVE_MLS_WELCOME = 30
    const val DAVE_MLS_INVALID_COMMIT_WELCOME = 31

    private val nameLookup: Map<Int, String> = mapOf(
        IDENTIFY to "IDENTIFY",
        SELECT_PROTOCOL to "SELECT_PROTOCOL",
        READY to "READY",
        HEARTBEAT to "HEARTBEAT",
        SELECT_PROTOCOL_ACK to "SELECT_PROTOCOL_ACK",
        SPEAKING to "SPEAKING",
        HEARTBEAT_ACK to "HEARTBEAT_ACK",
        RESUME to "RESUME",
        HELLO to "HELLO",
        RESUMED to "RESUMED",
        CLIENTS_CONNECT to "CLIENTS_CONNECT",
        VIDEO to "VIDEO",
        CLIENT_DISCONNECT to "CLIENT_DISCONNECT",
        SESSION_UPDATE to "SESSION_UPDATE",
        MEDIA_SINK_WANTS to "MEDIA_SINK_WANTS",
        VOICE_BACKEND_VERSION to "VOICE_BACKEND_VERSION",
        CLIENT_FLAGS to "CLIENT_FLAGS",
        CLIENT_PLATFORM to "CLIENT_PLATFORM",
        DAVE_PREPARE_TRANSITION to "DAVE_PREPARE_TRANSITION",
        DAVE_EXECUTE_TRANSITION to "DAVE_EXECUTE_TRANSITION",
        DAVE_TRANSITION_READY to "DAVE_TRANSITION_READY",
        DAVE_PREPARE_EPOCH to "DAVE_PREPARE_EPOCH",
        DAVE_MLS_EXTERNAL_SENDER to "DAVE_MLS_EXTERNAL_SENDER",
        DAVE_MLS_KEY_PACKAGE to "DAVE_MLS_KEY_PACKAGE",
        DAVE_MLS_PROPOSALS to "DAVE_MLS_PROPOSALS",
        DAVE_MLS_COMMIT_WELCOME to "DAVE_MLS_COMMIT_WELCOME",
        DAVE_MLS_ANNOUNCE_COMMIT_TRANSITION to "DAVE_MLS_ANNOUNCE_COMMIT_TRANSITION",
        DAVE_MLS_WELCOME to "DAVE_MLS_WELCOME",
        DAVE_MLS_INVALID_COMMIT_WELCOME to "DAVE_MLS_INVALID_COMMIT_WELCOME",
    )

    fun getNameOf(opcode: Int): String = nameLookup[opcode] ?: "Unknown ($opcode)"
}
