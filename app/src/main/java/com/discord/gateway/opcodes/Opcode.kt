package com.discord.gateway.opcodes

enum class Opcode(val apiInt: Int) {
    UNHANDLED(-1),
    DISPATCH(0),
    HEARTBEAT(1),
    IDENTIFY(2),
    PRESENCE_UPDATE(3),
    VOICE_STATE_UPDATE(4),
    VOICE_SERVER_PING(5),
    RESUME(6),
    RECONNECT(7),
    REQUEST_GUILD_MEMBERS(8),
    INVALID_SESSION(9),
    HELLO(10),
    HEARTBEAT_ACK(11),
    CALL_CONNECT(13),
    GUILD_SUBSCRIPTIONS(14),
    STREAM_CREATE(18),
    STREAM_DELETE(19),
    STREAM_WATCH(20),
    OPCODE_STREAM_PING(21),
    REQUEST_GUILD_APPLICATION_COMMANDS(24),
    REQUEST_FORUM_UNREADS(28),
    REQUEST_SOUNDBOARD_SOUNDS(31);

    companion object {
        private val byApiInt = entries.associateBy(Opcode::apiInt)
        fun fromApiInt(value: Int): Opcode = byApiInt[value] ?: UNHANDLED
    }
}
