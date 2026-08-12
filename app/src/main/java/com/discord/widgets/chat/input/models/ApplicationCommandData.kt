package com.discord.widgets.chat.input.models

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand

/**
 * A slash command the composer has selected, together with the arguments filled in so far.
 *
 * [validInputs] is what gates the send button: it is `false` until every required option of
 * [applicationCommand] has a value, so an incomplete command can be typed but not sent.
 */
data class ApplicationCommandData(
    val application: Application,
    val applicationCommand: ApplicationCommand,
    val values: List<ApplicationCommandValue>,
    val validInputs: Boolean = false,
    /** Set when the files staged in the composer outnumber the command's attachment options. */
    val hasUnusableAttachments: Boolean = false,
)
