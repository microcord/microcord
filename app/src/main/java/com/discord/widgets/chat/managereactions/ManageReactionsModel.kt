package com.discord.widgets.chat.managereactions

import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

data class ManageReactionsModel(
    val reactionItems: List<ManageReactionsEmojisAdapter.ReactionEmojiItem>,
    val userItems: List<MGRecyclerDataPayload>,
)
