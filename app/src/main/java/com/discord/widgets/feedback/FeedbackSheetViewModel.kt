package com.discord.widgets.feedback

import androidx.annotation.StringRes
import com.discord.widgets.voice.feedback.FeedbackIssue
import com.discord.widgets.voice.feedback.FeedbackRating
import java.io.Serializable
import rx.Observable

/**
 * The contract [WidgetFeedbackSheet] renders, so one sheet layout serves call quality, stream
 * quality and server deletion without knowing which of them it is showing.
 *
 * The concrete view models live with their features (`widgets/voice/feedback/call`,
 * `.../stream`, `widgets/guild_delete_feedback`); this interface is deliberately the only thing
 * they share.
 */
interface FeedbackSheetViewModel {

    fun observeViewState(): Observable<ViewState>

    fun observeEvents(): Observable<Event>

    /**
     * The user picked a reason. [reasonDescription] is the localised label, carried alongside the
     * enum because the analytics payload wants human text.
     */
    fun selectIssue(feedbackIssue: FeedbackIssue?, reasonDescription: String?)

    /** Face ratings are only shown by the call/stream flows; the default ignores them. */
    fun selectRating(feedbackRating: FeedbackRating) = Unit

    /** Called on submit *and* on dismissal — the sheet treats closing as an implicit submit. */
    fun submitForm()

    sealed class Event {
        /** The chosen reason needs free text, so hand off to the issue-details screen. */
        data class NavigateToIssueDetails(
            val pendingFeedback: PendingFeedback,
            val showCxLinkForIssueDetails: Boolean,
        ) : Event()

        /** [showConfirmation] false means the user skipped; close without the thank-you toast. */
        data class Submitted(val showConfirmation: Boolean) : Event()
    }

    data class ViewState(
        val selectedFeedbackRating: FeedbackRating,
        val feedbackIssues: List<FeedbackIssue>,
        @StringRes val titleTextResId: Int,
        @StringRes val promptTextResId: Int? = null,
        @StringRes val issuesHeaderTextResId: Int,
    ) {
        /**
         * Whether the three faces are shown at all. Derived, not passed: a flow that supplies a
         * rating prompt is by definition a rated flow.
         */
        val showFaceRatings: Boolean = promptTextResId != null
    }
}

/**
 * What a half-filled feedback form carries between the sheet and the issue-details screen.
 *
 * **Temporary marker.** The reference declares a `sealed class PendingFeedback : Serializable` in
 * `com.discord.widgets.voice.feedback` with `CallFeedback`, `StreamFeedback` and
 * `GuildDeleteFeedback` subclasses. That file does not exist in this project and creating it was
 * outside this agent's file ownership, so only the shared supertype is declared here.
 * **Delete this and use the real sealed class when `widgets/voice/feedback/PendingFeedback` lands.**
 */
interface PendingFeedback : Serializable

/**
 * Sends a completed feedback form.
 *
 * **Temporary location.** The reference puts this interface in
 * `com.discord.widgets.voice.feedback`; move it there with [PendingFeedback].
 */
interface FeedbackSubmitter {
    fun submit(issueDetails: String?)
}
