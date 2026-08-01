package com.discord.utilities.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareContractParserTest {
    @Test
    fun parsesTextSubjectAndSingleOrMultipleContentAttachments() {
        assertEquals(
            NormalizedShare("hello", emptyList()),
            ShareContractParser.parse(ShareContractParser.ACTION_SEND, "hello", "ignored", emptyList()),
        )
        assertEquals(
            NormalizedShare("subject", listOf("content://media/1")),
            ShareContractParser.parse(ShareContractParser.ACTION_SEND, " ", "subject", listOf("content://media/1")),
        )
        assertEquals(
            NormalizedShare(null, listOf("content://media/1", "content://media/2")),
            ShareContractParser.parse(
                ShareContractParser.ACTION_SEND_MULTIPLE,
                null,
                null,
                listOf("content://media/1", "content://media/2"),
            ),
        )
    }

    @Test
    fun rejectsEmptyWrongActionFileAndCredentialBearingAttachments() {
        assertNull(ShareContractParser.parse(ShareContractParser.ACTION_SEND, " ", null, emptyList()))
        assertNull(ShareContractParser.parse("android.intent.action.VIEW", "text", null, emptyList()))
        assertNull(ShareContractParser.parse(ShareContractParser.ACTION_SEND, null, null, listOf("file:///tmp/a")))
        assertNull(ShareContractParser.parse(ShareContractParser.ACTION_SEND, null, null, listOf("content://user:pass@media/1")))
        assertNull(ShareContractParser.parse(ShareContractParser.ACTION_SEND, null, null, listOf("content://media/1", "content://media/2")))
    }

    @Test
    fun acceptsOnlyDiscordOrManticordSdkUris() {
        assertTrue(ShareContractParser.isSdkUri("discord://sdk/connect"))
        assertTrue(ShareContractParser.isSdkUri("manticord://sdk/connect"))
        assertTrue(ShareContractParser.isSdkUri("DISCORD://SDK/connect"))
        assertFalse(ShareContractParser.isSdkUri("https://sdk/connect"))
        assertFalse(ShareContractParser.isSdkUri("discord://app/connect"))
        assertFalse(ShareContractParser.isSdkUri("file://sdk/connect"))
        assertFalse(ShareContractParser.isSdkUri("discord://user:pass@sdk/connect"))
        assertFalse(ShareContractParser.isSdkUri("discord://sdk:8443/connect"))
    }
}
