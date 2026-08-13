package com.discord.rpc

/** A [closeCode] disconnects the socket; an [errorCode] answers the one command and leaves it open. */
class RpcError private constructor(
    val errorCode: Int?,
    val closeCode: Int?,
    message: String,
) : RuntimeException(message) {

    val code: Int get() = closeCode ?: errorCode ?: RpcErrors.UNKNOWN_ERROR

    companion object {
        fun error(errorCode: Int, message: String) = RpcError(errorCode, null, message)

        fun close(closeCode: Int, message: String) = RpcError(null, closeCode, message)
    }
}
