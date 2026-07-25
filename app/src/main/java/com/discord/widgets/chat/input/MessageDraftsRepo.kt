package com.discord.widgets.chat.input

import android.content.SharedPreferences
import androidx.annotation.MainThread
import com.discord.utilities.cache.SharedPreferencesProvider

class MessageDraftsRepo(private val preferences: SharedPreferences? = null) {
    private val drafts = preferences?.all.orEmpty().mapNotNull { (key, value) ->
        key.removePrefix(KEY_PREFIX).toLongOrNull()?.let { it to (value as? String).orEmpty() }
    }.filter { it.second.isNotEmpty() }.toMap(LinkedHashMap())

    @MainThread fun getTextChannelInput(channelId: Long): CharSequence? = drafts[channelId]

    @MainThread
    fun setTextChannelInput(channelId: Long, text: CharSequence?) {
        if (channelId <= 0) return
        val value = text?.toString().orEmpty()
        if (value.isEmpty()) drafts.remove(channelId) else drafts[channelId] = value
        preferences?.edit()?.apply {
            if (value.isEmpty()) remove(KEY_PREFIX + channelId) else putString(KEY_PREFIX + channelId, value)
        }?.apply()
    }

    object Provider {
        val instance: MessageDraftsRepo by lazy {
            MessageDraftsRepo(SharedPreferencesProvider.getPrefsSessionDurable())
        }
        fun get() = instance
    }

    private companion object { const val KEY_PREFIX = "CHAT_DRAFT_" }
}
