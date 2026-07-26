package com.discord.app

import androidx.fragment.app.Fragment
import rx.Observable
import rx.subjects.Subject

/**
 * Anything with a bounded observation window: activities, fragments, dialogs and bottom sheets.
 *
 * [unsubscribeSignal] emits every time the component stops being visible (`onPause`). Subscriptions
 * created through [bindToComponentLifecycle] therefore terminate without any manual bookkeeping.
 */
interface AppComponent {
    val unsubscribeSignal: Subject<Void, Void>
}

/**
 * Terminates the stream when [appComponent] pauses.
 *
 * Fragment-backed components additionally drop emissions that arrive while the fragment is
 * detached, being removed, or hosted by a finishing activity, so a late delivery cannot touch a
 * dead view hierarchy.
 */
fun <T> Observable<T>.bindToComponentLifecycle(appComponent: AppComponent): Observable<T> {
    val bounded = takeUntil(appComponent.unsubscribeSignal)
    if (appComponent !is Fragment) return bounded
    return bounded.filter {
        appComponent.isAdded && !appComponent.isRemoving && appComponent.activity?.isFinishing == false
    }
}
