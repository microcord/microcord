package com.discord.widgets.guild_delete_feedback

import com.discord.R
import com.discord.widgets.feedback.FeedbackSheetViewModel
import com.discord.widgets.voice.feedback.FeedbackIssue
import com.discord.widgets.voice.feedback.FeedbackRating
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Subscription

class GuildDeleteFeedbackSheetViewModelTest {

    private val reports = mutableListOf<GuildDeleteFeedbackSubmitter.Report>()
    private val events = mutableListOf<FeedbackSheetViewModel.Event>()
    private var subscription: Subscription? = null

    @Before
    fun installSink() {
        reports.clear()
        events.clear()
        GuildDeleteFeedbackSubmitter.reportSink = { reports += it }
    }

    @After
    fun removeSink() {
        subscription?.unsubscribe()
        GuildDeleteFeedbackSubmitter.reportSink = { }
    }

    private fun viewModel(guildId: Long = 42L): GuildDeleteFeedbackSheetViewModel =
        GuildDeleteFeedbackSheetViewModel(guildId).also {
            subscription = it.observeEvents().subscribe { event -> events += event }
        }

    /**
     * The sheet renders this state directly. `promptTextResId` stays null, which is what makes
     * `showFaceRatings` false — a guild-delete prompt has reasons only, no rating faces.
     */
    @Test
    fun `the initial state is a reasons-only sheet`() {
        val state = viewModel().requireViewState()
        assertEquals(FeedbackRating.NO_RESPONSE, state.selectedFeedbackRating)
        assertEquals(R.string.guild_delete_feedback_header, state.titleTextResId)
        assertEquals(R.string.guild_delete_feedback_body, state.issuesHeaderTextResId)
        assertNull(state.promptTextResId)
        assertFalse(state.showFaceRatings)
    }

    /** Six reasons in a random order, then "Other" — pinned last so it never floats into the list. */
    @Test
    fun `reasons are shuffled with Other pinned last`() {
        val orderings = mutableSetOf<List<FeedbackIssue>>()
        repeat(40) {
            val issues = GuildDeleteFeedbackSheetViewModel(1L).requireViewState().feedbackIssues
            assertEquals(7, issues.size)
            assertEquals(FeedbackIssue.GUILD_DELETE_OTHER, issues.last())
            assertEquals(
                setOf(
                    FeedbackIssue.GUILD_DELETE_TOO_HARD,
                    FeedbackIssue.GUILD_DELETE_TEST,
                    FeedbackIssue.GUILD_DELETE_ACCIDENT,
                    FeedbackIssue.GUILD_DELETE_TEMPLATE,
                    FeedbackIssue.GUILD_DELETE_LONELY,
                    FeedbackIssue.GUILD_DELETE_INACTIVE,
                ),
                issues.dropLast(1).toSet(),
            )
            orderings += issues
        }
        assertTrue("the reason order never varied", orderings.size > 1)
    }

    /**
     * Any reason but "Other" is complete on its own, so it submits without a second screen. The
     * reported label comes from the enum, **not** from the localised row text handed to
     * [FeedbackSheetViewModel.selectIssue] — analytics dimensions must not vary by locale.
     */
    @Test
    fun `picking a concrete reason submits immediately and confirms`() {
        viewModel(7L).selectIssue(FeedbackIssue.GUILD_DELETE_LONELY, "Serveur vide")

        val report = reports.single()
        assertEquals(7L, report.guildId)
        assertEquals("Empty server", report.reason)
        assertFalse(report.skipped)
        assertEquals(listOf(FeedbackSheetViewModel.Event.Submitted(true)), events)
    }

    /** "Other" needs free text, so it hands off rather than reporting an empty explanation. */
    @Test
    fun `picking Other navigates to the details form without reporting`() {
        viewModel(9L).selectIssue(FeedbackIssue.GUILD_DELETE_OTHER, "Other")

        assertTrue(reports.isEmpty())
        val event = events.single() as FeedbackSheetViewModel.Event.NavigateToIssueDetails
        assertEquals(GuildDeleteFeedback(9L, FeedbackIssue.GUILD_DELETE_OTHER), event.pendingFeedback)
        assertFalse(event.showCxLinkForIssueDetails)
    }

    /** Closing the sheet without answering is still an outcome; it reports as skipped, unconfirmed. */
    @Test
    fun `dismissing without a reason reports a skip and suppresses the confirmation`() {
        viewModel(3L).submitForm()

        val report = reports.single()
        assertEquals(3L, report.guildId)
        assertNull(report.reason)
        assertTrue(report.skipped)
        assertEquals(listOf(FeedbackSheetViewModel.Event.Submitted(false)), events)
    }

    /**
     * Tapping a reason submits, and the sheet then closes — which submits again. Without the
     * idempotence guard every answered prompt would be reported twice.
     */
    @Test
    fun `submitting twice reports once`() {
        val viewModel = viewModel(11L)
        viewModel.selectIssue(FeedbackIssue.GUILD_DELETE_TEST, "Testing purposes")
        viewModel.submitForm()

        assertEquals(1, reports.size)
        assertEquals(1, events.size)
    }
}
