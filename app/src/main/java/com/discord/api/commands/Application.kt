package com.discord.api.commands

import com.discord.api.user.User
import java.io.Serializable

/**
 * An application as it appears in the slash-command picker.
 *
 * This is the command-surface projection of an application and is a different type from
 * `com.discord.api.application.Application`, which models the full application record.
 */
class Application(
    val id: Long,
    val name: String,
    val icon: String?,
    val commandCount: Int,
    val bot: User?,
    val builtIn: Boolean,
) : Serializable
