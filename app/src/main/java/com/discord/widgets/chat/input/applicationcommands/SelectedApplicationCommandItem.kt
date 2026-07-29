package com.discord.widgets.chat.input.applicationcommands

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommandOption
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

class SelectedApplicationCommandItem(
    val application: Application? = null,
    val option: ApplicationCommandOption? = null,
    var highlighted: Boolean = false,
    var error: Boolean = false,
    var completed: Boolean = false,
    val title: String? = null,
    val heading: String? = null,
) : MGRecyclerDataPayload {
    override val type: Int = when {
        option != null -> TYPE_PARAM
        title != null -> TYPE_TITLE
        else -> TYPE_SECTION_HEADING
    }

    override val key: String = when (type) {
        TYPE_TITLE -> title ?: "title"
        TYPE_PARAM -> option?.name ?: "option"
        TYPE_SECTION_HEADING -> heading ?: "heading"
        else -> ""
    }

    companion object {
        const val TYPE_TITLE = 0
        const val TYPE_PARAM = 1
        const val TYPE_SECTION_HEADING = 2
    }
}
