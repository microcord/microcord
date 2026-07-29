package com.discord.widgets.guild_role_subscriptions.tier.create

import com.discord.restapi.RestAPIInterface
import com.discord.stores.Dispatcher
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.widgets.guild_role_subscriptions.tier.model.GuildRoleSubscriptionTier
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.schedulers.Schedulers

class CreateGuildRoleSubscriptionTierViewModelTest {

    @Before
    fun setUpMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(object : RxAndroidSchedulersHook() {
            override fun getMainThreadScheduler() = Schedulers.immediate()
        })
    }

    @After
    fun resetMainScheduler() = RxAndroidPlugins.getInstance().reset()

    @Test
    fun `each wizard step uses the reference gate`() {
        val viewModel = viewModel()
        val details = GuildRoleSubscriptionTier(
            name = "Supporter",
            priceTier = 499,
            image = "image",
            description = "description",
        )

        assertFalse(viewModel.requireViewState().canProceed)
        viewModel.onTierUpdated(details)
        assertTrue(viewModel.requireViewState().canProceed)

        viewModel.goToNextStep()
        assertTrue(viewModel.requireViewState().canProceed)
        viewModel.goToNextStep()
        assertTrue(viewModel.requireViewState().canProceed)
        viewModel.goToNextStep()
        assertFalse(viewModel.requireViewState().canProceed)

        viewModel.onTierUpdated(details.copy(memberColor = 0x123456))
        assertTrue(viewModel.requireViewState().canProceed)
        viewModel.goToNextStep()
        assertFalse(viewModel.requireViewState().canProceed)
    }

    @Test
    fun `back from details enters the close sentinel step`() {
        val viewModel = viewModel()

        viewModel.goToPreviousStep()

        assertTrue(viewModel.requireViewState().currentStep < 0)
        assertFalse(viewModel.requireViewState().canProceed)
    }

    private fun viewModel(): CreateGuildRoleSubscriptionTierViewModel {
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(Schedulers.immediate())
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val roleSubscriptions = StoreGuildRoleSubscriptions(dispatcher, deck, { null })
        return CreateGuildRoleSubscriptionTierViewModel(
            guildId = 1,
            groupListingId = 2,
            restApi = noCallsApi(),
            storeGuildRoleSubscriptions = roleSubscriptions,
            storeGuilds = guilds,
            observationDeck = deck,
            storeStateObservable = Observable.never(),
        )
    }

    private fun noCallsApi() = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as RestAPIInterface
}
