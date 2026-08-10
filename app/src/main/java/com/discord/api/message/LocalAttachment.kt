package com.discord.api.message

data class LocalAttachment(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val mimeType: String? = null,
    val size: Long? = null,
    val durationSecs: Double? = null,
    val waveform: String? = null,
)
