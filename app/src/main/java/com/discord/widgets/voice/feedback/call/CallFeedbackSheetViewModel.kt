package com.discord.widgets.voice.feedback.call

import android.os.Parcel
import android.os.Parcelable
import com.discord.R
import com.discord.app.AppViewModel
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.widgets.feedback.FeedbackSheetViewModel
import com.discord.widgets.voice.feedback.FeedbackIssue
import com.discord.widgets.voice.feedback.FeedbackRating
import com.discord.widgets.voice.feedback.PendingFeedback
import rx.Observable
import rx.subjects.PublishSubject

class CallFeedbackSheetViewModel(
    config: Config,
    storeStateObservable: Observable<StoreState> = observeStoreState(),
    private val submitter: CallFeedbackSubmitter = CallFeedbackSubmitter(
        PendingFeedback.CallFeedback(
            config.channelId,
            config.rtcConnectionId,
            config.callDurationMs,
            config.mediaSessionId,
        ),
    ),
    onShown: (Long) -> Unit = { channelId ->
        StoreStream.getChannels().getChannel(channelId)?.let { channel ->
            AnalyticsTracker.openCallFeedbackSheet(channel.guildId, channel.id, channel.type)
        }
    },
) : AppViewModel<FeedbackSheetViewModel.ViewState>(createViewState()), FeedbackSheetViewModel {
    private val eventSubject = PublishSubject.create<FeedbackSheetViewModel.Event>()
    private var pendingFeedback = submitter.pendingCallFeedback
    private var mostRecentStoreState: StoreState? = null
    private var submitOnDismiss = true
    private var submitted = false
    private val issues = ISSUES.shuffled() + FeedbackIssue.OTHER

    init {
        onShown(config.channelId)
        storeStateObservable.takeUntil(unsubscribeSignal).subscribe { mostRecentStoreState = it }
    }

    override fun observeEvents(): Observable<FeedbackSheetViewModel.Event> = eventSubject

    override fun selectRating(feedbackRating: FeedbackRating) {
        pendingFeedback = pendingFeedback.copy(feedbackRating = feedbackRating)
        submitter.pendingCallFeedback = pendingFeedback
        if (feedbackRating == FeedbackRating.NEUTRAL || feedbackRating == FeedbackRating.BAD) {
            updateViewState(createViewState(feedbackRating, issues))
        } else {
            selectIssue(null, null)
            updateViewState(createViewState(feedbackRating))
            submitForm()
        }
    }

    override fun selectIssue(feedbackIssue: FeedbackIssue?, reasonDescription: String?) {
        pendingFeedback = pendingFeedback.copy(
            reasonCode = feedbackIssue?.let(REASON_CODES::get),
            reasonDescription = reasonDescription,
        )
        submitter.pendingCallFeedback = pendingFeedback
        if (feedbackIssue == FeedbackIssue.OTHER) {
            submitOnDismiss = false
            eventSubject.onNext(
                FeedbackSheetViewModel.Event.NavigateToIssueDetails(
                    pendingFeedback,
                    mostRecentStoreState?.shouldShowCxLinkForIssueDetails == true,
                ),
            )
        } else if (requireViewState().selectedFeedbackRating in listOf(FeedbackRating.NEUTRAL, FeedbackRating.BAD)) {
            submitForm()
        }
    }

    override fun submitForm() {
        if (submitted) return
        submitted = true
        submitter.submit(null)
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

    data class StoreState(val shouldShowCxLinkForIssueDetails: Boolean)

    data class Config(
        val channelId: Long,
        val rtcConnectionId: String?,
        val mediaSessionId: String?,
        val callDurationMs: Long?,
    ) : Parcelable {
        private constructor(parcel: Parcel) : this(
            parcel.readLong(),
            parcel.readString(),
            parcel.readString(),
            parcel.readValue(Long::class.java.classLoader) as? Long,
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeLong(channelId)
            parcel.writeString(rtcConnectionId)
            parcel.writeString(mediaSessionId)
            parcel.writeValue(callDurationMs)
        }

        override fun describeContents(): Int = 0

        companion object CREATOR : Parcelable.Creator<Config> {
            override fun createFromParcel(parcel: Parcel) = Config(parcel)
            override fun newArray(size: Int): Array<Config?> = arrayOfNulls(size)
        }
    }

    companion object {
        internal val ISSUES = listOf(
            FeedbackIssue.COULD_NOT_HEAR_AUDIO,
            FeedbackIssue.NOBODY_COULD_HEAR_ME,
            FeedbackIssue.AUDIO_ECHOS,
            FeedbackIssue.AUDIO_ROBOTIC,
            FeedbackIssue.AUDIO_CUT_IN_AND_OUT,
            FeedbackIssue.VOLUME_TOO_LOW_OR_HIGH,
            FeedbackIssue.BACKGROUND_NOISE_TOO_LOUD,
            FeedbackIssue.SPEAKERPHONE_ISSUE,
            FeedbackIssue.HEADSET_OR_BLUETOOTH_ISSUE,
        )

        internal val REASON_CODES = mapOf(
            FeedbackIssue.OTHER to 1,
            FeedbackIssue.COULD_NOT_HEAR_AUDIO to 2,
            FeedbackIssue.NOBODY_COULD_HEAR_ME to 3,
            FeedbackIssue.AUDIO_ECHOS to 4,
            FeedbackIssue.AUDIO_ROBOTIC to 5,
            FeedbackIssue.AUDIO_CUT_IN_AND_OUT to 6,
            FeedbackIssue.VOLUME_TOO_LOW_OR_HIGH to 7,
            FeedbackIssue.BACKGROUND_NOISE_TOO_LOUD to 8,
            FeedbackIssue.SPEAKERPHONE_ISSUE to 9,
            FeedbackIssue.HEADSET_OR_BLUETOOTH_ISSUE to 10,
        )

        private fun observeStoreState(): Observable<StoreState> =
            StoreStream.getExperiments().observeUserExperiment("2020-08_feedback_modal_helpdesk_link", true)
                .map { StoreState(it?.bucket == 1) }

        private fun createViewState(
            rating: FeedbackRating = FeedbackRating.NO_RESPONSE,
            issues: List<FeedbackIssue> = emptyList(),
        ) = FeedbackSheetViewModel.ViewState(
            selectedFeedbackRating = rating,
            feedbackIssues = issues,
            titleTextResId = R.string.call_feedback_sheet_title,
            promptTextResId = R.string.call_feedback_prompt,
            issuesHeaderTextResId = R.string.call_feedback_issue_section_header,
        )
    }
}
