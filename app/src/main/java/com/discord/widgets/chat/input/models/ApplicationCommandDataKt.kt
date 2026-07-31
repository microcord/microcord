@file:JvmName("ApplicationCommandDataKt")

package com.discord.widgets.chat.input.models

fun ApplicationCommandData.getCommandId(): String {
    val group = values.firstOrNull { it.type == 2 }
    val subcommand = group?.options?.firstOrNull { it.type == 1 }
        ?: values.firstOrNull { it.type == 1 }
    return listOfNotNull(applicationCommand.id, group?.name, subcommand?.name).joinToString(" ")
}
