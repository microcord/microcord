package com.discord.models.domain

import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory

/** Shared reading of the mute flag plus its optional expiry. */
object NotificationSettingsModelUtils {

    /**
     * A guild/channel counts as muted only while the mute is still in force: a [muteConfig] whose
     * end time has passed unmutes it even though the boolean flag is still set server-side.
     */
    @JvmStatic
    @JvmOverloads
    fun isMuted(
        muted: Boolean,
        muteConfig: ModelMuteConfig?,
        clock: Clock = ClockFactory.get(),
    ): Boolean {
        if (!muted) return false
        val endTimeMs = muteConfig?.endTimeMs ?: return true
        return endTimeMs > clock.currentTimeMillis()
    }
}
