package com.discord.stores

import com.discord.stores.StoreMessageUploads.MessageUploadState
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.rest.UploadProgress
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreMessageUploadsTest {
    @Test fun snapshotsProgressAndCleansEveryTerminalPath() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreMessageUploads(deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val observed = mutableListOf<MessageUploadState>()
        store.observeUploadProgress("nonce").subscribe(observed::add)

        dispatcher.schedule { store.onPreprocessing("nonce", 2, null, null) }
        scheduler.triggerActions()
        dispatcher.schedule { store.bindUpload("nonce", emptyList(), UploadProgress(2, 4)) }
        scheduler.triggerActions()
        dispatcher.schedule { store.onCancel("nonce") }
        scheduler.triggerActions()

        assertEquals(MessageUploadState.None, observed.first())
        assertEquals(MessageUploadState.Preprocessing(2, null, null), observed[1])
        assertEquals(MessageUploadState.Uploading(emptyList(), UploadProgress(2, 4)), observed[2])
        assertEquals(MessageUploadState.None, observed.last())

        listOf<(String) -> Unit>(store::onSuccess, store::onFailure, store::onCancel).forEach { cleanup ->
            dispatcher.schedule { store.onPreprocessing("nonce", 1, "a", "text/plain"); cleanup("nonce") }
            scheduler.triggerActions()
            assertEquals(MessageUploadState.None, store.getUploadProgress("nonce"))
        }
    }
}
