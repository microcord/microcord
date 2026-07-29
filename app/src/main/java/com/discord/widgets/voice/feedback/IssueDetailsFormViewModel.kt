package com.discord.widgets.voice.feedback

import com.discord.app.AppViewModel
import com.discord.widgets.feedback.PendingFeedback
import rx.Observable
import rx.subjects.PublishSubject

class IssueDetailsFormViewModel(
    private val feedbackSubmitter: FeedbackSubmitter,
) : AppViewModel<Unit>(Unit) {
    constructor(pendingFeedback: PendingFeedback) : this(FeedbackSubmitterFactory.create(pendingFeedback))

    private val eventSubject = PublishSubject.create<Event>()
    private var submitted = false

    fun observeEvents(): Observable<Event> = eventSubject

    fun submitForm(issueDetails: String?) {
        if (submitted) return
        submitted = true
        feedbackSubmitter.submit(issueDetails)
        eventSubject.onNext(Event.Close)
    }

    override fun onCleared() {
        submitForm(null)
        super.onCleared()
    }

    sealed class Event {
        object Close : Event()
    }
}
