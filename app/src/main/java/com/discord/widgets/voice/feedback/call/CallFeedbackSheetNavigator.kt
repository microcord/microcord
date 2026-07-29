package com.discord.widgets.voice.feedback.call

import androidx.fragment.app.FragmentManager
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.feedback.WidgetFeedbackSheet
import com.discord.widgets.voice.feedback.WidgetIssueDetailsForm
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet
import java.util.Random

object CallFeedbackSheetNavigator {
    private val clock = ClockFactory.get()
    private val random = Random()

    fun install() {
        WidgetVoiceBottomSheet.Navigation.enqueueCallFeedback = ::enqueueNotice
    }

    fun enqueueNotice(
        channelId: Long,
        rtcConnectionId: String?,
        mediaSessionId: String?,
        callDurationMs: Long?,
        triggerRateDenominator: Int,
    ) {
        if (triggerRateDenominator < 1 || !shouldEnqueue(random.nextInt(triggerRateDenominator))) return
        val notices = StoreStream.getNotices()
        val name = "Call feedback notice for rtcConnectionId: $rtcConnectionId"
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
                    show(
                        activity.supportFragmentManager,
                        channelId,
                        rtcConnectionId,
                        mediaSessionId,
                        callDurationMs,
                    )
                }
                notices.markSeen(name)
                true
            },
        )
    }

    internal fun show(
        fragmentManager: FragmentManager,
        channelId: Long,
        rtcConnectionId: String?,
        mediaSessionId: String?,
        callDurationMs: Long?,
    ) {
        WidgetIssueDetailsForm.installBindings()
        WidgetFeedbackSheet.newInstance(WidgetFeedbackSheet.FeedbackType.CALL).apply {
            requireArguments().putParcelable(
                WidgetFeedbackSheet.ARG_CALL_FEEDBACK_CONFIG,
                CallFeedbackSheetViewModel.Config(
                    channelId,
                    rtcConnectionId,
                    mediaSessionId,
                    callDurationMs,
                ),
            )
        }.show(fragmentManager, WidgetFeedbackSheet::class.java.name)
    }

    internal fun shouldEnqueue(randomDraw: Int): Boolean = randomDraw == 0

    private const val NOTICE_FRESHNESS_MS = 3_000L
}
