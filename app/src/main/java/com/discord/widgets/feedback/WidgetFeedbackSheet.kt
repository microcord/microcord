package com.discord.widgets.feedback

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.ToastManager
import com.discord.widgets.guild_delete_feedback.GuildDeleteFeedbackSheetViewModel
import com.discord.widgets.voice.feedback.FeedbackRating
import com.discord.widgets.voice.feedback.FeedbackView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subscriptions.CompositeSubscription

/**
 * One bottom sheet for every "how did that go?" prompt in the app: call quality, stream quality and
 * server deletion. Which flow it is comes from [ARG_FEEDBACK_TYPE]; the rest of the sheet is driven
 * entirely by [FeedbackSheetViewModel.ViewState].
 *
 * The sheet opens collapsed showing just the rating faces and expands 100 ms later once the reason
 * list is bound, so the list animates in instead of appearing mid-gesture. That two-step is the
 * reference behaviour and is what [isShowingFeedbackIssues] guards.
 *
 * Closing counts as submitting: the close button calls [FeedbackSheetViewModel.submitForm], which
 * is how a skipped form still records that the prompt was seen.
 */
class WidgetFeedbackSheet : AppBottomSheet() {

    enum class FeedbackType {
        // Ordinals are the reference's, taken from the synthetic `$VALUES` array.
        CALL,
        STREAM,
        GUILD_DELETE,
    }

    private var isShowingFeedbackIssues = false
    private val toastManager = ToastManager()

    /** Invoked when the sheet is dismissed by any route, including the scrim. */
    var onDismissed: () -> Unit = {}

    private lateinit var container: NestedScrollView
    private lateinit var title: TextView
    private lateinit var closeButton: ImageView
    private lateinit var feedbackView: FeedbackView

    private val feedbackType: FeedbackType
        get() = requireNotNull(argumentsOrDefault.getSerializable(ARG_FEEDBACK_TYPE) as? FeedbackType) {
            "WidgetFeedbackSheet requires a FeedbackType in $ARG_FEEDBACK_TYPE"
        }

    private val guildDeleteViewModel: GuildDeleteFeedbackSheetViewModel by appViewModels {
        GuildDeleteFeedbackSheetViewModel(
            argumentsOrDefault.getLong(ARG_GUILD_DELETE_FEEDBACK_GUILD_ID),
        )
    }

    /**
     * `CallFeedbackSheetViewModel` and `StreamFeedbackSheetViewModel` live in
     * `widgets/voice/feedback` and are not reconstructed yet, so those two types resolve through
     * [voiceViewModelProvider]. Without it the sheet has nothing to render and closes itself
     * rather than showing an empty form.
     */
    private val viewModel: FeedbackSheetViewModel?
        get() = when (feedbackType) {
            FeedbackType.GUILD_DELETE -> guildDeleteViewModel
            FeedbackType.CALL, FeedbackType.STREAM -> voiceViewModelProvider(this, feedbackType)
        }

    override fun getContentViewResId(): Int = R.layout.widget_feedback_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.feedback_sheet_container)
        title = view.findViewById(R.id.feedback_sheet_title)
        closeButton = view.findViewById(R.id.feedback_sheet_close_button)
        feedbackView = view.findViewById(R.id.feedback_sheet_feedback_view)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        val viewModel = viewModel ?: run {
            dismiss()
            return
        }
        viewModel.observeViewState()
            .ui(this)
            .subscribe(::updateView)
        viewModel.observeEvents()
            .ui(this)
            .subscribe(::handleEvent)
    }

    override fun onResume() {
        super.onResume()
        dialog?.setCanceledOnTouchOutside(true)
        closeButton.setOnClickListener { viewModel?.submitForm() }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        onDismissed()
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    private fun updateView(viewState: FeedbackSheetViewModel.ViewState) {
        // First emission that actually carries reasons: collapse to the faces, then expand.
        val shouldExpandForIssues = !isShowingFeedbackIssues && viewState.feedbackIssues.isNotEmpty()
        if (shouldExpandForIssues) {
            if (viewState.showFaceRatings) updatePeekHeightPx(container.measuredHeight)
            setBottomSheetState(BottomSheetBehavior.STATE_COLLAPSED)
        }

        title.setText(viewState.titleTextResId)
        feedbackView.updateView(
            ratingSummaryPromptText = viewState.promptTextResId?.let(::getString),
            selectedFeedbackRating = viewState.selectedFeedbackRating,
            onSadRatingClick = { viewModel?.selectRating(FeedbackRating.BAD) },
            onNeutralRatingClick = { viewModel?.selectRating(FeedbackRating.NEUTRAL) },
            onHappyRatingClick = { viewModel?.selectRating(FeedbackRating.GOOD) },
            issuesHeaderText = getString(viewState.issuesHeaderTextResId),
            feedbackIssues = viewState.feedbackIssues,
            onIssueClick = { issue -> viewModel?.selectIssue(issue, getString(issue.reasonStringRes)) },
        )

        if (shouldExpandForIssues) {
            Observable.timer(SHEET_EXPAND_DELAY_MS, TimeUnit.MILLISECONDS)
                .ui(this)
                .appSubscribe(errorClass = WidgetFeedbackSheet::class.java) {
                    isShowingFeedbackIssues = true
                    setBottomSheetState(BottomSheetBehavior.STATE_EXPANDED)
                }
        }
    }

    private fun handleEvent(event: FeedbackSheetViewModel.Event) {
        when (event) {
            is FeedbackSheetViewModel.Event.Submitted ->
                if (event.showConfirmation) {
                    // The toast has to land before the sheet goes, hence the delay before closing.
                    Observable.timer(SHEET_DISMISS_DELAY_MS, TimeUnit.MILLISECONDS)
                        .ui(this)
                        .appSubscribe(errorClass = WidgetFeedbackSheet::class.java) {
                            context?.let { toastManager.show(it, R.string.call_feedback_confirmation) }
                            dialog?.cancel()
                        }
                } else {
                    dialog?.cancel()
                }

            is FeedbackSheetViewModel.Event.NavigateToIssueDetails -> {
                issueDetailsLauncher?.invoke(
                    requireContext(),
                    event.pendingFeedback,
                    event.showCxLinkForIssueDetails,
                )
                dialog?.cancel()
            }
        }
    }

    companion object {
        const val ARG_FEEDBACK_TYPE = "ARG_FEEDBACK_TYPE"
        const val ARG_CALL_FEEDBACK_CONFIG = "ARG_CONFIG"
        const val ARG_GUILD_DELETE_FEEDBACK_GUILD_ID = "ARG_GUILD_DELETE_FEEDBACK_GUILD_ID"
        const val ARG_STREAM_FEEDBACK_MEDIA_SESSION_ID = "ARG_STREAM_FEEDBACK_MEDIA_SESSION_ID"

        /** Note the trailing space — it is in the reference key and must be preserved. */
        const val ARG_STREAM_FEEDBACK_STREAM_KEY = "ARG_STREAM_FEEDBACK_STREAM_KEY "

        private const val SHEET_DISMISS_DELAY_MS = 600L
        private const val SHEET_EXPAND_DELAY_MS = 100L

        /**
         * Supplies the call/stream view models once `widgets/voice/feedback/call` and `.../stream`
         * are reconstructed. **Reported to the voice wave.**
         */
        @JvmStatic
        var voiceViewModelProvider: (WidgetFeedbackSheet, FeedbackType) -> FeedbackSheetViewModel? =
            { _, _ -> null }

        /**
         * `WidgetIssueDetailsForm.launch(context, pendingFeedback, showCxLink)` in the reference.
         * That screen lives in `widgets/voice/feedback` and is not reconstructed; until it is, a
         * reason that needs free text submits without it rather than opening a blank screen.
         */
        @JvmStatic
        var issueDetailsLauncher: ((Context, PendingFeedback, Boolean) -> Unit)? = null

        fun newInstance(feedbackType: FeedbackType): WidgetFeedbackSheet =
            WidgetFeedbackSheet().apply {
                arguments = Bundle().apply { putSerializable(ARG_FEEDBACK_TYPE, feedbackType) }
            }
    }
}
