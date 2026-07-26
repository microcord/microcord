package com.discord.api.application

/** A named image an application registered for use in Rich Presence. */
data class ApplicationAsset(
    val id: Long,
    val type: Int,
    val name: String,
)
