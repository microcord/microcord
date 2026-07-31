@file:JvmName("WidgetApplicationCommandBottomSheetKt")

package com.discord.widgets.chat.list.sheet

import com.discord.api.commands.ApplicationCommandData
import com.discord.api.commands.ApplicationCommandValue

fun ApplicationCommandData.toSlashCommandCopyString(
    commandValues: Map<String, WidgetApplicationCommandBottomSheetViewModel.SlashCommandParam>,
): String = buildString {
    append('/')
    append(name)
    append(' ')
    append(options.orEmpty().joinToString(" ") { it.toSlashCommandCopyString(commandValues) })
}

fun ApplicationCommandValue.toSlashCommandCopyString(
    commandValues: Map<String, WidgetApplicationCommandBottomSheetViewModel.SlashCommandParam>,
): String = options?.let { children ->
    "$name ${children.joinToString(" ") { it.toSlashCommandCopyString(commandValues) }}"
} ?: "$name:${commandValues[name]?.copyText}"
