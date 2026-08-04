package com.discord.stores

import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelUserRelationship
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreUserRelationshipNicknamesTest {

    private val directory = Files.createTempDirectory("relationship-nicknames-test").toFile()
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()

    @Before fun setUp() = Persister.init(directory)
    @After fun tearDown() { directory.deleteRecursively() }

    private val store by lazy {
        StoreUserRelationships(
            deck,
            Persister(StoreUserRelationships.CACHE_KEY, mapOf(-1L to -1)),
            Persister(StoreUserRelationships.NICKNAMES_CACHE_KEY, emptyMap()),
        ).also { dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(it), deck)) }
    }

    @Test
    fun `nicknames arrive with READY and survive an add`() {
        dispatch { store.handleConnectionOpen(payload(relationship(7L, nickname = "Ada"))) }
        assertEquals("Ada", store.getNickname(7L))

        dispatch { store.handleRelationshipAdd(relationship(8L, nickname = "Grace")) }
        assertEquals("Grace", store.getNickname(8L))
        assertEquals("Ada", store.getNickname(7L))
    }

    @Test
    fun `a blank nickname is the same as none`() {
        dispatch { store.handleConnectionOpen(payload(relationship(7L, nickname = "   "))) }

        assertNull(store.getNickname(7L))
        assertEquals(mapOf(7L to ModelUserRelationship.TYPE_FRIEND), store.getRelationships())
    }

    @Test
    fun `clearing a nickname removes it`() {
        dispatch { store.handleConnectionOpen(payload(relationship(7L, nickname = "Ada"))) }

        dispatch { store.handleRelationshipAdd(relationship(7L, nickname = null)) }

        assertNull(store.getNickname(7L))
    }

    @Test
    fun `removing the relationship drops the nickname`() {
        dispatch { store.handleConnectionOpen(payload(relationship(7L, nickname = "Ada"))) }

        dispatch { store.handleRelationshipRemove(relationship(7L, nickname = "Ada")) }

        assertNull(store.getNickname(7L))
        assertEquals(emptyMap<Long, Int>(), store.getRelationships())
    }

    private fun dispatch(block: () -> Unit) {
        dispatcher.schedule(block)
        scheduler.triggerActions()
    }

    private fun relationship(id: Long, nickname: String?) = ModelUserRelationship(
        id = id,
        type = ModelUserRelationship.TYPE_FRIEND,
        nickname = nickname,
    )

    private fun payload(vararg relationships: ModelUserRelationship): ModelPayload =
        ModelPayload().apply { this.relationships = relationships.toList() }
}
