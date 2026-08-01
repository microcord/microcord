package com.discord.widgets.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthBirthdayDatePickerContractTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
    private val source = File(
        root,
        "app/src/main/java/com/discord/widgets/auth/AuthFragments.kt",
    ).readText()

    @Test
    fun `birthday selection uses the first party Discord dialog contract`() {
        assertFalse(source.contains("android.app.DatePickerDialog"))
        listOf(
            "import com.discord.dialogs.DatePickerDialog",
            "DatePickerDialog.show(",
            "parentFragmentManager",
            "getString(R.string.age_gate_date_of_birth)",
            "current ?: subtractYearsFromToday(10)",
            "subtractYearsFromToday(3)",
            "timeInMillis = ClockFactory.get().currentTimeMillis()",
            "callback,",
        ).forEach { assertTrue("AuthFragments.kt must contain $it", source.contains(it)) }
    }
}
