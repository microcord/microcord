package com.discord.utilities.feature

import androidx.annotation.StringRes
import com.discord.R
import com.discord.utilities.cache.SharedPreferencesProvider

/**
 * Toggleable components of a private-channel row in the DM list.
 *
 * Read at row-bind time, so a change applies on the next list rebuild without a restart. The
 * defaults reproduce the reference row; MESSAGE and TIMESTAMP are additions a user opts into.
 */
object DmListCustomization {
    enum class Component(@param:StringRes val label: Int) {
        STATUS_DOT(R.string.dm_list_customization_status_dot),
        STATUS(R.string.dm_list_customization_status),
        GUILD_TAG(R.string.dm_list_customization_guild_tag),
        MESSAGE(R.string.dm_list_customization_message_preview),
        TIMESTAMP(R.string.dm_list_customization_timestamp),
    }

    fun visible(component: Component): Boolean =
        ClientSettings.dmComponent(component).get(SharedPreferencesProvider.get())

    fun setVisible(component: Component, visible: Boolean) {
        ClientSettings.dmComponent(component).set(SharedPreferencesProvider.get(), visible)
    }

    /** Whether the message preview takes over the row's description line, replacing the status. */
    fun previewReplacesStatus(messageEnabled: Boolean, isDirectMessage: Boolean, preview: String?): Boolean =
        messageEnabled && isDirectMessage && !preview.isNullOrBlank()
}
