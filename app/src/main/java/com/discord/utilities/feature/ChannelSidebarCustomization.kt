package com.discord.utilities.feature

import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.cache.SharedPreferencesProvider

/**
 * Toggleable action buttons at the top of the right-hand channel panel.
 *
 * Guild channels and private channels get separate surfaces, each stored under its own key, so a
 * user can hide the thread browser on channels without touching the call button on DMs. Visibility
 * is read at bind time, so a change applies on the next panel rebuild without a restart.
 */
object ChannelSidebarCustomization {
    enum class Surface { DM, CHANNEL }

    enum class Button(@param:StringRes val label: Int) {
        SEARCH(R.string.search),
        THREADS(R.string.threads),
        MEDIA(R.string.media),
        PINS(R.string.pins),
        NOTIFICATIONS(R.string.notifications),
        SETTINGS(R.string.settings),
        CALL(R.string.call),
        VIDEO(R.string.video),
    }

    private val channelButtons = listOf(
        Button.SEARCH,
        Button.THREADS,
        Button.MEDIA,
        Button.PINS,
        Button.NOTIFICATIONS,
        Button.SETTINGS,
    )

    private val dmButtons = listOf(
        Button.CALL,
        Button.VIDEO,
        Button.MEDIA,
        Button.NOTIFICATIONS,
        Button.SEARCH,
    )

    fun buttons(surface: Surface) = if (surface == Surface.DM) dmButtons else channelButtons

    fun visible(surface: Surface, button: Button): Boolean =
        SharedPreferencesProvider.get().getBoolean(key(surface, button), button != Button.PINS)

    fun setVisible(surface: Surface, button: Button, visible: Boolean) {
        SharedPreferencesProvider.get().edit().putBoolean(key(surface, button), visible).apply()
    }

    private fun key(surface: Surface, button: Button) =
        "channel_sidebar_customization_${surface.name.lowercase()}_${button.name.lowercase()}"
}
