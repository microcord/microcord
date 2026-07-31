package com.discord.widgets.chat.input.emoji

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R

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
