package com.discord.widgets.home

import java.io.Serializable

/**
 * One-shot instructions handed to [WidgetHome] through the launching intent's
 * `com.discord.intent.extra.EXTRA_HOME_CONFIG` extra.
 *
 * These are things that must happen *after* the home surface is on screen and cannot be expressed
 * as navigation: showing a guild's welcome sheet the first time it is opened from an invite, or
 * opening a scheduled event's details for a link that pointed at one. [ageGated] records that the
 * user reached home through the age gate.
 *
 * [Serializable] because it travels as an intent extra, exactly as the reference does.
 */
data class HomeConfig(
    val guildWelcomeSheetId: Long? = null,
    val guildScheduledEventId: Long? = null,
    val ageGated: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L

        /** Intent extra key the reference reads this off. */
        const val EXTRA_HOME_CONFIG = "com.discord.intent.extra.EXTRA_HOME_CONFIG"
    }
}
