package com.discord.rpc

/**
 * Codes carried in an `ERROR` frame's `data.code`. These share a number space with [RpcCloseCodes]
 * without agreeing on meanings — 4000 is `INVALID_PAYLOAD` here and `INVALID_CLIENTID` there.
 */
object RpcErrors {
    const val UNKNOWN_ERROR = 1000
    const val SERVICE_UNAVAILABLE = 1001
    const val TRANSACTION_ABORTED = 1002

    const val INVALID_PAYLOAD = 4000
    const val INVALID_COMMAND = 4002
    const val INVALID_GUILD = 4003
    const val INVALID_EVENT = 4004
    const val INVALID_CHANNEL = 4005
    const val INVALID_PERMISSIONS = 4006
    const val INVALID_CLIENTID = 4007
    const val INVALID_ORIGIN = 4008
    const val INVALID_TOKEN = 4009
    const val INVALID_USER = 4010
    const val INVALID_INVITE = 4011
    const val INVALID_ACTIVITY_JOIN_REQUEST = 4012
    const val INVALID_ENTITLEMENT = 4015
    const val INVALID_GIFT_CODE = 4016
    const val INVALID_GUILD_TEMPLATE = 4017
    const val INVALID_SOUND = 4018
    const val INVALID_PROVIDER = 4019
    const val INVALID_CONNECTION_CALLBACK_STATE = 4020
    const val BAD_REQUEST_FOR_PROVIDER = 4021

    const val OAUTH2_ERROR = 5000
    const val SELECT_CHANNEL_TIMED_OUT = 5001
    const val GET_GUILD_TIMED_OUT = 5002
    const val SELECT_VOICE_FORCE_REQUIRED = 5003
    const val INVALID_ACTIVITY_SECRET = 5005
    const val NO_ELIGIBLE_ACTIVITY = 5006
    const val PURCHASE_CANCELED = 5008
    const val PURCHASE_ERROR = 5009
    const val UNAUTHORIZED_FOR_ACHIEVEMENT = 5010
    const val RATE_LIMITED = 5011
    const val UNAUTHORIZED_FOR_APPLICATION = 5012
    const val NO_CONNECTION_FOUND = 5013
}
