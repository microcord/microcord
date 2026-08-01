package com.discord.widgets.guildscheduledevent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuildScheduledEventPickerDialogContractTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }

    @Test
    fun `settings uses only first party picker dialogs and preserves event bounds`() {
        val source = java("WidgetGuildScheduledEventSettings.kt")
        assertFalse(source.contains("android.app.DatePickerDialog"))
        assertFalse(source.contains("android.app.TimePickerDialog"))
        assertTrue(source.contains("com.discord.dialogs.DatePickerDialog"))
        assertTrue(source.contains("ClockFactory.get().currentTimeMillis()"))
        assertTrue(source.contains("maxOf(model.startDate.toMillis(), ClockFactory.get().currentTimeMillis())"))
        assertTrue(source.contains("generateDefaultEndDateTime(model.startDate, model.startTime).first"))
        assertTrue(source.contains("showDateErrorToast("))
    }

    @Test
    fun `time picker is recreatable cancellable and follows the device clock format`() {
        val source = java("GuildScheduledEventTimePickerDialog.kt")
        assertTrue(source.contains(": AppDialog(R.layout.guild_scheduled_event_time_picker_dialog)"))
        assertTrue(source.contains("DateFormat.is24HourFormat(requireContext())"))
        assertTrue(source.contains("onSaveInstanceState"))
        assertTrue(source.contains("setFragmentResult("))
        assertTrue(source.contains("guild_scheduled_event_time_picker_cancel"))

        val layout = File(
            root,
            "app/src/main/res/layout/guild_scheduled_event_time_picker_dialog.xml",
        ).readText()
        assertTrue(layout.contains("android:timePickerMode=\"spinner\""))
        assertTrue(layout.contains("@string/cancel"))
        assertTrue(layout.contains("@string/done"))
    }

    private fun java(name: String) = File(
        root,
        "app/src/main/java/com/discord/widgets/guildscheduledevent/$name",
    ).readText()
}
