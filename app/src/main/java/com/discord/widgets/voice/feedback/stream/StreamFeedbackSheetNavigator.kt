package com.discord.widgets.voice.feedback.stream

import androidx.fragment.app.FragmentManager
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.feedback.WidgetFeedbackSheet
import com.discord.widgets.voice.feedback.WidgetIssueDetailsForm
import java.util.Random

object StreamFeedbackSheetNavigator {
    private val clock = ClockFactory.get()
    private val random = Random()

    fun enqueueNotice(streamKey: String, mediaSessionId: String?, triggerRateDenominator: Int) {
        if (triggerRateDenominator < 1 || random.nextInt(triggerRateDenominator) != 0) return
        val notices = StoreStream.getNotices()
        val name = "Stream feedback notice for streamKey: $streamKey"
        val requestedAt = clock.currentTimeMillis()
        notices.requestToShow(
            StoreNotices.Notice(
                name = name,
                requestedShowTimestamp = requestedAt,
                priority = StoreNotices.PRIORITY_HIGH,
                delayPeriodMs = 0L,
                sinceLastPeriodMs = 0L,
            ) { activity ->
                if (clock.currentTimeMillis() - requestedAt < NOTICE_FRESHNESS_MS) {
                    show(activity.supportFragmentManager, streamKey, mediaSessionId)
                }
                notices.markSeen(name)
                true
            },
        )
    }

    internal fun show(fragmentManager: FragmentManager, streamKey: String, mediaSessionId: String?) {
        WidgetIssueDetailsForm.installBindings()
        WidgetFeedbackSheet.newInstance(WidgetFeedbackSheet.FeedbackType.STREAM).apply {
            requireArguments().putString(WidgetFeedbackSheet.ARG_STREAM_FEEDBACK_STREAM_KEY, streamKey)
            requireArguments().putString(
                WidgetFeedbackSheet.ARG_STREAM_FEEDBACK_MEDIA_SESSION_ID,
                mediaSessionId,
            )
        }.show(fragmentManager, WidgetFeedbackSheet::class.java.name)
    }

    private const val NOTICE_FRESHNESS_MS = 3_000L
}
