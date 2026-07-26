package com.discord.widgets.user.search

import androidx.lifecycle.ViewModel
import rx.Observable
import rx.subjects.PublishSubject

/**
 * Lets the quick-switcher content ask its host dialog to close.
 *
 * The list fragment does not know whether it is hosted in a dialog, a tab, or the mentions pager, so
 * it publishes an intent to dismiss and whichever host is listening acts on it. Scoped to the
 * activity so the two sides find the same instance.
 */
class WidgetGlobalSearchDismissModel : ViewModel() {

    private val dismissEventsSubject = PublishSubject.create<Unit>()

    val dismissEvents: Observable<Unit> get() = dismissEventsSubject

    fun dismiss() {
        dismissEventsSubject.onNext(Unit)
    }
}
