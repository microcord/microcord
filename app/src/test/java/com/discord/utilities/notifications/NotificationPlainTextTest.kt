package com.discord.utilities.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPlainTextTest {

    @Test
    fun markupReadsAsNames() {
        val text = NotificationData.plainText(
            "hi <@1> and <@!2> in <#3> for <@&4> <:wave:5> <a:dance:6>",
            user = { mapOf(1L to "ana", 2L to "bo")[it] },
            channel = { "general".takeIf { _ -> it == 3L } },
            role = { "mods".takeIf { _ -> it == 4L } },
        )
        assertEquals("hi @ana and @bo in #general for @mods :wave: :dance:", text)
    }

    @Test
    fun unresolvedMentionsUseDiscordsPlaceholders() {
        assertEquals("@unknown-user #deleted-channel @deleted-role", NotificationData.plainText("<@1> <#2> <@&3>"))
    }
}
