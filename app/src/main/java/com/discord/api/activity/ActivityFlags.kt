package com.discord.api.activity

object ActivityFlags {
    const val INSTANCE = 1
    const val JOIN = 2

    /** Removed from official clients; still sent by older rich-presence integrations. */
    const val SPECTATE = 4
    const val SYNC = 16
    const val PLAY = 32
    const val PARTY_PRIVACY_FRIENDS = 64
    const val PARTY_PRIVACY_VOICE_CHANNEL = 128
    const val EMBEDDED = 256
    const val CONTEXTLESS = 512
}
