package com.discord.widgets.feedback

import com.discord.R
import com.discord.widgets.guild_delete_feedback.GuildDeleteFeedback
import com.discord.widgets.voice.feedback.FeedbackIssue
import com.discord.widgets.voice.feedback.FeedbackRating
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable

/**
 * One sheet layout serves call quality, stream quality and server deletion, so the shape of
 * [FeedbackSheetViewModel] is the only thing those three flows agree on. These are the parts
 * `WidgetFeedbackSheet` branches on.
 */
class FeedbackSheetContractTest {

    /** A view model that implements nothing but the required members. */
    private class MinimalViewModel(
        private val state: FeedbackSheetViewModel.ViewState,
    ) : FeedbackSheetViewModel {
        var selectedIssue: FeedbackIssue? = null
        var submitted = false

        override fun observeViewState(): Observable<FeedbackSheetViewModel.ViewState> =
            Observable.just(state)

        override fun observeEvents(): Observable<FeedbackSheetViewModel.Event> = Observable.empty()

        override fun selectIssue(feedbackIssue: FeedbackIssue?, reasonDescription: String?) {
            selectedIssue = feedbackIssue
        }

        override fun submitForm() {
            submitted = true
        }
    }

    private fun viewState(promptTextResId: Int?) = FeedbackSheetViewModel.ViewState(
        selectedFeedbackRating = FeedbackRating.NO_RESPONSE,
        feedbackIssues = emptyList(),
        titleTextResId = R.string.call_feedback_sheet_title,
        promptTextResId = promptTextResId,
        issuesHeaderTextResId = R.string.call_feedback_issue_section_header,
    )

    /**
     * `showFaceRatings` is derived, not passed: a flow that supplies a rating prompt is by
     * definition a rated flow. The sheet uses it to decide whether to measure a peek height for the
     * faces before expanding, so getting it wrong makes the sheet open at the wrong size.
     */
    @Test
    fun `face ratings are shown exactly when a rating prompt is supplied`() {
        assertTrue(viewState(R.string.call_feedback_prompt).showFaceRatings)
        assertFalse(viewState(null).showFaceRatings)
    }

    /**
     * Face ratings are optional, so [FeedbackSheetViewModel.selectRating] has a default body. The
     * sheet wires the three faces unconditionally and would crash an unrated flow without it.
     */
    @Test
    fun `an unrated flow can ignore rating taps`() {
        val viewModel = MinimalViewModel(viewState(null))
        viewModel.selectRating(FeedbackRating.GOOD)
        viewModel.selectRating(FeedbackRating.BAD)
        assertFalse(viewModel.submitted)

        // Reason taps still land; only the faces are optional.
        viewModel.selectIssue(FeedbackIssue.GUILD_DELETE_OTHER, "Other")
        assertEquals(FeedbackIssue.GUILD_DELETE_OTHER, viewModel.selectedIssue)
    }

    /**
     * A half-filled form crosses an `Intent`/`Bundle` boundary on its way to the issue-details
     * screen, so it has to serialise. This is why the marker interface exists.
     */
    @Test
    fun `pending feedback survives a serialization round trip`() {
        val pending: PendingFeedback = GuildDeleteFeedback(1L, FeedbackIssue.GUILD_DELETE_OTHER)
        val bytes = ByteArrayOutputStream().also { out ->
            ObjectOutputStream(out).use { it.writeObject(pending) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        assertEquals(pending, restored)
    }
}
