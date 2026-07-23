package com.discord.api.activity

data class ActivityMetadata(
    val contextUri: String?,
    val albumId: String?,
    val artistIds: List<String>?,
    val buttonUrls: List<String>?,
)
