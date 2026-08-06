package com.discord.widgets.voice.feedback

import com.discord.models.domain.ModelApplicationStream
import com.discord.widgets.feedback.FeedbackSheetViewModel
import com.discord.widgets.voice.feedback.call.CallFeedbackSheetViewModel
import com.discord.widgets.voice.feedback.call.CallFeedbackSubmitter
import com.discord.widgets.voice.feedback.stream.StreamFeedbackSheetViewModel
import com.discord.widgets.voice.feedback.stream.StreamFeedbackSubmitter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFeedbackFlowTest {
    @Test
    fun `call bad rating asks for a reason and submits its reference code`() {
        var report: PendingFeedback.CallFeedback? = null
        val initial = PendingFeedback.CallFeedback(10L, "rtc", 500L, "media")
        val viewModel = CallFeedbackSheetViewModel(
            config = CallFeedbackSheetViewModel.Config(10L, "rtc", "media", 500L),
            submitter = CallFeedbackSubmitter(initial) { report = it },
            onShown = {},
        )

        viewModel.selectRating(FeedbackRating.BAD)
        assertEquals(FeedbackRating.BAD, viewModel.requireViewState().selectedFeedbackRating)
        assertEquals(FeedbackIssue.OTHER, viewModel.requireViewState().feedbackIssues.last())
        assertEquals(CallFeedbackSheetViewModel.ISSUES.toSet(), viewModel.requireViewState().feedbackIssues.dropLast(1).toSet())

        viewModel.selectIssue(FeedbackIssue.AUDIO_ECHOS, "Audio had echos or feedback")
        assertEquals(4, report?.reasonCode)
        assertEquals(FeedbackRating.BAD, report?.feedbackRating)
    }

    @Test
    fun `other call issue hands the pending form to details without submitting`() {
        var report: PendingFeedback.CallFeedback? = null
        val events = mutableListOf<FeedbackSheetViewModel.Event>()
        val viewModel = CallFeedbackSheetViewModel(
            config = CallFeedbackSheetViewModel.Config(10L, null, null, null),
            submitter = CallFeedbackSubmitter(PendingFeedback.CallFeedback(10L)) { report = it },
            onShown = {},
        )
        viewModel.observeEvents().subscribe(events::add)

        viewModel.selectRating(FeedbackRating.NEUTRAL)
        viewModel.selectIssue(FeedbackIssue.OTHER, "Other")

        assertNull(report)
        val event = events.single() as FeedbackSheetViewModel.Event.NavigateToIssueDetails
        assertTrue(event.showCxLinkForIssueDetails)
        assertEquals(1, (event.pendingFeedback as PendingFeedback.CallFeedback).reasonCode)
    }

    @Test
    fun `good stream rating submits immediately and only once`() {
        val reports = mutableListOf<PendingFeedback.StreamFeedback>()
        val viewModel = StreamFeedbackSheetViewModel(
            streamKey = "guild:1:2:3",
            mediaSessionId = "media",
            report = reports::add,
        )

        viewModel.selectRating(FeedbackRating.GOOD)
        viewModel.submitForm()

        assertEquals(1, reports.size)
        assertEquals(FeedbackRating.GOOD, reports.single().feedbackRating)
        assertTrue(viewModel.requireViewState().feedbackIssues.isEmpty())
    }

    @Test
    fun `issue details submitter is idempotent`() {
        val details = mutableListOf<String?>()
        val viewModel = IssueDetailsFormViewModel(object : FeedbackSubmitter {
            override fun submit(issueDetails: String?) {
                details += issueDetails
            }
        })

        viewModel.submitForm("It crackled")
        viewModel.submitForm("duplicate")

        assertEquals(listOf("It crackled"), details)
    }

    @Test
    fun `stream issues map to reference analytics reasons`() {
        assertEquals("BLACK_SCREEN", StreamFeedbackSubmitter.reason(FeedbackIssue.STREAM_REPORT_ENDED_BLACK))
        assertEquals("OUT_OF_SYNC", StreamFeedbackSubmitter.reason(FeedbackIssue.STREAM_REPORT_ENDED_OUT_OF_SYNC))
        assertEquals("STREAM_STOPPED_UNEXPECTEDLY", StreamFeedbackSubmitter.reason(FeedbackIssue.STREAM_REPORT_ENDED_STREAM_STOPPED_UNEXPECTEDLY))
        assertEquals("OTHER", StreamFeedbackSubmitter.reason(FeedbackIssue.OTHER))
    }

    @Test
    fun `voice pending feedback survives process state serialization`() {
        val pending: PendingFeedback = PendingFeedback.StreamFeedback(
            ModelApplicationStream.CallStream(2L, 3L),
            FeedbackRating.BAD,
            FeedbackIssue.STREAM_REPORT_ENDED_LAGGING,
            "media",
            "details",
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(pending) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }

        assertEquals(pending, restored)
        assertFalse(bytes.isEmpty())
    }
}
