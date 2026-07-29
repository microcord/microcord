package com.discord.widgets.chat.list.actions

import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class MoreEmojisViewHolder(adapter: WidgetChatListActionsEmojisAdapter) :
    MGRecyclerViewHolder<WidgetChatListActionsEmojisAdapter, EmojiItem>(
        R.layout.view_chat_list_actions_emoji_item_more,
        adapter,
    ) {
    override fun onConfigure(position: Int, data: EmojiItem) {
        itemView.setOnClickListener { adapter.onClickMoreEmojis() }
    }
}
