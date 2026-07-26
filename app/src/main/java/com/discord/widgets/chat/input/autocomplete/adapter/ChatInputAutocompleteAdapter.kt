package com.discord.widgets.chat.input.autocomplete.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandAutocompletable
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandChoiceAutocompletable
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandLoadingPlaceholder
import com.discord.widgets.chat.input.autocomplete.ApplicationPlaceholder
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.ChannelAutocompletable
import com.discord.widgets.chat.input.autocomplete.EmojiAutocompletable
import com.discord.widgets.chat.input.autocomplete.EmojiUpsellPlaceholder
import com.discord.widgets.chat.input.autocomplete.GlobalRoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable

/**
 * The autocomplete popup's list.
 *
 * The unusual part is [resizeRecyclerToData]: the popup is not a fixed-height list, it grows to fit
 * its rows and stops at a cap, so that offering two matches does not push the composer up by four
 * rows of empty space. Heights are per-row-kind because the command header and the emoji upsell are
 * physically different sizes.
 */
class ChatInputAutocompleteAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onItemSelected: ((Autocompletable) -> Unit)? = null

    private var data: List<Autocompletable> = emptyList()
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    fun getItem(position: Int): Autocompletable = data[position]

    override fun getItemCount(): Int = data.size

    /**
     * Stable ids keyed on the underlying entity, not the position, so that a row that survives a
     * refilter is not re-created (and its avatar not re-fetched) while the user is still typing.
     */
    override fun getItemId(position: Int): Long = when (val item = getItem(position)) {
        is ApplicationCommandAutocompletable -> item.command.hashCode().toLong()
        is ApplicationCommandChoiceAutocompletable -> item.choice.value.hashCode().toLong()
        is ApplicationCommandLoadingPlaceholder -> item.application?.id ?: 0L
        is ApplicationPlaceholder -> item.application.id
        is ChannelAutocompletable -> item.channel.id
        is EmojiAutocompletable -> item.emoji.getUniqueId().hashCode().toLong()
        is EmojiUpsellPlaceholder -> item.lockedFirstThree.hashCode().toLong()
        is GlobalRoleAutocompletable -> item.text.hashCode().toLong()
        is RoleAutocompletable -> item.role.id
        is UserAutocompletable -> item.user.id
    }

    override fun getItemViewType(position: Int): Int = when (data[position]) {
        is ApplicationCommandLoadingPlaceholder -> VIEW_TYPE_COMMAND_LOADING_ITEM
        is ApplicationPlaceholder -> VIEW_TYPE_COMMAND_HEADER_ITEM
        is EmojiUpsellPlaceholder -> VIEW_TYPE_EMOJI_UPSELL_AUTOCOMPLETE
        else -> VIEW_TYPE_AUTOCOMPLETE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_AUTOCOMPLETE_ITEM -> AutocompleteItemViewHolder(
                inflater.inflate(R.layout.widget_chat_input_autocomplete_item, parent, false),
            ) { onAutocompletableSelected(it) }

            VIEW_TYPE_COMMAND_HEADER_ITEM -> CommandHeaderViewHolder(
                inflater.inflate(R.layout.widget_chat_input_command_application_header_item, parent, false),
            )

            VIEW_TYPE_COMMAND_LOADING_ITEM -> ApplicationCommandLoadingViewHolder(
                inflater.inflate(R.layout.widget_chat_input_slash_loading_item, parent, false),
            )

            VIEW_TYPE_EMOJI_UPSELL_AUTOCOMPLETE -> EmojiAutocompleteUpsellViewHolder(
                inflater.inflate(R.layout.widget_chat_input_emoji_autocomplete_upsell_item, parent, false),
            ) { onAutocompletableSelected(it) }

            else -> error("Cannot create view holder. Unknown View Type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            VIEW_TYPE_AUTOCOMPLETE_ITEM ->
                (holder as AutocompleteItemViewHolder).bind(data[position])

            VIEW_TYPE_COMMAND_HEADER_ITEM ->
                (holder as CommandHeaderViewHolder).bind(data[position] as ApplicationPlaceholder)

            VIEW_TYPE_COMMAND_LOADING_ITEM -> Unit

            VIEW_TYPE_EMOJI_UPSELL_AUTOCOMPLETE ->
                (holder as EmojiAutocompleteUpsellViewHolder).bind(data[position] as EmojiUpsellPlaceholder)

            else -> error("Cannot bind. Unknown View Type")
        }
    }

    fun onAutocompletableSelected(autocompletable: Autocompletable) {
        onItemSelected?.invoke(autocompletable)
    }

    @JvmOverloads
    fun setData(
        autocompletables: List<Autocompletable>,
        hasStickers: Boolean = false,
        sizeToMax: Boolean = false,
    ) {
        resizeRecyclerToData(autocompletables, hasStickers, sizeToMax)
        data = autocompletables
        notifyDataSetChanged()
    }

    fun scrollToPosition(position: Int, offset: Int = 0) {
        (recyclerView?.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, offset)
    }

    private fun resizeRecyclerToData(
        data: List<Autocompletable>,
        hasStickers: Boolean,
        sizeToMax: Boolean,
    ) {
        val recyclerView = recyclerView ?: return
        val maxDp = if (hasStickers) MENTION_CONTAINER_MAX_SIZE_WITH_STICKERS else MENTION_CONTAINER_MAX_SIZE
        val heightDp = if (sizeToMax) maxDp else getDataHeightOrMax(data, maxDp)
        val density = recyclerView.resources.displayMetrics.density
        recyclerView.layoutParams?.height = (heightDp * density).toInt()
        recyclerView.requestLayout()
    }

    private fun getDataHeightOrMax(data: List<Autocompletable>, maxAllowedHeight: Int): Int {
        var total = 0
        for (item in data) {
            total += when (item) {
                is ApplicationPlaceholder -> COMMAND_HEADER_HEIGHT
                is EmojiUpsellPlaceholder -> EMOJI_AUTOCOMPLETE_UPSELL_HEIGHT
                else -> MENTION_ROW_HEIGHT_DEFAULT
            }
            if (total >= maxAllowedHeight) return maxAllowedHeight
        }
        return total
    }

    companion object {
        const val VIEW_TYPE_AUTOCOMPLETE_ITEM = 0
        const val VIEW_TYPE_COMMAND_HEADER_ITEM = 1
        const val VIEW_TYPE_COMMAND_LOADING_ITEM = 2
        const val VIEW_TYPE_EMOJI_UPSELL_AUTOCOMPLETE = 3

        const val CONTAINER_ROW_MAX_VISIBLE_COUNT = 4

        private const val COMMAND_HEADER_HEIGHT = 32
        private const val EMOJI_AUTOCOMPLETE_UPSELL_HEIGHT = 52
        private const val MENTION_CONTAINER_MAX_SIZE = 176
        private const val MENTION_CONTAINER_MAX_SIZE_WITH_STICKERS = 132
        private const val MENTION_ROW_HEIGHT_DEFAULT = 44
    }
}
