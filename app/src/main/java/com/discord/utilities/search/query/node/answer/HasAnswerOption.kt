package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.strings.SearchStringProvider

/**
 * The media kinds `has:` accepts.
 *
 * [restParamValue] is the wire value; the label the user types and sees is localised and comes from
 * [SearchStringProvider]. The two differ for [FORWARD], which the user types as `forward` and the
 * API calls `snapshot`.
 *
 * Declaration order is the reference's `has` map order. [OFFERED] is the order the suggestion list
 * walks, which is a different one — do not conflate them.
 */
enum class HasAnswerOption(val restParamValue: String) {
    LINK("link"),
    EMBED("embed"),
    POLL("poll"),
    FORWARD("snapshot"),
    FILE("file"),
    VIDEO("video"),
    IMAGE("image"),
    SOUND("sound"),
    STICKER("sticker");

    fun getLocalizedInputText(searchStringProvider: SearchStringProvider): String = when (this) {
        LINK -> searchStringProvider.linkAnswerString
        EMBED -> searchStringProvider.embedAnswerString
        POLL -> searchStringProvider.pollAnswerString
        FORWARD -> searchStringProvider.forwardAnswerString
        FILE -> searchStringProvider.fileAnswerString
        VIDEO -> searchStringProvider.videoAnswerString
        IMAGE -> searchStringProvider.imageAnswerString
        SOUND -> searchStringProvider.soundAnswerString
        STICKER -> searchStringProvider.stickerAnswerString
    }

    companion object {
        /** `has:-image` excludes rather than requires; the `-` rides on the value, not the key. */
        const val NEGATION_PREFIX = "-"

        /** Suggestion order, which the reference lists separately from the map order above. */
        val OFFERED = listOf(IMAGE, VIDEO, LINK, FILE, EMBED, SOUND, POLL, STICKER, FORWARD)

        /** Reverse of [getLocalizedInputText]. Throws on anything the parser did not produce. */
        fun getOptionFromString(
            match: String,
            searchStringProvider: SearchStringProvider,
        ): HasAnswerOption {
            val label = match.removePrefix(NEGATION_PREFIX)
            return values().firstOrNull { it.getLocalizedInputText(searchStringProvider) == label }
                ?: throw IllegalArgumentException("invalid match: $match")
        }
    }
}
