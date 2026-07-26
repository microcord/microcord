package com.discord.api.commands

/** Grants or denies one role or user access to an [ApplicationCommand]. */
data class ApplicationCommandPermission(
    val id: Long,
    val type: ApplicationCommandPermissionType,
    val permission: Boolean,
)
