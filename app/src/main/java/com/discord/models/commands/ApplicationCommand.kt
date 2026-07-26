package com.discord.models.commands

import com.discord.api.commands.ApplicationCommandPermission

/**
 * A command the chat input can offer, whether it comes from an application ([RemoteApplicationCommand])
 * or from the client itself ([BuiltInCommand]).
 *
 * [permissions] is the flattened `id -> allowed` view of the API's permission overwrites; it is
 * consulted by [hasPermission] together with [defaultPermissions].
 */
abstract class ApplicationCommand protected constructor(
    val id: String,
    val applicationId: Long,
    val name: String,
    val description: String? = null,
    val descriptionRes: Int? = null,
    val options: List<ApplicationCommandOption>,
    val builtIn: Boolean = false,
    val guildId: Long? = null,
    val version: String? = null,
    val defaultPermissions: Boolean? = null,
    val permissions: Map<Long, Boolean>? = null,
    val execute: ((Map<String, Any?>) -> String)? = null,
)

/**
 * Whether [userId], holding [roles], may invoke this command.
 *
 * A per-user overwrite wins outright; otherwise the first role overwrite that grants access wins;
 * otherwise the command's default applies. Commands without a default are unrestricted.
 */
fun ApplicationCommand.hasPermission(userId: Long, roles: List<Long>): Boolean {
    val defaultPermissions = defaultPermissions ?: return true
    val permissions = permissions
    if (permissions != null) {
        permissions[userId]?.let { return it }
        roles.map { permissions[it] }.firstOrNull { it == true }?.let { return it }
    }
    return defaultPermissions
}

/** A command supplied by an application and advertised over the gateway or REST. */
class RemoteApplicationCommand(
    id: String,
    applicationId: Long,
    name: String,
    description: String? = null,
    options: List<ApplicationCommandOption>,
    guildId: Long?,
    version: String?,
    defaultPermissions: Boolean? = null,
    permissions: List<ApplicationCommandPermission>? = null,
    execute: ((Map<String, Any?>) -> String)? = null,
) : ApplicationCommand(
    id = id,
    applicationId = applicationId,
    name = name,
    description = description,
    options = options,
    guildId = guildId,
    version = version,
    defaultPermissions = defaultPermissions,
    permissions = permissions?.associate { it.id to it.permission },
    execute = execute,
)

/** A command implemented locally by the client; it renders from resources and never hits the API. */
class BuiltInCommand(
    id: String,
    applicationId: Long,
    name: String,
    descriptionRes: Int? = null,
    options: List<ApplicationCommandOption>,
    execute: ((Map<String, Any?>) -> String)? = null,
) : ApplicationCommand(
    id = id,
    applicationId = applicationId,
    name = name,
    descriptionRes = descriptionRes,
    options = options,
    builtIn = true,
    execute = execute,
)

/**
 * One leaf of a command that declares sub-commands.
 *
 * The id is synthesised from the root command's id plus the (optional) group and leaf names
 * separated by spaces, so `/role add` and `/role remove` remain distinguishable in the picker.
 */
class ApplicationSubCommand(
    val rootCommand: ApplicationCommand,
    val subCommandName: String,
    val parentGroupName: String? = null,
    applicationId: Long,
    name: String,
    description: String? = null,
    descriptionRes: Int? = null,
    options: List<ApplicationCommandOption>,
    guildId: Long?,
    defaultPermissions: Boolean? = null,
    permissions: List<ApplicationCommandPermission>? = null,
    version: String?,
) : ApplicationCommand(
    id = if (parentGroupName != null) {
        "${rootCommand.id} $parentGroupName $subCommandName"
    } else {
        "${rootCommand.id} $subCommandName"
    },
    applicationId = applicationId,
    name = name,
    description = description,
    descriptionRes = descriptionRes,
    options = options,
    guildId = guildId,
    version = version,
    defaultPermissions = defaultPermissions,
    permissions = permissions?.associate { it.id to it.permission },
)
