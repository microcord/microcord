package com.discord.widgets.chat.input.expression

import rx.Observable
import rx.subjects.PublishSubject

/**
 * The one-way channel from anywhere in the app into the expression tray.
 *
 * A `PublishSubject`, not a `BehaviorSubject`: an event that arrived while no tray was listening is
 * gone, which is the correct behaviour for a command. [INSTANCE] is a process-wide singleton
 * because the emitters (the sticker popout, message rows) have no reference to the tray and no
 * sensible scope to share with it.
 */
class ExpressionPickerEventBus {

    private val expressionPickerEventSubject = PublishSubject.create<ExpressionPickerEvent>()

    fun emitEvent(expressionPickerEvent: ExpressionPickerEvent) {
        expressionPickerEventSubject.onNext(expressionPickerEvent)
    }

    fun observeExpressionPickerEvents(): Observable<ExpressionPickerEvent> = expressionPickerEventSubject

    companion object {
        @JvmStatic
        val INSTANCE: ExpressionPickerEventBus by lazy { ExpressionPickerEventBus() }
    }
}
