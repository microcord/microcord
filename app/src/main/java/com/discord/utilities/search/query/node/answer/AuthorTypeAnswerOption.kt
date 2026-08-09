package com.discord.utilities.search.query.node.answer

import com.discord.utilities.search.strings.SearchStringProvider

/** The account kinds `authorType:` accepts. Label and wire value are identical in the reference. */
enum class AuthorTypeAnswerOption(val restParamValue: String) {
    USER("user"),
    BOT("bot"),
    WEBHOOK("webhook");

    fun getLocalizedInputText(searchStringProvider: SearchStringProvider): String = when (this) {
        USER -> searchStringProvider.userAuthorTypeAnswerString
        BOT -> searchStringProvider.botAuthorTypeAnswerString
        WEBHOOK -> searchStringProvider.webhookAuthorTypeAnswerString
    }

    companion object {
        fun getOptionFromString(
            match: String,
            searchStringProvider: SearchStringProvider,
        ): AuthorTypeAnswerOption {
            val label = match.removePrefix(HasAnswerOption.NEGATION_PREFIX)
            return values().firstOrNull { it.getLocalizedInputText(searchStringProvider) == label }
                ?: throw IllegalArgumentException("invalid match: $match")
        }
    }
}
