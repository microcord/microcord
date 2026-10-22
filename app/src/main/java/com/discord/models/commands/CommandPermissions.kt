package com.discord.models.commands

import com.discord.api.commands.ApplicationCommandContext
import com.discord.api.commands.ApplicationCommandIndexPermissions
import com.discord.api.commands.ApplicationIntegrationType
import com.discord.api.permission.Permission
import com.discord.utilities.permissions.PermissionUtils

/**
 * Where a command would run, as far as the access rules care.
 *
 * For a thread the reference checks the parent channel throughout, so [channelId],
 * [computedPermissions] and [allowNsfw] must already describe the parent; [isThread] only
 * survives to pick `SEND_MESSAGES_IN_THREADS` over `SEND_MESSAGES`.
 */
data class CommandPermissionContext(
    val userId: Long,
    val roleIds: List<Long> = emptyList(),
    val guildId: Long? = null,
    val channelId: Long? = null,
    val isThread: Boolean = false,
    val isPrivateChannel: Boolean = false,
    val dmRecipientId: Long? = null,
    val computedPermissions: Long? = null,
    val allowNsfw: Boolean = false,
) {
    private val isAdministrator: Boolean
        get() = PermissionUtils.can(Permission.ADMINISTRATOR, computedPermissions)

    val hasBaseAccessPermissions: Boolean
        get() = isPrivateChannel || guildId == null || isAdministrator ||
            (
                PermissionUtils.can(Permission.VIEW_CHANNEL, computedPermissions) &&
                    PermissionUtils.can(Permission.USE_APPLICATION_COMMANDS, computedPermissions)
                )

    val hasSendMessagesPermission: Boolean
        get() = isPrivateChannel || guildId == null || isAdministrator || PermissionUtils.can(
            if (isThread) Permission.SEND_MESSAGES_IN_THREADS else Permission.SEND_MESSAGES,
            computedPermissions,
        )

    /** `GUILD`, `BOT_DM` or `PRIVATE_CHANNEL`, which the command's `contexts` is matched against. */
    fun contextType(commandBotId: Long?): Int = when {
        guildId != null && guildId != 0L -> ApplicationCommandContext.GUILD.value
        commandBotId != null && dmRecipientId == commandBotId -> ApplicationCommandContext.BOT_DM.value
        else -> ApplicationCommandContext.PRIVATE_CHANNEL.value
    }
}

/**
 * Why a command is not offered. The reference keeps the reason rather than a boolean because the
 * command list greys out a denied command instead of hiding it.
 */
enum class CommandAccess {
    ALLOWED,
    NSFW_NOT_ALLOWED,
    CONTEXT_NOT_ALLOWED,
    MISSING_BASE_PERMISSIONS,
    CHANNEL_DENIED,
    USER_DENIED,
}

/**
 * Ported from the reference's `CommandPermissionUtils.hasAccess`, which its comments pin to the
 * server's `application_command_permissions.py`. The order of the checks is load-bearing: an
 * explicit command-level grant outranks an application-level denial, but a channel denial at
 * either level outranks both.
 */
fun ApplicationCommand.commandAccess(
    context: CommandPermissionContext,
    isGuildInstalled: Boolean = false,
    isUserInstalled: Boolean = false,
    commandBotId: Long? = null,
): CommandAccess {
    if (nsfw && !context.allowNsfw) return CommandAccess.NSFW_NOT_ALLOWED

    val contextType = context.contextType(commandBotId)
    if (contexts != null) {
        if (contextType !in contexts) return CommandAccess.CONTEXT_NOT_ALLOWED
    } else if (!builtIn) {
        if (dmPermission == false && contextType == ApplicationCommandContext.BOT_DM.value) {
            return CommandAccess.CONTEXT_NOT_ALLOWED
        }
        if (contextType == ApplicationCommandContext.PRIVATE_CHANNEL.value) {
            return CommandAccess.CONTEXT_NOT_ALLOWED
        }
    }

    if (builtIn) return CommandAccess.ALLOWED

    val guildId = context.guildId?.takeIf { it != 0L } ?: return CommandAccess.ALLOWED

    if (PermissionUtils.can(Permission.ADMINISTRATOR, context.computedPermissions)) {
        return CommandAccess.ALLOWED
    }
    if (isUserInstalled && integrationTypes?.contains(ApplicationIntegrationType.USER_INSTALL.value) == true) {
        return CommandAccess.ALLOWED
    }
    if (!context.hasBaseAccessPermissions &&
        isGuildInstalled &&
        (
            integrationTypes.isNullOrEmpty() ||
                integrationTypes.contains(ApplicationIntegrationType.GUILD_INSTALL.value)
            )
    ) {
        return CommandAccess.MISSING_BASE_PERMISSIONS
    }

    if (context.channelId != null) {
        val commandChannel = commandPermissions.allowedForChannel(context.channelId, guildId)
        if (commandChannel == false) return CommandAccess.CHANNEL_DENIED
        if (commandChannel != true &&
            applicationPermissions.allowedForChannel(context.channelId, guildId) == false
        ) {
            return CommandAccess.CHANNEL_DENIED
        }
    }

    when (commandPermissions.allowedForUser(guildId, context.roleIds)) {
        true -> return CommandAccess.ALLOWED
        false -> return CommandAccess.USER_DENIED
        null -> Unit
    }
    if (applicationPermissions.allowedForUser(guildId, context.roleIds) == false) {
        return CommandAccess.USER_DENIED
    }

    val required = defaultMemberPermissions ?: return CommandAccess.ALLOWED
    val granted = required != DISABLED_BY_DEFAULT_PERMISSIONS &&
        PermissionUtils.can(required, context.computedPermissions)
    return if (granted) CommandAccess.ALLOWED else CommandAccess.USER_DENIED
}

fun ApplicationCommand.hasAccess(
    context: CommandPermissionContext,
    isGuildInstalled: Boolean = false,
    isUserInstalled: Boolean = false,
    commandBotId: Long? = null,
): Boolean =
    commandAccess(context, isGuildInstalled, isUserInstalled, commandBotId) == CommandAccess.ALLOWED

/** How the command surfaces reach it: the owning application carries the install types and bot. */
fun ApplicationCommand.hasAccess(
    context: CommandPermissionContext,
    application: Application?,
): Boolean = hasAccess(
    context = context,
    // A command that arrived over the gateway rather than the index carries no install types;
    // those are the classic guild-installed bots.
    isGuildInstalled = application == null || application.installationTypes.isEmpty() ||
        ApplicationIntegrationType.GUILD_INSTALL.value in application.installationTypes,
    isUserInstalled = application != null &&
        ApplicationIntegrationType.USER_INSTALL.value in application.installationTypes,
    commandBotId = application?.botId,
)

/**
 * `null` when these permissions say nothing about the channel, which lets the caller fall through
 * to the next level rather than treating silence as a denial.
 */
private fun ApplicationCommandIndexPermissions?.allowedForChannel(channelId: Long, guildId: Long): Boolean? {
    val channels = this?.channels ?: return null
    return channels[channelId] ?: channels[allChannelsSentinel(guildId)]
}

private fun ApplicationCommandIndexPermissions?.allowedForUser(guildId: Long, roleIds: List<Long>): Boolean? {
    this ?: return null
    user?.let { return it }
    val roleValues = roleIds.mapNotNull { roles?.get(it) }
    if (roleValues.any { it }) return true
    if (roleValues.isNotEmpty()) return false
    return roles?.get(guildId)
}

/** The server denotes "All Channels" with the guild id minus one. */
private fun allChannelsSentinel(guildId: Long): Long = guildId - 1L

/** `default_member_permissions` of 0 means only an administrator may run the command. */
private const val DISABLED_BY_DEFAULT_PERMISSIONS = 0L
