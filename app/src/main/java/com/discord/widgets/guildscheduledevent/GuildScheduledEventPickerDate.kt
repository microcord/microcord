package com.discord.widgets.guildscheduledevent

import java.io.Serializable
import java.util.Calendar

data class GuildScheduledEventPickerDate(
    val year: Int,
    val month: Int,
    val dayOfMonth: Int,
) : Serializable {
    fun toMillis(): Long = Calendar.getInstance().apply {
        set(year, month, dayOfMonth, 0, 0)
    }.timeInMillis

    companion object {
        fun now(): GuildScheduledEventPickerDate = Calendar.getInstance().let {
            GuildScheduledEventPickerDate(
                it.get(Calendar.YEAR),
                it.get(Calendar.MONTH),
                it.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
}
