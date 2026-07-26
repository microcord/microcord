package com.discord.api.guildrolesubscription

/** An uploaded image backing a subscription group or tier listing. */
data class ImageAsset(
    val id: Long,
    val size: Int?,
    val mimeType: String,
    val filename: String?,
    val width: Int?,
    val height: Int?,
)
