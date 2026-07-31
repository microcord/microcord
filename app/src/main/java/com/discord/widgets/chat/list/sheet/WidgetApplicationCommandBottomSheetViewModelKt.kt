@file:JvmName("WidgetApplicationCommandBottomSheetViewModelKt")

package com.discord.widgets.chat.list.sheet

import com.discord.api.commands.ApplicationCommandValue

fun ApplicationCommandValue.flattenOptions(): List<ApplicationCommandValue> =
    options?.takeIf { it.isNotEmpty() }?.flatMap { it.flattenOptions() }
        ?: listOf(this)

fun List<ApplicationCommandValue>.flattenOptions(): List<ApplicationCommandValue> = flatMap { it.flattenOptions() }

fun Any?.toStringTruncateZeroDecimal(): String =
    if (this is Number) toString().removeSuffix(".0") else toString()
