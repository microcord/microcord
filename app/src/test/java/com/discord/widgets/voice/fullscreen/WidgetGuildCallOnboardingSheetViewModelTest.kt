package com.discord.widgets.voice.fullscreen

import com.discord.utilities.voice.VoiceChannelJoinability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.observers.TestSubscriber
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

class WidgetGuildCallOnboardingSheetViewModelTest {
    private val storeStates = PublishSubject.create<WidgetGuildCallOnboardingSheetViewModel.StoreState>()
    private val selectedChannels = mutableListOf<Long>()
    private lateinit var viewModel: WidgetGuildCallOnboardingSheetViewModel
    private lateinit var events: TestSubscriber<WidgetGuildCallOnboardingSheetViewModel.Event>

    @Before
    fun setUp() {
        RxJavaHooks.setOnComputationScheduler { Schedulers.immediate() }
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
        viewModel = WidgetGuildCallOnboardingSheetViewModel(42L, storeStates, selectedChannels::add)
        events = TestSubscriber()
        viewModel.observeEvents().subscribe(events)
    }

    @After
    fun tearDown() {
        RxJavaHooks.reset()
        RxAndroidPlugins.getInstance().reset()
    }

    @Test
    fun storeStateBecomesLoadedViewState() {
        storeStates.onNext(storeState(VoiceChannelJoinability.CHANNEL_FULL))

        assertEquals(
            WidgetGuildCallOnboardingSheetViewModel.ViewState.Loaded(
                guildId = 7L,
                joinability = VoiceChannelJoinability.CHANNEL_FULL,
            ),
            viewModel.viewState,
        )
    }

    @Test
    fun joinableChannelIsSelectedBeforeLaunchingTheCallScreen() {
        storeStates.onNext(storeState(VoiceChannelJoinability.CAN_JOIN))
        viewModel.onConnectPressed()

        assertEquals(listOf(42L), selectedChannels)
        events.assertValue(
            WidgetGuildCallOnboardingSheetViewModel.Event.LaunchGuildCallScreen(42L, 7L),
        )
    }

    @Test
    fun guildVideoCapacityEmitsTheCapacityDialogWithoutSelecting() {
        storeStates.onNext(storeState(VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY))
        viewModel.onConnectPressed()

        assertEquals(emptyList<Long>(), selectedChannels)
        events.assertValue(
            WidgetGuildCallOnboardingSheetViewModel.Event.ShowGuildVideoCapacityDialog,
        )
    }

    @Test
    fun otherJoinFailuresDoNothing() {
        VoiceChannelJoinability.entries
            .filterNot {
                it == VoiceChannelJoinability.CAN_JOIN ||
                    it == VoiceChannelJoinability.GUILD_VIDEO_AT_CAPACITY
            }
            .forEach {
                storeStates.onNext(storeState(it))
                viewModel.onConnectPressed()
            }

        assertEquals(emptyList<Long>(), selectedChannels)
        events.assertNoValues()
    }

    private fun storeState(joinability: VoiceChannelJoinability) =
        WidgetGuildCallOnboardingSheetViewModel.StoreState(7L, joinability)
}
