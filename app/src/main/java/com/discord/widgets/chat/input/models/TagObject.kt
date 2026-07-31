package com.discord.widgets.chat.input.models

import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import java.util.regex.Pattern

interface TagObject : MGRecyclerDataPayload {
    val displayTag: String
    val tag: String
    val tagRegex: Pattern?
}
