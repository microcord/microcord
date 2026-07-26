package com.discord.widgets.stage.sheet

import com.discord.utilities.cache.SharedPreferencesProvider

/**
 * Remembers whether the "you are in the audience" explainer has been shown. Persisted, so it
 * appears exactly once per install rather than once per cold start.
 */
object WidgetStageAudienceNoticeManager {
    private const val STAGE_AUDIENCE_NOTICE_SHOWN_KEY = "STAGE_AUDIENCE_NOTICE_SHOWN_KEY"

    fun hasUserSeenAudienceNotice(): Boolean =
        SharedPreferencesProvider.get().getBoolean(STAGE_AUDIENCE_NOTICE_SHOWN_KEY, false)

    fun markUserSeenAudienceNotice() {
        SharedPreferencesProvider.get().edit()
            .putBoolean(STAGE_AUDIENCE_NOTICE_SHOWN_KEY, true)
            .apply()
    }
}
