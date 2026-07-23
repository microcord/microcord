package com.discord.utilities.streams

import com.discord.stores.StoreApplicationStreamPreviews

interface StreamContext {
    val stream: ApplicationStream
    val preview: StoreApplicationStreamPreviews.StreamPreview?

    interface ApplicationStream {
        val encodedStreamKey: String
    }

    enum class Joinability {
        CAN_CONNECT,
        VOICE_CHANNEL_FULL,
        MISSING_PERMISSIONS,
    }
}
