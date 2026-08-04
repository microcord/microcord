package com.discord.api.activity

data class ActivityAssets(
    val largeImage: String?,
    val largeText: String?,
    val smallImage: String?,
    val smallText: String?,
    val largeUrl: String? = null,
    val smallUrl: String? = null,
    val inviteCoverImage: String? = null,
)
