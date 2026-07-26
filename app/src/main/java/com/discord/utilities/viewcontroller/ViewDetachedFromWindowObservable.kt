package com.discord.utilities.viewcontroller

import android.view.View
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Emits once when [view] is detached from its window.
 *
 * Views have no lifecycle of their own, so a view that drives itself from an `Observable` has no
 * natural point at which to stop. This is that point: `takeUntil` this and the subscription ends
 * exactly when the view leaves the hierarchy.
 *
 * The subject is a [BehaviorSubject], so a subscriber that arrives *after* the detach still sees it
 * and unsubscribes immediately rather than leaking.
 */
class ViewDetachedFromWindowObservable(view: View) : View.OnAttachStateChangeListener {

    private val onDetachSubject: BehaviorSubject<Unit> = BehaviorSubject.create()

    init {
        view.addOnAttachStateChangeListener(this)
    }

    fun observe(): Observable<Unit> = onDetachSubject

    override fun onViewAttachedToWindow(v: View) = Unit

    override fun onViewDetachedFromWindow(v: View) {
        onDetachSubject.onNext(Unit)
    }
}
