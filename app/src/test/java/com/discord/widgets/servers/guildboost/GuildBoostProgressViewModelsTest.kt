package com.discord.widgets.servers.guildboost

import com.discord.models.domain.ModelAppliedGuildBoost
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.models.guild.Guild
import com.discord.stores.Dispatcher
import com.discord.stores.StoreGuildBoost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers

class GuildBoostProgressViewModelsTest {
    private val guild = Guild(id = 10L, name = "Guild")
    private val otherGuild = Guild(id = 20L, name = "Other")
    private lateinit var store: StoreGuildBoost

    @Before
    fun setUp() {
        RxJavaHooks.setOnComputationScheduler { Schedulers.immediate() }
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        RxAndroidPlugins.getInstance().registerSchedulersHook(object : RxAndroidSchedulersHook() {
            override fun getMainThreadScheduler() = Schedulers.immediate()
        })
        store = StoreGuildBoost(Dispatcher(Schedulers.immediate()), restApi = { null })
    }

    @After
    fun tearDown() {
        RxJavaHooks.reset()
        RxAndroidPlugins.getInstance().reset()
    }

    @Test
    fun `confirmation counts only boosts already applied to its guild`() {
        val viewModel = GuildBoostInProgressViewModel(
            guildId = guild.id,
            storeGuildBoost = store,
            subscribeToGuild = { _, _ -> Observable.empty() },
            storeObservable = Observable.never(),
        )

        viewModel.handleStoreState(
            GuildBoostInProgressViewModel.StoreState(
                guild,
                StoreGuildBoost.State.Loaded(
                    mapOf(
                        1L to slot(1L, guild.id),
                        2L to slot(2L, guild.id),
                        3L to slot(3L, otherGuild.id),
                        4L to slot(4L, null),
                    ),
                ),
            ),
        )

        assertEquals(
            GuildBoostInProgressViewModel.ViewState.Loaded(
                GuildBoostInProgressViewModel.GuildBoostState.NOT_IN_PROGRESS,
                guild,
                2,
            ),
            viewModel.requireViewState(),
        )
    }

    @Test
    fun `transfer state includes current boosts on the target guild`() {
        val viewModel = transferViewModel()

        viewModel.handleStoreState(
            GuildBoostTransferInProgressViewModel.StoreState(
                previousGuild = guild,
                targetGuild = otherGuild,
                guildBoostState = StoreGuildBoost.State.Loaded(
                    mapOf(1L to slot(1L, otherGuild.id), 2L to slot(2L, guild.id)),
                ),
            ),
        )

        assertEquals(
            GuildBoostTransferInProgressViewModel.ViewState.PreTransfer(guild, otherGuild, 1, false),
            viewModel.requireViewState(),
        )
    }

    @Test
    fun `transfer fails closed when either guild is unavailable`() {
        val viewModel = transferViewModel()

        viewModel.handleStoreState(
            GuildBoostTransferInProgressViewModel.StoreState(
                previousGuild = guild,
                targetGuild = null,
                guildBoostState = StoreGuildBoost.State.Loading,
            ),
        )

        assertEquals(GuildBoostTransferInProgressViewModel.ViewState.ErrorLoading, viewModel.requireViewState())
    }

    private fun transferViewModel() = GuildBoostTransferInProgressViewModel(
        slotId = 1L,
        boostId = 2L,
        previousGuildId = guild.id,
        targetGuildId = otherGuild.id,
        unsubscribeFromGuild = { _, _ -> Observable.empty() },
        subscribeToGuild = { _, _ -> Observable.empty() },
        storeGuildBoost = store,
        storeObservable = Observable.never(),
    )

    private fun slot(id: Long, guildId: Long?) = ModelGuildBoostSlot(
        cooldownEndsAt = null,
        id = id,
        subscriptionId = 100L + id,
        premiumGuildSubscription = guildId?.let { ModelAppliedGuildBoost(1_000L + id, it, 99L) },
        canceled = false,
    )
}
