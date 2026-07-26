package com.discord.models.domain.emoji

import android.content.Context
import android.os.Parcelable
import java.util.regex.Pattern

/**
 * Anything the emoji picker, the chat input autocomplete and the reaction bar can render, whether
 * it is a Unicode glyph ([ModelEmojiUnicode]) or a guild upload ([ModelEmojiCustom]).
 */
interface Emoji : Parcelable {

    /** Every name this emoji answers to; the first is the display name. */
    val names: List<String>

    val firstName: String

    /** `:name:`, as typed in the chat box. */
    fun getCommand(name: String? = null): String

    /** What gets inserted into the chat box when the emoji is picked. */
    fun getChatInputText(): String

    /** What the typed text is rewritten to before the message is sent. */
    fun getMessageContentReplacement(): String

    /** Key used by the reactions API (`name:id` for custom, the surrogate pair for Unicode). */
    fun getReactionKey(): String

    /** Stable identity used for dedupe and for recent/favourite bookkeeping. */
    fun getUniqueId(): String

    fun getImageUri(allowAnimated: Boolean, imageSize: Int, context: Context): String

    /** Matches occurrences of this emoji in message content. */
    fun getRegex(name: String? = null): Pattern

    fun isAvailable(): Boolean

    fun isUsable(): Boolean

    fun requiresColons(): Boolean
}
