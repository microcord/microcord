package com.discord.widgets.status

import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Process-wide state of the global status banner, shared between the banner itself and the screens
 * that have to lay out around it.
 *
 * The banner is a single fragment pinned above the app's content, but three unrelated surfaces need
 * to know about it: the banner decides *what* to render, a screen underneath needs to know whether
 * the banner is taking vertical space at all, and the fullscreen call screen needs to suppress it
 * while it is showing the same call itself. Routing all three through one observable keeps them
 * from disagreeing about it mid-animation.
 *
 * The reference exposes this as a singleton via a `Provider` object; [Provider] preserves that so
 * call sites read the same.
 */
class WidgetGlobalStatusIndicatorState @VisibleForTesting internal constructor() {

    /**
     * @param isVisible the banner is showing something — anything other than
     *   [WidgetGlobalStatusIndicatorViewModel.ViewState.Inactive].
     * @param isCustomBackground the banner has taken on a state-specific background (the green
     *   "you are speaking" tint) rather than the neutral one, so a screen underneath should not
     *   assume the default colour continues into it.
     * @param isViewingCall the user is already looking at the call the banner would describe, so
     *   the banner collapses to just its actions.
     */
    data class State(
        val isVisible: Boolean,
        val isCustomBackground: Boolean,
        val isViewingCall: Boolean,
    )

    private val stateSubject: BehaviorSubject<State> = BehaviorSubject.create()

    fun observeState(): Observable<State> = stateSubject.distinctUntilChanged()

    /**
     * Publishes a new state. Each argument defaults to its current value, so a caller that only
     * knows about one facet — the call screen toggling [isViewingCall], say — does not have to
     * reconstruct the other two.
     */
    @MainThread
    fun updateState(
        isVisible: Boolean = stateSubject.value?.isVisible ?: false,
        isCustomBackground: Boolean = stateSubject.value?.isCustomBackground ?: false,
        isViewingCall: Boolean = stateSubject.value?.isViewingCall ?: false,
    ) {
        stateSubject.onNext(State(isVisible, isCustomBackground, isViewingCall))
    }

    object Provider {
        private val callIndicatorState = WidgetGlobalStatusIndicatorState()

        @JvmStatic
        fun get(): WidgetGlobalStatusIndicatorState = callIndicatorState
    }
}
