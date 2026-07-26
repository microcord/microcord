package com.discord.models.commands

import com.discord.api.commands.ApplicationCommandType
import com.discord.api.commands.CommandChoice

/**
 * One argument of an [ApplicationCommand].
 *
 * Sub-command and sub-command-group options nest further options in [options]; leaf options may
 * restrict their input via [choices], [channelTypes] or the numeric [minValue]/[maxValue] bounds,
 * or defer to the application through [autocomplete].
 */
data class ApplicationCommandOption(
    val type: ApplicationCommandType,
    val name: String,
    val description: String? = null,
    val descriptionRes: Int? = null,
    val required: Boolean,
    val default: Boolean,
    val channelTypes: List<Int>? = emptyList(),
    val choices: List<CommandChoice>? = null,
    val options: List<ApplicationCommandOption>? = null,
    val autocomplete: Boolean = false,
    val minValue: Number? = null,
    val maxValue: Number? = null,
)

/** Marker for the per-component state a message's interactive components can be in. */
sealed class ComponentState
