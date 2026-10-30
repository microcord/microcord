package com.discord.widgets.chat.input

import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.MainThread
import com.discord.utilities.cache.SharedPreferencesProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lytefast.flexinput.model.Attachment

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

    /**
     * Unsent attachments of [channelId], rebuilt from what [setAttachments] stored. Only references
     * are kept, never file copies, so one that no longer resolves is the caller's to drop.
     */
    @MainThread
    fun getAttachments(channelId: Long): List<SourcedAttachment<Any>> =
        preferences?.getString(ATTACHMENTS_PREFIX + channelId, null)
            ?.let { runCatching { gson.fromJson<List<StoredAttachment>>(it, storedListType) }.getOrNull() }
            .orEmpty()
            .map { SourcedAttachment(it.id, Uri.parse(it.uri), it.name, null, it.source, it.spoiler) }

    @MainThread
    fun setAttachments(channelId: Long, attachments: List<Attachment<*>>) {
        if (channelId <= 0) return
        val stored = attachments.map {
            val sourced = it as? SourcedAttachment<*>
            StoredAttachment(it.id, it.uri.toString(), it.displayName, sourced?.source.orEmpty(), sourced?.spoiler == true)
        }
        preferences?.edit()?.apply {
            if (stored.isEmpty()) remove(ATTACHMENTS_PREFIX + channelId)
            else putString(ATTACHMENTS_PREFIX + channelId, gson.toJson(stored))
        }?.apply()
    }

    private data class StoredAttachment(
        val id: Long,
        val uri: String,
        val name: String,
        val source: String,
        val spoiler: Boolean,
    )

    object Provider {
        val instance: MessageDraftsRepo by lazy {
            MessageDraftsRepo(SharedPreferencesProvider.getPrefsSessionDurable())
        }
        fun get() = instance
    }

    private companion object {
        const val KEY_PREFIX = "CHAT_DRAFT_"
        const val ATTACHMENTS_PREFIX = "CHAT_DRAFT_ATTACHMENTS_"
        val gson = Gson()
        val storedListType = object : TypeToken<List<StoredAttachment>>() {}.type
    }
}
