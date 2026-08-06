package com.discord.utilities.attachments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentUrlRefresherTest {

    /** `ex` is unix seconds in hex; 0x65d903de is 2024-02-23T20:41:34Z. */
    private val signed = "https://cdn.discordapp.com/attachments/1/2/a.png?ex=65d903de&is=65c68ede&hm=abc"
    private val expiresAtMs = 0x65d903deL * 1000L

    @Test
    fun `a lapsed signature is expired`() {
        assertTrue(AttachmentUrlRefresher.isExpired(signed, expiresAtMs + 1))
    }

    @Test
    fun `a link is renewed shortly before it lapses, not after`() {
        // Renewing exactly on the deadline would hand out a link that dies in transit.
        assertTrue(AttachmentUrlRefresher.isExpired(signed, expiresAtMs - 30_000L))
        assertFalse(AttachmentUrlRefresher.isExpired(signed, expiresAtMs - 120_000L))
    }

    @Test
    fun `an unsigned link never expires`() {
        assertFalse(
            AttachmentUrlRefresher.isExpired(
                "https://cdn.discordapp.com/attachments/1/2/a.png",
                Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `a malformed expiry is treated as unsigned rather than crashing`() {
        assertFalse(
            AttachmentUrlRefresher.isExpired(
                "https://cdn.discordapp.com/attachments/1/2/a.png?ex=notahexnumber",
                Long.MAX_VALUE,
            ),
        )
    }
}
