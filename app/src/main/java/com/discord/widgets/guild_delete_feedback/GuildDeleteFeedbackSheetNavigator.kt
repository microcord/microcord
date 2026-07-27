package com.discord.widgets.guild_delete_feedback

import androidx.fragment.app.FragmentManager
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.feedback.WidgetFeedbackSheet

/**
 * Shows the "why are you deleting this server?" sheet after a guild is deleted.
 *
 * The sheet cannot be shown at the moment of deletion — the user is still on a screen that is
 * about to be torn down — so it is enqueued as a notice and the queue picks the next stable
 * activity. The **3 second staleness check** is the point of the indirection: if the queue only
 * gets round to it much later the user has moved on, and the prompt is dropped rather than
 * appearing out of nowhere. It is marked seen either way, so a stale prompt is not re-offered.
 */
object GuildDeleteFeedbackSheetNavigator {

    private val clock: Clock = ClockFactory.get()

    /** How long an enqueued prompt stays relevant. */
    private const val NOTICE_FRESHNESS_MS = 3_000L

    fun enqueueNotice(guildId: Long) {
        val storeNotices = StoreStream.getNotices()
        val noticeName = "Delete Guild Feedback for $guildId"
        val requestedShowTimestamp = clock.currentTimeMillis()
        storeNotices.requestToShow(
            StoreNotices.Notice(
                name = noticeName,
                requestedShowTimestamp = requestedShowTimestamp,
                priority = StoreNotices.PRIORITY_HIGH,
                delayPeriodMs = 0L,
                sinceLastPeriodMs = 0L,
            ) { activity ->
                if (clock.currentTimeMillis() - requestedShowTimestamp < NOTICE_FRESHNESS_MS) {
                    show(activity.supportFragmentManager, guildId)
                }
                storeNotices.markSeen(noticeName)
                true
            },
        )
    }

    /** Shows the sheet immediately. Callers that already own a stable host use this directly. */
    fun show(fragmentManager: FragmentManager, guildId: Long) {
        val sheet = WidgetFeedbackSheet.newInstance(WidgetFeedbackSheet.FeedbackType.GUILD_DELETE)
        sheet.requireArguments().putLong(
            WidgetFeedbackSheet.ARG_GUILD_DELETE_FEEDBACK_GUILD_ID,
            guildId,
        )
        sheet.show(fragmentManager, WidgetFeedbackSheet::class.java.name)
    }
}
