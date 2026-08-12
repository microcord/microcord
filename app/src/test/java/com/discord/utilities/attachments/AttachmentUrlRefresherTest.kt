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
    fun `a link is renewed an hour before it lapses, not after`() {
        // A link can sit between being built and being fetched, so the window covers the gap.
        assertTrue(AttachmentUrlRefresher.isExpired(signed, expiresAtMs - 59L * 60_000L))
        assertFalse(AttachmentUrlRefresher.isExpired(signed, expiresAtMs - 61L * 60_000L))
    }

    @Test
    fun `only attachment paths are treated as signed`() {
        assertFalse(
            AttachmentUrlRefresher.isExpired(
                "https://cdn.discordapp.com/avatars/1/hash.webp?ex=65d903de",
                expiresAtMs,
            ),
        )
        assertTrue(
            AttachmentUrlRefresher.isExpired(
                "https://cdn.discordapp.com/ephemeral-attachments/1/2/a.png?ex=65d903de",
                expiresAtMs,
            ),
        )
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
