package com.discord.rpc

/** Frame opcodes, sent as element 0 of every `[opcode, payload]` tuple. */
object RpcOpcodes {
    const val HANDSHAKE = 0
    const val FRAME = 1
    const val CLOSE = 2
    const val HELLO = 3
}
