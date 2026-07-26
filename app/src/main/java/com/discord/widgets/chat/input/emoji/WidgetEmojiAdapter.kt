package com.discord.widgets.chat.input.emoji

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.guild.Guild
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The emoji grid.
 *
 * The list is **flat** — headers and emoji in one stream — rather than one list per category, and
 * that is the design decision everything else follows from. A flat list is what lets the category
 * rail track the scroll position, lets a header stay pinned above its own emoji, and lets search
 * results replace the content without changing adapter type. Headers are made full-width by
 * [spanSizeLookup] rather than by a second RecyclerView.
 */
class WidgetEmojiAdapter(
    private val onEmojiSelected: OnEmojiSelectedListener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** One row of the flat list. */
    sealed class Item {
        abstract val key: String
        abstract val type: Int

        /** A section title. Spans the full grid width. */
        sealed class HeaderItem : Item() {
            override val type: Int = ITEM_TYPE_HEADER

            /** Names a built-in category. */
            data class StandardHeaderItem(val emojiCategory: EmojiCategory) : HeaderItem() {
                override val key: String = "header-${emojiCategory.name}"
            }

            /** Names one guild's uploads. */
            data class GuildHeaderItem(val guild: Guild) : HeaderItem() {
                override val key: String = "header-guild-${guild.id}"
            }

            /** A literal string, used for upsell sections that are not a category. */
            data class StringHeaderItem(@StringRes val textResId: Int) : HeaderItem() {
                override val key: String = "header-string-$textResId"
            }
        }

        /**
         * One emoji.
         *
         * [guildName] is carried for the accessibility label — two guilds can upload `:kek:` and
         * the grid otherwise announces them identically.
         */
        data class EmojiItem(
            val guildName: String?,
            val emoji: Emoji,
            val emojiName: String,
            val allowEmojisToAnimate: Boolean,
        ) : Item() {
            override val type: Int = ITEM_TYPE_EMOJI
            override val key: String = emoji.getUniqueId()
        }
    }

    private var items: List<Item> = emptyList()

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = items[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ITEM_TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.expression_picker_header_item, parent, false),
            )
            else -> EmojiViewHolder(
                inflater.inflate(R.layout.emoji_picker_emoji_item, parent, false),
                onEmojiSelected,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.HeaderItem -> (holder as HeaderViewHolder).bind(item)
            is Item.EmojiItem -> (holder as EmojiViewHolder).bind(item)
        }
    }

    fun setItems(items: List<Item>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun getItem(position: Int): Item? = items.getOrNull(position)

    /**
     * Grid span rule: headers take the whole row, emoji take one cell each. Installed by the
     * fragment on the [GridLayoutManager] it creates.
     */
    fun spanSizeLookup(spanCount: Int): GridLayoutManager.SpanSizeLookup =
        object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (items.getOrNull(position) is Item.HeaderItem) spanCount else 1
        }

    private class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: TextView = itemView.findViewById(R.id.header_item_text)

        fun bind(item: Item.HeaderItem) {
            text.text = when (item) {
                is Item.HeaderItem.StandardHeaderItem ->
                    itemView.context.getString(EmojiCategoryViewHolder.categoryName(item.emojiCategory))
                is Item.HeaderItem.GuildHeaderItem -> item.guild.name
                is Item.HeaderItem.StringHeaderItem -> itemView.context.getString(item.textResId)
            }
        }
    }

    private class EmojiViewHolder(
        itemView: View,
        private val onEmojiSelected: OnEmojiSelectedListener,
    ) : RecyclerView.ViewHolder(itemView) {

        private val image: SimpleDraweeView = itemView as SimpleDraweeView

        fun bind(item: Item.EmojiItem) {
            image.setImageURI(
                item.emoji.getImageUri(item.allowEmojisToAnimate, MAX_EMOJI_SIZE_PX, image.context),
            )
            image.contentDescription = if (item.guildName != null) {
                "${item.emojiName}, ${item.guildName}"
            } else {
                item.emojiName
            }
            image.setOnClickListener { onEmojiSelected.onEmojiSelected(item.emoji) }
        }
    }

    companion object {
        const val DEFAULT_NUM_COLUMNS = 8

        const val ITEM_TYPE_HEADER = 0
        const val ITEM_TYPE_EMOJI = 1

        /**
         * Requested pixel size for every emoji image. Fixed rather than measured so that the CDN
         * URL is identical for the same emoji everywhere in the grid and Fresco serves all of them
         * from one cache entry.
         */
        const val MAX_EMOJI_SIZE_PX = 64
    }
}
