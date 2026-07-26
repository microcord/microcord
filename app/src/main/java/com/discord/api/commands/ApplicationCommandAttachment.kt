package com.discord.api.commands

/** An attachment uploaded as the value of an `ATTACHMENT` command option. */
data class ApplicationCommandAttachment(
    val filename: String,
    val id: String,
)
