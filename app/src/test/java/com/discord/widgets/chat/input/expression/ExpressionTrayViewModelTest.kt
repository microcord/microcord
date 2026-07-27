package com.discord.widgets.chat.input.expression

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.observers.TestSubscriber
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

class ExpressionTrayViewModelTest {

    private val selectedTab = BehaviorSubject.create(ExpressionTrayTab.EMOJI)
    private val pickerEvents = PublishSubject.create<ExpressionPickerEvent>()
    private val storeStates = PublishSubject.create<ExpressionTrayViewModel.StoreState>()
    private val navigation = object : ExpressionPickerNavigationStore {
        override fun observeSelectedTab(): Observable<ExpressionTrayTab> = selectedTab

        override fun onSelectTab(expressionTrayTab: ExpressionTrayTab) {
            selectedTab.onNext(expressionTrayTab)
        }
    }

    @Before
    fun setUpMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
    }

    @After
    fun resetMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
    }

    @Test
    fun externalStickerRequestSelectsStickerAndEmitsSheetThenInlineCommands() {
        val viewModel = viewModel()
        val events = TestSubscriber<ExpressionTrayViewModel.Event>()
        viewModel.observeEvents().subscribe(events)

        pickerEvents.onNext(
            ExpressionPickerEvent.OpenStickerPicker(
                stickerPackId = 42L,
                searchText = "wave",
            ),
        )

        assertEquals(ExpressionTrayTab.STICKER, selectedTab.value)
        events.assertValues(
            ExpressionTrayViewModel.Event.ShowStickerPicker(42L, "wave", inline = false),
            ExpressionTrayViewModel.Event.ShowStickerPicker(42L, "wave", inline = true),
        )
    }

    @Test
    fun inlineStickerRequestSkipsTheSheetAndUsesTheNoPackSentinel() {
        val viewModel = viewModel()
        val events = TestSubscriber<ExpressionTrayViewModel.Event>()
        viewModel.observeEvents().subscribe(events)

        pickerEvents.onNext(ExpressionPickerEvent.OpenStickerPicker(inline = true))

        events.assertValue(
            ExpressionTrayViewModel.Event.ShowStickerPicker(
                ExpressionTrayViewModel.NO_STICKER_PACK,
                null,
                inline = true,
            ),
        )
    }

    @Test
    fun threadDraftHidesGifAndStickerTabsWithoutLosingThePersistedSelection() {
        val viewModel = viewModel()
        storeStates.onNext(storeState(ExpressionTrayTab.GIF, isThreadDraft = true))

        assertEquals(ExpressionTrayTab.GIF, viewModel.viewState?.selectedExpressionTab)
        assertEquals(false, viewModel.viewState?.showGifsAndStickers)
    }

    @Test
    fun gifDetailBackReturnsToTheLandingPage() {
        val viewModel = viewModel()
        viewModel.selectGifCategory(
            com.discord.widgets.chat.input.gifpicker.GifCategoryItem.Trending("https://preview"),
        )

        assertEquals(false, viewModel.viewState?.showLandingPage)
        viewModel.clickBack()
        assertEquals(true, viewModel.viewState?.showLandingPage)
    }

    @Test
    fun closeCommandEmitsHideTray() {
        val viewModel = viewModel()
        val events = TestSubscriber<ExpressionTrayViewModel.Event>()
        viewModel.observeEvents().subscribe(events)

        pickerEvents.onNext(ExpressionPickerEvent.CloseExpressionPicker)

        events.assertValue(ExpressionTrayViewModel.Event.HideExpressionTray)
    }

    private fun viewModel() = ExpressionTrayViewModel(
        storeExpressionPickerNavigation = navigation,
        storeStateObservable = storeStates,
        expressionPickerEventObservable = pickerEvents,
    )

    private fun storeState(
        tab: ExpressionTrayTab,
        isThreadDraft: Boolean,
    ) = ExpressionTrayViewModel.StoreState(
        selectedExpressionTab = tab,
        guildStickers = emptyMap(),
        userGuildIds = emptySet(),
        isThreadDraft = isThreadDraft,
    )
}
