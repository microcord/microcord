package com.discord.widgets.bugreports

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.bugreport.Feature

/** One selectable feature area: its name, and the squad that owns it. */
class BugReportFeatureViewHolder(
    itemView: View,
    private val onClickListener: (Feature) -> Unit,
) : RecyclerView.ViewHolder(itemView) {

    private val featureName: TextView = itemView.findViewById(R.id.feature_list_item_feature)
    private val squad: TextView = itemView.findViewById(R.id.feature_list_item_squad)

    fun bind(data: Feature) {
        featureName.text = data.name
        squad.text = data.squad
        itemView.setOnClickListener { onClickListener(data) }
    }
}
