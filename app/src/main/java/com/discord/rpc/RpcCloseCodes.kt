package com.discord.rpc

/** Codes carried in a `CLOSE` frame. Shares a number space with [RpcErrors] but not its meanings. */
object RpcCloseCodes {
    const val CLOSE_NORMAL = 1000
    const val CLOSE_UNSUPPORTED = 1003
    const val CLOSE_ABNORMAL = 1006
    const val INVALID_CLIENTID = 4000
    const val INVALID_ORIGIN = 4001
    const val RATELIMITED = 4002
    const val TOKEN_REVOKED = 4003
    const val INVALID_VERSION = 4004
    const val INVALID_ENCODING = 4005

    val ALL = intArrayOf(
        CLOSE_NORMAL,
        CLOSE_UNSUPPORTED,
        CLOSE_ABNORMAL,
        INVALID_CLIENTID,
        INVALID_ORIGIN,
        RATELIMITED,
        TOKEN_REVOKED,
        INVALID_VERSION,
        INVALID_ENCODING,
    )
}
