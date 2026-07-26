package com.discord.widgets.nux

import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.drawable.setCompoundDrawablesCompat
import com.google.android.material.card.MaterialCardView

/**
 * A tappable template card: the template's icon, its label, and a trailing chevron.
 *
 * The chevron is set programmatically alongside the leading icon because both compound drawables
 * have to be replaced in one call — setting only the start drawable would clear the end one.
 */
class GuildTemplateCardViewHolder(
    private val card: MaterialCardView,
    private val callbacks: GuildTemplatesAdapter.Callbacks,
) : RecyclerView.ViewHolder(card) {

    private val label: TextView = card.findViewById(R.id.nux_guild_template_text)

    fun bind(guildTemplate: GuildTemplate) {
        label.setText(guildTemplate.titleRes)
        label.setCompoundDrawablesCompat(
            start = guildTemplate.drawableRes,
            end = R.drawable.icon_carrot,
        )
        card.setOnClickListener { callbacks.onTemplateClick(guildTemplate) }
    }
}

/** The static "or pick your own" caption between the escape hatch and the real templates. */
class GuildTemplateTextViewHolder(textView: TextView) : RecyclerView.ViewHolder(textView)
