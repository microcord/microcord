package com.discord.utilities.search.strings

import android.content.Context
import com.discord.R

/** [SearchStringProvider] backed by the app's string resources. */
class ContextSearchStringProvider(context: Context) : SearchStringProvider {
    override val fromFilterString: String = context.getString(R.string.search_filter_from)
    override val inFilterString: String = context.getString(R.string.search_filter_in)
    override val mentionsFilterString: String = context.getString(R.string.search_filter_mentions)
    override val hasFilterString: String = context.getString(R.string.search_filter_has)
    override val duringFilterString: String = context.getString(R.string.search_filter_during)
    override val onFilterString: String = context.getString(R.string.search_filter_on)
    override val beforeFilterString: String = context.getString(R.string.search_filter_before)
    override val afterFilterString: String = context.getString(R.string.search_filter_after)
    override val authorTypeFilterString: String = context.getString(R.string.search_filter_author_type)
    override val linkFromFilterString: String = context.getString(R.string.search_filter_link_from)
    override val fileTypeFilterString: String = context.getString(R.string.search_filter_file_type)
    override val fileNameFilterString: String = context.getString(R.string.search_filter_file_name)
    override val pinnedFilterString: String = context.getString(R.string.search_filter_pinned)

    override val linkAnswerString: String = context.getString(R.string.search_answer_has_link)
    override val embedAnswerString: String = context.getString(R.string.search_answer_has_embed)
    override val fileAnswerString: String = context.getString(R.string.search_answer_has_attachment)
    override val videoAnswerString: String = context.getString(R.string.search_answer_has_video)
    override val imageAnswerString: String = context.getString(R.string.search_answer_has_image)
    override val soundAnswerString: String = context.getString(R.string.search_answer_has_sound)
    override val stickerAnswerString: String = context.getString(R.string.search_answer_has_sticker)
    override val pollAnswerString: String = context.getString(R.string.search_answer_has_poll)
    override val forwardAnswerString: String = context.getString(R.string.search_answer_has_forward)

    override val userAuthorTypeAnswerString: String = context.getString(R.string.search_answer_author_type_user)
    override val botAuthorTypeAnswerString: String = context.getString(R.string.search_answer_author_type_bot)
    override val webhookAuthorTypeAnswerString: String = context.getString(R.string.search_answer_author_type_webhook)
}
