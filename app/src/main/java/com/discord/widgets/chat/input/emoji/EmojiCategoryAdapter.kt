package com.discord.widgets.chat.input.emoji

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.domain.emoji.EmojiCategory

/**
 * The horizontal category rail.
 *
 * Stable ids are the category/guild identity rather than the position so that re-selecting only
 * animates the overline instead of rebinding every guild icon — the rail is re-published on every
 * grid scroll, which would otherwise re-issue an image request per frame.
 */
class EmojiCategoryAdapter(
    private val onCategorySelected: (EmojiCategoryItem) -> Unit,
) : RecyclerView.Adapter<EmojiCategoryViewHolder>() {

    private var items: List<EmojiCategoryItem> = emptyList()

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].stableId

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is EmojiCategoryItem.StandardItem -> EmojiCategoryItem.TYPE_STANDARD
        is EmojiCategoryItem.GuildItem -> EmojiCategoryItem.TYPE_GUILD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiCategoryViewHolder {
        val layout = if (viewType == EmojiCategoryItem.TYPE_GUILD) {
            R.layout.emoji_category_item_guild
        } else {
            R.layout.emoji_category_item_standard
        }
        return EmojiCategoryViewHolder(
            LayoutInflater.from(parent.context).inflate(layout, parent, false),
            onCategorySelected,
        )
    }

    override fun onBindViewHolder(holder: EmojiCategoryViewHolder, position: Int) =
        holder.bind(items[position])

    fun setItems(items: List<EmojiCategoryItem>) {
        this.items = items
        notifyDataSetChanged()
    }

    /** Rail position of the category owning [gridPosition], or -1 when nothing does. */
    fun positionOfGridPosition(gridPosition: Int): Int =
        items.indexOfFirst { gridPosition in it.categoryRange }
}

/** One category icon plus the brand-coloured overline that marks it selected. */
class EmojiCategoryViewHolder(
    itemView: View,
    private val onCategorySelected: (EmojiCategoryItem) -> Unit,
) : RecyclerView.ViewHolder(itemView) {

    private val overline: View = itemView.findViewById(R.id.expression_picker_category_selection_overline)
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
