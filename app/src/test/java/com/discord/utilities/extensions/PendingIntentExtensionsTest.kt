package com.discord.utilities.extensions

import android.app.PendingIntent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingIntentExtensionsTest {
    @Test
    fun `mutable flag is explicit only from api 31`() {
        val existing = PendingIntent.FLAG_UPDATE_CURRENT

        assertEquals(existing, mutablePendingIntentFlag(existing, Build.VERSION_CODES.R))
        assertEquals(
            existing or PendingIntent.FLAG_MUTABLE,
            mutablePendingIntentFlag(existing, Build.VERSION_CODES.S),
        )
    }
}
