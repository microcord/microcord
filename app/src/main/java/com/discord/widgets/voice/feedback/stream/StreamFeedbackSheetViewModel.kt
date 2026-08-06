package com.discord.widgets.voice.feedback.stream

import com.discord.R
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelApplicationStream
import com.discord.stores.StoreStream
import com.discord.widgets.feedback.FeedbackSheetViewModel
import com.discord.widgets.voice.feedback.FeedbackIssue
import com.discord.widgets.voice.feedback.FeedbackRating
import com.discord.widgets.voice.feedback.PendingFeedback
import rx.Observable
import rx.subjects.PublishSubject

class StreamFeedbackSheetViewModel(
    streamKey: String,
    mediaSessionId: String?,
    private val report: (PendingFeedback.StreamFeedback) -> Unit = {
        StreamFeedbackSubmitter(it).submit(null)
    },
) : AppViewModel<FeedbackSheetViewModel.ViewState>(createViewState()), FeedbackSheetViewModel {
    private val eventSubject = PublishSubject.create<FeedbackSheetViewModel.Event>()
    private var pendingFeedback = PendingFeedback.StreamFeedback(
        ModelApplicationStream.decodeStreamKey(streamKey),
        mediaSessionId = mediaSessionId,
    )
    private var submitOnDismiss = true
    private var submitted = false
    private val issues = ISSUES.shuffled() + FeedbackIssue.OTHER

    override fun observeEvents(): Observable<FeedbackSheetViewModel.Event> = eventSubject

    override fun selectRating(feedbackRating: FeedbackRating) {
        pendingFeedback = pendingFeedback.copy(feedbackRating = feedbackRating)
        if (feedbackRating == FeedbackRating.NEUTRAL || feedbackRating == FeedbackRating.BAD) {
            updateViewState(createViewState(feedbackRating, issues))
        } else {
            selectIssue(null, null)
            updateViewState(createViewState(feedbackRating))
            submitForm()
        }
    }

    override fun selectIssue(feedbackIssue: FeedbackIssue?, reasonDescription: String?) {
        pendingFeedback = pendingFeedback.copy(issue = feedbackIssue)
        if (feedbackIssue == FeedbackIssue.OTHER) {
            submitOnDismiss = false
            eventSubject.onNext(
                FeedbackSheetViewModel.Event.NavigateToIssueDetails(
                    pendingFeedback,
                    true,
                ),
            )
        } else if (requireViewState().selectedFeedbackRating in listOf(FeedbackRating.NEUTRAL, FeedbackRating.BAD)) {
            submitForm()
        }
    }

    override fun submitForm() {
        if (submitted) return
        submitted = true
        report(pendingFeedback)
        eventSubject.onNext(
            FeedbackSheetViewModel.Event.Submitted(
                requireViewState().selectedFeedbackRating != FeedbackRating.NO_RESPONSE,
            ),
        )
    }

    override fun onCleared() {
        if (submitOnDismiss) submitForm()
        super.onCleared()
    }

    companion object {
        internal val ISSUES = listOf(
            FeedbackIssue.STREAM_REPORT_ENDED_BLACK,
            FeedbackIssue.STREAM_REPORT_ENDED_BLURRY,
            FeedbackIssue.STREAM_REPORT_ENDED_LAGGING,
            FeedbackIssue.STREAM_REPORT_ENDED_OUT_OF_SYNC,
            FeedbackIssue.STREAM_REPORT_ENDED_AUDIO_MISSING,
            FeedbackIssue.STREAM_REPORT_ENDED_AUDIO_POOR,
            FeedbackIssue.STREAM_REPORT_ENDED_STREAM_STOPPED_UNEXPECTEDLY,
        )

        private fun createViewState(
            rating: FeedbackRating = FeedbackRating.NO_RESPONSE,
            issues: List<FeedbackIssue> = emptyList(),
        ) = FeedbackSheetViewModel.ViewState(
            selectedFeedbackRating = rating,
            feedbackIssues = issues,
            titleTextResId = R.string.stream_report_a_problem_post_stream,
            promptTextResId = R.string.stream_report_rating_body,
            issuesHeaderTextResId = R.string.stream_report_placeholder,
        )
    }
}
