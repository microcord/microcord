package com.discord.app

import rx.subjects.Subject

/**
 * Anything with a bounded observation window: activities, fragments, dialogs and bottom sheets.
 *
 * [unsubscribeSignal] emits every time the component stops being visible (`onPause`), so a
 * subscription created through `Observable.bindToComponentLifecycle` in
 * `com.discord.utilities.rx` terminates without any manual bookkeeping.
 */
interface AppComponent {
    val unsubscribeSignal: Subject<Void, Void>
}
