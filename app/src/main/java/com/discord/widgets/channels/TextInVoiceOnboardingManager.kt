package com.discord.widgets.channels

import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.chat.list.TextInVoiceFeatureFlag

class TextInVoiceOnboardingManager {
    val clock: Clock = ClockFactory.get()

    fun shouldShowTextInVoiceOnboarding(userId: Long, guildId: Long?): Boolean =
        guildId != null &&
            TextInVoiceFeatureFlag.isEnabled(guildId) &&
            !StoreStream.getNotices().hasSeen(NOTICE_NAME_TEXT_IN_VOICE_ONBOARDED) &&
            clock.currentTimeMillis() >= SnowflakeUtils.DISCORD_EPOCH + (userId ushr 22) + MIN_VIEW_AGE_IN_MS

    fun markSeen() {
        StoreStream.getNotices().markSeen(NOTICE_NAME_TEXT_IN_VOICE_ONBOARDED)
    }

    fun show(onCtaClicked: () -> Unit) {
        StoreStream.getNotices().requestToShow(
            StoreNotices.Notice(
                name = NOTICE_NAME_TEXT_IN_VOICE_ONBOARDED,
                requestedShowTimestamp = 0L,
                priority = 0,
                persist = true,
                // The reference restricts this to WidgetCallFullscreen, which is not reconstructed yet.
                validScreens = emptyList(),
                delayPeriodMs = 0L,
                sinceLastPeriodMs = 0L,
            ) { activity ->
                WidgetTextInVoiceOnboarding.show(activity.supportFragmentManager, onCtaClicked)
                true
            },
        )
    }
}
