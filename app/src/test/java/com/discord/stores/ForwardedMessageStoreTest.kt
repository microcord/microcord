package com.discord.stores

import com.discord.api.message.Message as ApiMessage
import com.discord.api.message.MessageFlags
import com.discord.api.message.MessageReference
import com.discord.api.message.MessageSnapshot
import com.discord.models.message.Message
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import rx.Observable

class ForwardedMessageStoreTest {
    @Before
    fun initializePersistence() {
        Persister.init(Files.createTempDirectory("forwarded-message").toFile(), Observable.never())
    }

    @Test
    fun gatewayUpdatesKeepSnapshotsAndLogoutClearsThem() {
        val store = StoreMessages(ObservationDeck())
        store.handleChannelSelected(20)
        store.handleMessageCreate(forward("sanitized"))

        store.handleMessageUpdate(
            ApiMessage(
                id = 10,
                channelId = 20,
                pinned = true,
            ),
        )

        val updated = store.holder.getMessages(20).single()
        assertEquals("sanitized", updated.messageSnapshots!!.single().message.content)
        assertEquals(true, updated.pinned)

        store.handlePreLogout()
        assertNull(store.holder.getMessages(20).firstOrNull())
    }

    private fun forward(content: String) = Message(
        id = 10,
        channelId = 20,
        type = 0,
        flags = MessageFlags.HAS_SNAPSHOT,
        messageReference = MessageReference(1, 2, 3, type = 1),
        messageSnapshots = listOf(MessageSnapshot(ApiMessage(3, 2, content = content))),
    )
}
