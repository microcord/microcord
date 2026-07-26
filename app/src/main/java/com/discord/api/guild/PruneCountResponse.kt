package com.discord.api.guild

/** Response to the prune-preview endpoint: how many members the prune would remove. */
data class PruneCountResponse(
    val pruned: Int,
)
