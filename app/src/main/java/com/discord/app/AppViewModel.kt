package com.discord.app

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subjects.Subject

/**
 * Base class for every screen-scoped view model in the app.
 *
 * A view model owns exactly one observable `ViewState` and, being an [AppComponent], its own
 * unsubscribe signal. Store subscriptions started in the constructor are bound to that signal with
 * `Observable.ui(this)` so they terminate in [onCleared] rather than when the hosting fragment
 * pauses — the state survives configuration changes, the subscription does not leak.
 *
 * [viewState] is `null` until the first [updateViewState]; screens render an "uninitialized" state
 * for that window instead of blocking.
 */
abstract class AppViewModel<V>(initialViewState: V? = null) : ViewModel(), AppComponent {

    private val viewStateSubject: BehaviorSubject<V> = BehaviorSubject.create()

    final override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()

    init {
        if (initialViewState != null) {
            viewStateSubject.onNext(initialViewState)
        }
    }

    /** The most recently published state, or `null` before the first emission. */
    val viewState: V?
        get() = viewStateSubject.value

    /** The most recently published state. Only call once the first emission is guaranteed. */
    fun requireViewState(): V = checkNotNull(viewState) { "requireViewState() before the first updateViewState()" }

    /** Hot stream of view states; replays the current one to every new subscriber. */
    fun observeViewState(): Observable<V> = viewStateSubject

    /**
     * Last chance to rewrite a state before it is published, given the state it replaces.
     * Subclasses that need to preserve transient fields (a pending toggle, a loading flag) across a
     * store-driven rebuild override this. The default keeps [pendingViewState] untouched.
     */
    open fun modifyPendingViewState(currentViewState: V?, pendingViewState: V): V = pendingViewState

    @MainThread
    open fun updateViewState(viewState: V) {
        viewStateSubject.onNext(modifyPendingViewState(this.viewState, viewState))
    }

    /** Run [block] against the current state, requiring one to exist. */
    fun <T> withViewState(block: (V) -> T): T = block(requireViewState())

    override fun onCleared() {
        super.onCleared()
        unsubscribeSignal.onNext(null)
    }
}
