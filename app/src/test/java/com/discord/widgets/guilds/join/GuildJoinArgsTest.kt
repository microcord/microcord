package com.discord.widgets.guilds.join

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuildJoinArgsTest {
    @Test fun `join args preserve callbacks without printing session ids`() {
        val args = JoinArgs(
            guildId = 1L,
            isLurker = true,
            sessionId = "secret-session",
            directoryChannelId = 2L,
            errorClass = javaClass,
            onNext = {},
        )

        assertFalse(args.toString().contains("secret-session"))
        assertTrue(args.toString().contains("sessionId=<redacted>"))
    }
}
