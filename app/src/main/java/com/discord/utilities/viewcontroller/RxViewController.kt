package com.discord.utilities.viewcontroller

import android.view.View
import androidx.annotation.MainThread
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable
import rx.Subscription

/**
 * Drives a single view from an observable, without the view needing a fragment or a view model.
 *
 * A view has no lifecycle, so the subscription is scoped to the only signal a view really has: its
 * attachment to a window. [bind] connects [observeState] to [configureView] and the connection ends
 * on detach, which is what lets a self-contained view — an avatar with a presence ring, say — keep
 * itself up to date wherever it is dropped.
 *
 * Two guards stop stale frames reaching a recycled view: `computationLatest` drops intermediate
 * emissions so only the newest state is delivered, and a final `filter` skips any emission that
 * lands after detachment but before the `takeUntil` has torn the chain down.
 */
abstract class RxViewController<V : View, T>(protected val view: V) {

    private var subscription: Subscription? = null

    /** The state this controller renders. */
    abstract fun observeState(): Observable<T>

    /** Applies [viewState] to the view; always called on the main thread. */
    abstract fun configureView(viewState: T)

    /**
     * (Re)connects the view to [observeState].
     *
     * Calling this again replaces the previous connection, so it is safe to call from a rebind — for
     * example when a recycler hands the view a different model.
     */
    @MainThread
    fun bind() {
        subscription?.unsubscribe()
        subscription = connectViewRx(view, observeState()) { configureView(it) }
    }

    private fun <S> connectViewRx(
        view: View,
        observable: Observable<S>,
        onNext: (S) -> Unit,
    ): Subscription {
        lateinit var subscriptionResult: Subscription
        observable
            .takeUntil(ViewDetachedFromWindowObservable(view).observe())
            .computationLatest()
            .ui()
            .filter { view.isAttachedToWindow }
            .appSubscribe(
                errorClass = javaClass,
                subscriptionHandler = { subscription -> subscriptionResult = subscription },
                onNext = onNext,
            )
        return subscriptionResult
    }
}
