package com.discord.widgets.chat.input

import com.discord.app.AppViewModel
import rx.Observable
import rx.subjects.PublishSubject

/**
 * The shared handle on the composer that the expression tray and its child pickers use to drive the
 * surface they are sitting in.
 *
 * Activity-scoped, so the tray, the GIF category page and the chat input all address the same
 * instance: the tray has no reference to the composer and must not acquire one, because it is
 * hosted inside the composer's own child fragment manager.
 *
 * **Divergence from the reference, and why.** The reference class implements
 * `com.lytefast.flexinput.viewmodel.FlexInputViewModel` — an interface that exists only in
 * Discord's private fork of FlexInput and carries the whole attachment/permission/tooltip surface
 * (`onSendButtonClicked`, `onGalleryButtonClicked`, `requestMediaPermissions`, `FlexInputState`, …).
 * The published `com.github.lytefast:flex-input:0.10.9` this project builds against has no such
 * interface; `FlexInputFragment` exposes the same behaviour as plain methods
 * (`onSend`, `showEmojiTray`, `hideEmojiTray`). So this reproduces the subset the expression tray
 * actually calls, over the same shared-view-model mechanism, and the composer bridges the rest.
 */
class AppFlexInputViewModel : AppViewModel<AppFlexInputViewModel.ViewState>(ViewState()) {

    data class ViewState(val expressionTrayShown: Boolean = false)

    /** Things only the view holding the `EditText` can carry out. */
    sealed class Event {
        object ShowKeyboard : Event()
        object HideKeyboard : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()

    fun observeEvents(): Observable<Event> = eventSubject

    fun showExpressionTray() {
        if (!requireViewState().expressionTrayShown) {
            updateViewState(ViewState(expressionTrayShown = true))
        }
        eventSubject.onNext(Event.HideKeyboard)
    }

    /** @return `true` when the tray was open and this closed it — the shape a back handler wants. */
    fun hideExpressionTray(): Boolean {
        if (!requireViewState().expressionTrayShown) return false
        updateViewState(ViewState(expressionTrayShown = false))
        return true
    }

    fun toggleExpressionTray() {
        if (!hideExpressionTray()) showExpressionTray()
    }

    /**
     * Dismissing a picker sheet should hand the composer straight back to the user rather than
     * dropping them onto the tray they never asked to return to.
     */
    fun showKeyboardAndHideExpressionTray() {
        updateViewState(ViewState(expressionTrayShown = false))
        eventSubject.onNext(Event.ShowKeyboard)
    }

    fun hideKeyboard() = eventSubject.onNext(Event.HideKeyboard)

    fun focus() = eventSubject.onNext(Event.ShowKeyboard)
}
