package com.discord.widgets.nux

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.google.android.material.card.MaterialCardView

/** A row in the template list: either a template card or the "or pick your own" caption. */
sealed class GuildTemplateViewType(val viewType: Int) {

    data class Template(val guildTemplate: GuildTemplate) : GuildTemplateViewType(TEMPLATE)

    object Text : GuildTemplateViewType(TEXT)

    companion object {
        const val TEXT: Int = 0
        const val TEMPLATE: Int = 1
    }
}

/**
 * Backs the template picker list.
 *
 * The "Create My Own" card and the caption below it are not part of the caller's data — the [items]
 * setter always prepends them, so no caller can drop or reorder the escape hatch.
 */
class GuildTemplatesAdapter(
    private val callbacks: Callbacks,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        fun onTemplateClick(guildTemplate: GuildTemplate)
    }

    var items: List<GuildTemplateViewType> = emptyList()
        set(value) {
            field = listOf(
                GuildTemplateViewType.Template(GuildTemplate.CreateMyOwn),
                GuildTemplateViewType.Text,
            ) + value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = items[position].viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            GuildTemplateViewType.TEXT -> GuildTemplateTextViewHolder(
                inflater.inflate(R.layout.nux_guild_template_text_view_holder, parent, false) as TextView,
            )

            GuildTemplateViewType.TEMPLATE -> GuildTemplateCardViewHolder(
                inflater.inflate(
                    R.layout.nux_guild_template_card_view_holder,
                    parent,
                    false,
                ) as MaterialCardView,
                callbacks,
            )

            else -> throw IllegalStateException("Missing view type for $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (item is GuildTemplateViewType.Template) {
            (holder as? GuildTemplateCardViewHolder)?.bind(item.guildTemplate)
        }
    }
}
