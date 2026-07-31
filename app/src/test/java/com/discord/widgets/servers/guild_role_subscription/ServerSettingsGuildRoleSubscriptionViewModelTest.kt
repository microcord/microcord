package com.discord.widgets.servers.guild_role_subscription

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.restapi.RestAPIInterface
import com.discord.stores.Dispatcher
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.schedulers.Schedulers

class ServerSettingsGuildRoleSubscriptionViewModelTest {
    @Before
    fun setUpMainScheduler() {
        Persister.init(Files.createTempDirectory("server-role-subscription").toFile(), Observable.never())
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(object : RxAndroidSchedulersHook() {
            override fun getMainThreadScheduler() = Schedulers.immediate()
        })
    }

    @After
    fun resetMainScheduler() = RxAndroidPlugins.getInstance().reset()

    @Test
    fun `loaded listing tracks edits`() {
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(Schedulers.immediate())
        val subscriptions = StoreGuildRoleSubscriptions(dispatcher, deck, restApi = { null })
        val guilds = StoreGuilds(StoreUser(dispatcher = dispatcher, observationDeck = deck), dispatcher, deck)
        val listing = GuildRoleSubscriptionGroupListing(
            id = 9,
            guildId = 1,
            applicationId = 2,
            imageAsset = null,
            description = "Original",
            subscriptionListingsIds = null,
            subscriptionListings = null,
            fullServerGate = false,
        )
        val viewModel = ServerSettingsGuildRoleSubscriptionViewModel(
            guildId = 1,
            restApi = noCallsApi(),
            storeGuildRoleSubscriptions = subscriptions,
            storeGuilds = guilds,
            observationDeck = deck,
            storeObservable = Observable.just(
                ServerSettingsGuildRoleSubscriptionViewModel.StoreState(
                    StoreGuildRoleSubscriptions.GuildRoleSubscriptionGroupState.Loaded(listing),
                    null,
                ),
            ),
        )

        val loaded = viewModel.observeViewState()
            .ofType(ServerSettingsGuildRoleSubscriptionViewModel.ViewState.Loaded::class.java)
            .toBlocking()
            .first()
        assertEquals(9L, loaded.groupListingId)
        assertFalse(loaded.hasChanges)

        viewModel.onGuildRoleSubscriptionGroupUpdated("Changed", null, false)
        assertTrue((viewModel.requireViewState() as ServerSettingsGuildRoleSubscriptionViewModel.ViewState.Loaded).hasChanges)
    }

    private fun noCallsApi() = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as RestAPIInterface
}
