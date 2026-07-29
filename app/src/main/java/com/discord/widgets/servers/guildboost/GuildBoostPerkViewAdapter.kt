package com.discord.widgets.servers.guildboost

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.drawable.setCompoundDrawablesCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class GuildBoostPerkViewAdapter(recycler: RecyclerView) :
    MGRecyclerAdapterSimple<GuildBoostPerkViewAdapter.GuildBoostPerkViewListItem>(recycler) {

    fun configure(perkItems: List<GuildBoostPerkViewListItem>) = setData(perkItems)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PerkViewHolder =
        if (viewType == TYPE_LIST_ITEM) PerkViewHolder(this) else throw invalidViewTypeException(viewType)

    data class GuildBoostPerkViewListItem(
        val iconResId: Int,
        val text: CharSequence,
    ) : MGRecyclerDataPayload {
        override val type = TYPE_LIST_ITEM
        override val key = text.toString()
    }

    class PerkViewHolder(adapter: GuildBoostPerkViewAdapter) :
        MGRecyclerViewHolder<GuildBoostPerkViewAdapter, GuildBoostPerkViewListItem>(
            R.layout.view_nitro_boost_perks_list_item,
            adapter,
        ) {
        override fun onConfigure(position: Int, data: GuildBoostPerkViewListItem) {
            (itemView as TextView).apply {
                text = data.text
                setCompoundDrawablesCompat(start = data.iconResId)
            }
        }
    }

    private companion object {
        const val TYPE_LIST_ITEM = 1
    }
}
