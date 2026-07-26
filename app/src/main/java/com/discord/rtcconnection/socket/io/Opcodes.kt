package com.discord.rtcconnection.socket.io

/**
 * Opcodes of the voice control WebSocket (`wss://<region>.media.discord.gg/?v=5`).
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
    const val VIDEO = 12
    const val CLIENT_DISCONNECT = 13
    const val SESSION_UPDATE = 14
    const val MEDIA_SINK_WANTS = 15

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
        VIDEO to "VIDEO",
        CLIENT_DISCONNECT to "CLIENT_DISCONNECT",
        SESSION_UPDATE to "SESSION_UPDATE",
        MEDIA_SINK_WANTS to "MEDIA_SINK_WANTS",
    )

    fun getNameOf(opcode: Int): String = nameLookup[opcode] ?: "Unknown ($opcode)"
}
