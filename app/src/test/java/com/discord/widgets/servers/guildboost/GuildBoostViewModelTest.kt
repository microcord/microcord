package com.discord.widgets.servers.guildboost

import com.android.billingclient.api.Purchase
import com.discord.R
import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.models.domain.ModelSubscription
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.stores.Dispatcher
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.stores.StoreGuildBoost
import com.discord.stores.StoreSubscriptions
import com.discord.utilities.time.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.observers.TestSubscriber
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers

class GuildBoostViewModelTest {
    private val dispatcher = Dispatcher(Schedulers.immediate())
    private lateinit var boosts: StoreGuildBoost
    private lateinit var subscriptions: StoreSubscriptions

    @Before
    fun setUp() {
        RxJavaHooks.setOnComputationScheduler { Schedulers.immediate() }
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        RxAndroidPlugins.getInstance().registerSchedulersHook(object : RxAndroidSchedulersHook() {
            override fun getMainThreadScheduler() = Schedulers.immediate()
        })
        boosts = StoreGuildBoost(dispatcher, restApi = { null })
        subscriptions = StoreSubscriptions(dispatcher = dispatcher, restApi = { null })
    }

    @After
    fun tearDown() {
        RxJavaHooks.reset()
        RxAndroidPlugins.getInstance().reset()
    }

    @Test
    fun `store combination preserves loading failure and loaded states`() {
        val viewModel = viewModel()
        viewModel.handleStoreState(state(guildBoostState = StoreGuildBoost.State.Loading))
        assertEquals(GuildBoostViewModel.ViewState.Loading, viewModel.requireViewState())

        viewModel.handleStoreState(state(guildBoostState = StoreGuildBoost.State.Failure))
        assertEquals(GuildBoostViewModel.ViewState.Failure, viewModel.requireViewState())

        viewModel.handleStoreState(state())
        assertEquals(guild, (viewModel.requireViewState() as GuildBoostViewModel.ViewState.Loaded).guild)
    }

    @Test
    fun `available slot routes directly to confirmation`() {
        val viewModel = viewModel()
        viewModel.handleStoreState(state(slots = mapOf(7L to slot(7L))))
        val events = TestSubscriber<GuildBoostViewModel.Event>()
        viewModel.observeEvents().subscribe(events)

        viewModel.subscribeClicked("Header")

        events.assertValue(GuildBoostViewModel.Event.LaunchSubscriptionConfirmation(guild.id, 7L))
    }

    @Test
    fun `subscription restrictions run before purchase routing`() {
        assertEvent(
            subscription(paymentGateway = ModelSubscription.PaymentGateway.STRIPE.intRepresentation),
            GuildBoostViewModel.Event.ShowDesktopAlertDialog,
        )
        assertEvent(
            subscription(renewalMutations = ModelSubscription.SubscriptionRenewalMutations(emptyList(), null)),
            GuildBoostViewModel.Event.ShowBlockedPlanSwitchAlertDialog(
                R.string.premium_guild_subscription_out_of_slots_title,
                R.string.premium_guild_subscription_out_of_slots_pending_plan_change,
            ),
        )
        assertEvent(
            subscription(status = ModelSubscription.Status.CANCELED),
            GuildBoostViewModel.Event.ShowBlockedPlanSwitchAlertDialog(
                R.string.premium_guild_subscription_out_of_slots_title,
                R.string.premium_guild_subscription_out_of_slots_canceled_subscription,
            ),
        )
        assertEvent(
            subscription(status = ModelSubscription.Status.ACCOUNT_HOLD),
            GuildBoostViewModel.Event.ShowBlockedPlanSwitchAlertDialog(
                R.string.premium_guild_subscription_out_of_slots_account_hold_title,
                R.string.premium_guild_subscription_out_of_slots_account_hold,
            ),
        )
    }

    @Test
    fun `purchase routing handles unacknowledged records and carries current plan`() {
        val purchase = purchase(acknowledged = false)
        assertEvent(subscription(), GuildBoostViewModel.Event.UnacknowledgedPurchase, listOf(purchase))
        assertEvent(
            subscription(plan = "premium_guild_1_monthly"),
            GuildBoostViewModel.Event.LaunchPurchaseSubscription(
                "Footer",
                guild.id,
                "premium_guild_1_monthly",
            ),
        )
    }

    @Test
    fun `progress follows reference tier segments`() {
        val standard = intArrayOf(2, 7, 14)
        assertEquals(0, WidgetGuildBoost.totalProgress(0, 0, standard))
        assertEquals(33, WidgetGuildBoost.totalProgress(1, 2, standard))
        assertEquals(67, WidgetGuildBoost.totalProgress(2, 7, standard))
        assertEquals(100, WidgetGuildBoost.totalProgress(3, 14, standard))
    }

    @Test
    fun `missing Play products fail closed`() {
        assertFalse(WidgetGuildBoost.hasPurchasableGuildBoostPlan(emptySet()))
        assertTrue(WidgetGuildBoost.hasPurchasableGuildBoostPlan(setOf("premium_guild_1_monthly")))
    }

    private fun assertEvent(
        subscription: ModelSubscription?,
        expected: GuildBoostViewModel.Event,
        purchases: List<Purchase> = emptyList(),
    ) {
        val viewModel = viewModel()
        viewModel.handleStoreState(state(subscription = subscription, purchases = purchases))
        val events = TestSubscriber<GuildBoostViewModel.Event>()
        viewModel.observeEvents().subscribe(events)
        viewModel.subscribeClicked("Footer")
        events.assertValue(expected)
    }

    private fun viewModel() = GuildBoostViewModel(
        guildId = guild.id,
        storeGuildBoost = boosts,
        storeSubscriptions = subscriptions,
        clock = Clock { 1_000L },
        storeObservable = Observable.never(),
    )

    private fun state(
        guildBoostState: StoreGuildBoost.State = StoreGuildBoost.State.Loaded(emptyMap()),
        slots: Map<Long, ModelGuildBoostSlot>? = null,
        subscription: ModelSubscription? = null,
        purchases: List<Purchase> = emptyList(),
    ) = GuildBoostViewModel.StoreState(
        slots?.let { StoreGuildBoost.State.Loaded(it) } ?: guildBoostState,
        StoreSubscriptions.SubscriptionsState.Loaded(listOfNotNull(subscription)),
        me,
        guild,
        StoreGooglePlayPurchases.State.Loaded(subscriptionPurchases = purchases),
    )

    private fun slot(id: Long) = ModelGuildBoostSlot(null, id, 100L + id, null, false)

    private fun subscription(
        paymentGateway: Int? = ModelSubscription.PaymentGateway.GOOGLE.intRepresentation,
        renewalMutations: ModelSubscription.SubscriptionRenewalMutations? = null,
        status: ModelSubscription.Status = ModelSubscription.Status.ACTIVE,
        plan: String? = null,
    ) = ModelSubscription(
        id = "subscription",
        createdAt = "",
        canceledAt = null,
        currentPeriodStart = "",
        currentPeriodEnd = "",
        paymentSourceId = null,
        paymentGateway = paymentGateway,
        trialId = null,
        trialEndsAt = null,
        renewalMutations = renewalMutations,
        items = emptyList(),
        paymentGatewayPlanId = plan,
        statusValue = status.intRepresentation,
        typeValue = 1,
    )

    private fun purchase(acknowledged: Boolean) = Purchase(
        """{"packageName":"dev.mantikafasi.manticord","productId":"sku","purchaseToken":"token","purchaseState":0,"acknowledged":$acknowledged}""",
        "signature",
    )

    private val guild = Guild(id = 10L, name = "Guild")
    private val me = MeUser(
        id = 1L,
        username = "me",
        avatar = null,
        banner = null,
        isBot = false,
        isSystemUser = false,
        discriminator = 1,
        premiumTier = PremiumTier.NONE,
        email = null,
        mfaEnabled = false,
        isVerified = true,
        token = null,
        flags = 0,
        publicFlags = 0,
        phoneNumber = null,
        nsfwAllowance = NsfwAllowance.ALLOWED,
        bio = null,
        bannerColor = null,
    )
}
