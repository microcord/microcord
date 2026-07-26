package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.strings.SearchStringProvider

/**
 * The media kinds `has:` accepts.
 *
 * [restParamValue] is the wire value; the label the user types and sees is localised and comes from
 * [SearchStringProvider]. Ordinal order is `LINK, EMBED, FILE, VIDEO, IMAGE, SOUND, STICKER` — the
 * suggestion list and the icon table are both indexed by it. (The reference's decompiled source
 * lists them alphabetically; the real order comes from the enum's static initialiser.)
 */
enum class HasAnswerOption(val restParamValue: String) {
    LINK("link"),
    EMBED("embed"),
    FILE("file"),
    VIDEO("video"),
    IMAGE("image"),
    SOUND("sound"),
    STICKER("sticker");

    fun getLocalizedInputText(searchStringProvider: SearchStringProvider): String = when (this) {
        LINK -> searchStringProvider.linkAnswerString
        EMBED -> searchStringProvider.embedAnswerString
        FILE -> searchStringProvider.fileAnswerString
        VIDEO -> searchStringProvider.videoAnswerString
        IMAGE -> searchStringProvider.imageAnswerString
        SOUND -> searchStringProvider.soundAnswerString
        STICKER -> searchStringProvider.stickerAnswerString
    }

    companion object {
        /** Reverse of [getLocalizedInputText]. Throws on anything the parser did not produce. */
        fun getOptionFromString(
            match: String,
            searchStringProvider: SearchStringProvider,
        ): HasAnswerOption = values().firstOrNull { it.getLocalizedInputText(searchStringProvider) == match }
            ?: throw IllegalArgumentException("invalid match: $match")
    }
}
