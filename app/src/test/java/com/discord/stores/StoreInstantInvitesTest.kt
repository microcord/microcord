package com.discord.stores

import com.discord.models.domain.ModelInvite
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreInstantInvitesTest {
    private val server = MockWebServer()

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun `a dead code resolves to Invalid rather than sitting on Loading`() {
        val state = fetch(
            MockResponse().setResponseCode(404)
                .setBody("""{"message":"Unknown Invite","code":10006}"""),
        )

        assertEquals(StoreInstantInvites.InviteState.Invalid, state)
    }

    @Test
    fun `a 404 with no coded body is still Invalid`() {
        val state = fetch(MockResponse().setResponseCode(404).setBody(""))

        assertEquals(StoreInstantInvites.InviteState.Invalid, state)
    }

    @Test
    fun `a transport failure stays retryable`() {
        val state = fetch(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(StoreInstantInvites.InviteState.LoadFailed, state)
    }

    @Test
    fun `a live code resolves`() {
        val state = fetch(MockResponse().setBody("""{"code":"abc","guild":{"id":"1","name":"Guild"}}"""))

        assertEquals("abc", (state as StoreInstantInvites.InviteState.Resolved).invite.code)
    }

    /** Drives one `fetchInviteIfNotLoaded` to whatever terminal state it lands on. */
    private fun fetch(response: MockResponse): StoreInstantInvites.InviteState {
        server.enqueue(response)
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val store = StoreInstantInvites(dispatcher, deck, restApi = { api })
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val key = ModelInvite.getInviteStoreKey(INVITE_CODE, null)

        store.fetchInviteIfNotLoaded(INVITE_CODE)

        // The REST call runs on a real IO scheduler, so the terminal transition is only queued on
        // the TestScheduler once the round trip finishes.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            scheduler.triggerActions()
            val state = store.getKnownInvites()[key]
            if (state != null && state != StoreInstantInvites.InviteState.Loading) return state
            Thread.sleep(10)
        }
        throw AssertionError("invite never left Loading")
    }

    private val api: RestAPIInterface by lazy {
        RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
            .build(RestAPIInterface::class.java)
    }

    private companion object {
        const val INVITE_CODE = "abc"
    }
}
