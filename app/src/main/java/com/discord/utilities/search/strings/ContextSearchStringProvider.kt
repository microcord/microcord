package com.discord.utilities.search.strings

import android.content.Context
import com.discord.R

/** [SearchStringProvider] backed by the app's string resources. */
class ContextSearchStringProvider(context: Context) : SearchStringProvider {
    override val fromFilterString: String = context.getString(R.string.search_filter_from)
    override val inFilterString: String = context.getString(R.string.search_filter_in)
    override val mentionsFilterString: String = context.getString(R.string.search_filter_mentions)
    override val hasFilterString: String = context.getString(R.string.search_filter_has)

    override val linkAnswerString: String = context.getString(R.string.search_answer_has_link)
    override val embedAnswerString: String = context.getString(R.string.search_answer_has_embed)
    override val fileAnswerString: String = context.getString(R.string.search_answer_has_attachment)
    override val videoAnswerString: String = context.getString(R.string.search_answer_has_video)
    override val imageAnswerString: String = context.getString(R.string.search_answer_has_image)
    override val soundAnswerString: String = context.getString(R.string.search_answer_has_sound)
    override val stickerAnswerString: String = context.getString(R.string.search_answer_has_sticker)
}
