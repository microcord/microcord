package com.discord.widgets.chat.input.sticker

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.input.emoji.GuildIcon

sealed class StickerCategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val overline: View = itemView.findViewById(R.id.overline)

    protected fun setSelected(isSelected: Boolean) {
        overline.visibility = if (isSelected) View.VISIBLE else View.GONE
    }

    class Recent(itemView: View) : StickerCategoryViewHolder(itemView) {
        fun configure(item: StickerCategoryItem.RecentItem, onRecentClicked: () -> Unit) {
            setSelected(item.isSelected)
            itemView.setOnClickListener { onRecentClicked() }
            itemView.contentDescription = itemView.context.getString(
                R.string.sticker_picker_categories_recent,
            )
        }
    }

    class Pack(itemView: View) : StickerCategoryViewHolder(itemView) {
        private val avatar: StickerView = itemView.findViewById(R.id.sticker_category_item_pack_avatar)

        fun configure(
            item: StickerCategoryItem.PackItem,
            onPackClicked: (StickerCategoryItem.PackItem) -> Unit,
        ) {
            avatar.setSticker(item.pack.coverSticker, NEVER_ANIMATE)
            avatar.setOnClickListener { onPackClicked(item) }
            avatar.contentDescription = item.pack.name
            itemView.setOnClickListener { onPackClicked(item) }
            setSelected(item.isSelected)
        }

        private companion object {
            const val NEVER_ANIMATE = 2
        }
    }

    class Guild(itemView: View) : StickerCategoryViewHolder(itemView) {
        private val guildIcon: GuildIcon = itemView.findViewById(R.id.sticker_category_item_guild_icon)

        fun configure(
            item: StickerCategoryItem.GuildItem,
            onGuildClicked: (StickerCategoryItem.GuildItem) -> Unit,
        ) {
            guildIcon.updateView(item.guild)
            setSelected(item.isSelected)
            itemView.setOnClickListener { onGuildClicked(item) }
            itemView.contentDescription = item.guild.name
        }
    }
}
