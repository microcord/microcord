package com.discord.widgets.chat.list.actions

import com.discord.R
import com.discord.utilities.dimen.pixelsToDp
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.facebook.drawee.view.SimpleDraweeView

class EmojiViewHolder(adapter: WidgetChatListActionsEmojisAdapter) :
    MGRecyclerViewHolder<WidgetChatListActionsEmojisAdapter, EmojiItem>(
        R.layout.view_chat_list_actions_emoji_item,
        adapter,
    ) {
    override fun onConfigure(position: Int, data: EmojiItem) {
        val item = data as EmojiItem.EmojiData
        val image = itemView as SimpleDraweeView
        val imageSize = IconUtils.getMediaProxySize(
            pixelsToDp(image.layoutParams.width - image.paddingStart - image.paddingEnd) * 2,
        )
        itemView.contentDescription = item.emoji.firstName
        itemView.setOnClickListener { adapter.onClickEmoji(item.emoji) }
        MGImages.setImage(image, item.emoji.getImageUri(true, imageSize, itemView.context))
    }
}
