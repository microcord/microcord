package com.discord.stores

import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The in-progress thread-creation form.
 *
 * There is exactly one draft at a time — the thread composer is a single modal surface — so this is
 * a lone [BehaviorSubject] rather than a keyed map. It is a [StoreV2] for dispatcher confinement
 * only: it publishes through the subject and therefore has no snapshot to take and never calls
 * `markChanged`.
 */
class StoreThreadDraft(private val dispatcher: Dispatcher) : StoreV2() {

    data class ThreadDraftState(
        val isPrivate: Boolean = false,
        val autoArchiveDuration: Int? = null,
        val threadName: String? = null,
        val isSending: Boolean = false,
        val shouldDisplayNameError: Boolean = false,
    )

    private val draftStateSubject = BehaviorSubject.create(ThreadDraftState())

    fun observeDraftState(): Observable<ThreadDraftState> = draftStateSubject

    fun setDraftState(draftState: ThreadDraftState) = dispatcher.schedule {
        draftStateSubject.onNext(draftState)
    }

    /** Latch the form into its sending state; only [clearDraftState] releases it. */
    fun setDraftSending() = dispatcher.schedule {
        draftStateSubject.onNext(draftStateSubject.value.copy(isSending = true))
    }

    fun clearDraftState() = dispatcher.schedule {
        draftStateSubject.onNext(ThreadDraftState())
    }
}
