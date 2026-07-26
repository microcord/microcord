package com.discord.widgets.chat.input.autocomplete.adapter

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.i18n.FormatUtils
import com.discord.utilities.icon.IconUtils
import com.discord.views.PileView
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.EmojiUpsellPlaceholder

/**
 * "Get N more emoji with Nitro" row, appended below the emoji matches when the query also matched
 * emoji the user cannot use.
 *
 * The count is a nested format: `autocomplete_emoji_upsell` ("Get {count} with Nitro") takes the
 * *pluralised phrase* from `autocomplete_emoji_upsell_count` as its `{count}` argument, rather than
 * the bare number.
 */
class EmojiAutocompleteUpsellViewHolder(
    itemView: View,
    private val onItemSelected: (Autocompletable) -> Unit,
) : RecyclerView.ViewHolder(itemView) {

    private val text: TextView = itemView.findViewById(R.id.chat_input_emoji_upsell_text)
    private val pile: PileView = itemView.findViewById(R.id.chat_input_emoji_upsell_pile)

    fun bind(placeholder: EmojiUpsellPlaceholder) {
        val resources = itemView.resources
        val count = placeholder.lockedTotal
        val countPhrase = FormatUtils.format(
            resources.getQuantityString(R.plurals.autocomplete_emoji_upsell_count, count),
            // The plural's own placeholder is `{#}`, not `{count}`.
            "#" to count,
        )
        text.text = FormatUtils.format(
            resources.getString(R.string.autocomplete_emoji_upsell),
            "count" to countPhrase,
        )

        pile.setItems(
            placeholder.lockedFirstThree.map { emoji ->
                PileView.Item(
                    getImageUri = { requestedSize ->
                        emoji.getImageUri(
                            placeholder.allowEmojiToAnimate,
                            IconUtils.getMediaProxySize(requestedSize),
                            itemView.context,
                        )
                    },
                    getInitials = null,
                )
            },
        )

        itemView.setOnClickListener { onItemSelected(placeholder) }
    }
}
