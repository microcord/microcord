package com.discord.widgets.voice.sheet

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.discord.R
import com.discord.app.AppViewModel
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StoreStream
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Drives [WidgetNoiseCancellationBottomSheet].
 *
 * The sheet is purely explanatory, so it has no view state at all — it only turns the single
 * "enable" press into a store write plus a confirmation toast.
 */
class WidgetNoiseCancellationBottomSheetViewModel(
    private val mediaSettingsStore: StoreMediaSettings = StoreStream.getMediaSettings(),
) : AppViewModel<WidgetNoiseCancellationBottomSheetViewModel.ViewState>() {

    private val eventSubject = PublishSubject.create<Event>()

    /** The sheet renders entirely from its layout; no state is ever published. */
    sealed class ViewState

    sealed class Event {
        data class ShowToast(@StringRes val toastResId: Int) : Event()
    }

    fun observeEvents(): Observable<Event> = eventSubject

    @MainThread
    fun onNoiseCancellationButtonPressed() {
        mediaSettingsStore.setNoiseProcessing(StoreMediaSettings.NoiseProcessing.Cancellation)
        eventSubject.onNext(Event.ShowToast(R.string.noise_cancellation_on))
    }
}
