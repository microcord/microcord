package com.discord.api.guild

/** A guild's vanity invite code and how many times it has been used. */
data class VanityUrlResponse(
    val code: String,
    val uses: Int,
)
