package com.discord.widgets.chat.list.adapter

import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View
import com.discord.R
import com.discord.widgets.chat.list.entries.ChatListEntry

/**
 * Footer of the recent-mentions list: the "no more mentions" artwork and the pro-tip card.
 *
 * The arm that runs out from the hand is a single bitmap that has to tile horizontally to fill
 * whatever width is left, which cannot be expressed in the layout — hence the drawable is mutated
 * and switched to [Shader.TileMode.REPEAT] on bind.
 */
class WidgetChatListAdapterItemMentionFooter(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_mention_footer, adapter) {

    private val divider: View = itemView.findViewById(R.id.chat_list_item_mention_footer_divider)
    private val tile: View = itemView.findViewById(R.id.tile)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        // When the footer is the only row there is nothing above it to separate it from.
        val isOnlyRow = adapter.data.list.size == 1
        divider.visibility = if (isOnlyRow) View.GONE else View.VISIBLE
        tileBackground()
    }

    private fun tileBackground() {
        val background = tile.background as? BitmapDrawable ?: return
        background.mutate()
        background.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}
