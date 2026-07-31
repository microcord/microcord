package com.discord.widgets.chat.input.emoji

import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.domain.emoji.EmojiCategory

class EmojiCategoryViewHolder(
    itemView: View,
    private val onCategorySelected: (EmojiCategoryItem) -> Unit,
) : RecyclerView.ViewHolder(itemView) {
    private val overline: View = itemView.findViewById(R.id.overline)
    private val standardIcon: ImageView? = itemView.findViewById(R.id.emoji_category_item_standard_icon)
    private val guildIcon: GuildIcon? = itemView.findViewById(R.id.emoji_category_item_guild_icon)

    fun bind(item: EmojiCategoryItem) {
        overline.visibility = if (item.isSelected) View.VISIBLE else View.GONE
        when (item) {
            is EmojiCategoryItem.StandardItem -> {
                standardIcon?.setImageResource(categoryIcon(item.emojiCategory))
                itemView.contentDescription = itemView.context.getString(categoryName(item.emojiCategory))
            }
            is EmojiCategoryItem.GuildItem -> {
                guildIcon?.updateView(item.guild)
                itemView.contentDescription = item.guild.name
            }
        }
        itemView.setOnClickListener { onCategorySelected(item) }
    }

    companion object {
        fun categoryIcon(category: EmojiCategory): Int = when (category) {
            EmojiCategory.FAVORITE -> R.drawable.ic_emoji_picker_category_favorites_star
            EmojiCategory.RECENT -> R.drawable.ic_emoji_picker_category_recent
            EmojiCategory.CUSTOM -> R.drawable.ic_emoji_picker_category_custom
            EmojiCategory.PEOPLE -> R.drawable.ic_emoji_picker_category_people
            EmojiCategory.NATURE -> R.drawable.ic_emoji_picker_category_nature
            EmojiCategory.FOOD -> R.drawable.ic_emoji_picker_category_food
            EmojiCategory.ACTIVITY -> R.drawable.ic_emoji_picker_category_activity
            EmojiCategory.TRAVEL -> R.drawable.ic_emoji_picker_category_travel
            EmojiCategory.OBJECTS -> R.drawable.ic_emoji_picker_category_objects
            EmojiCategory.SYMBOLS -> R.drawable.ic_emoji_picker_category_symbols
            EmojiCategory.FLAGS -> R.drawable.ic_emoji_picker_category_flags
        }

        fun categoryName(category: EmojiCategory): Int = when (category) {
            EmojiCategory.FAVORITE -> R.string.category_favorite
            EmojiCategory.RECENT -> R.string.emoji_category_recent
            EmojiCategory.CUSTOM -> R.string.emoji_category_custom
            EmojiCategory.PEOPLE -> R.string.emoji_category_people
            EmojiCategory.NATURE -> R.string.emoji_category_nature
            EmojiCategory.FOOD -> R.string.emoji_category_food
            EmojiCategory.ACTIVITY -> R.string.emoji_category_activity
            EmojiCategory.TRAVEL -> R.string.emoji_category_travel
            EmojiCategory.OBJECTS -> R.string.emoji_category_objects
            EmojiCategory.SYMBOLS -> R.string.emoji_category_symbols
            EmojiCategory.FLAGS -> R.string.emoji_category_flags
        }
    }
}
