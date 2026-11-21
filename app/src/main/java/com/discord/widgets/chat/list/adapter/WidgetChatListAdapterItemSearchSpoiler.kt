package com.discord.widgets.chat.list.adapter

import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.databinding.WidgetChatListAdapterItemSearchSpoilerBinding
import com.discord.stores.StoreStream
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.SearchSpoilerResultEntry

/** A search hit from a spoiler channel the viewer has not revealed: the channel name over a box that reveals it. */
class WidgetChatListAdapterItemSearchSpoiler(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_search_spoiler, adapter) {

    private val binding = WidgetChatListAdapterItemSearchSpoilerBinding.bind(itemView)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as SearchSpoilerResultEntry
        val channel = StoreStream.getChannels().getChannel(entry.channelId)
        binding.searchSpoilerChannel.text = channel?.getDisplayNameOrDefault(itemView.context)
        itemView.setOnClickListener { adapter.eventHandler.onSearchSpoilerClicked(entry.messageId) }
    }
}
