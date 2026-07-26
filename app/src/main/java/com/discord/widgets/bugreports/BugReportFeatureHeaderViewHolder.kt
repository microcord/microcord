package com.discord.widgets.bugreports

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.bugreport.Feature

/**
 * A squad heading in the feature-area list.
 *
 * The heading is itself tappable and reports the synthetic [Feature] the adapter built for the
 * squad, so "file this against the whole squad" is one tap — that is deliberate in the reference,
 * not an accident of reusing the row's click listener.
 */
class BugReportFeatureHeaderViewHolder(
    itemView: View,
    private val onClickListener: (Feature) -> Unit,
) : RecyclerView.ViewHolder(itemView) {

    private val gap: View = itemView.findViewById(R.id.gap)
    private val header: TextView = itemView.findViewById(R.id.header)

    /** [isTop] suppresses the leading gap for the first heading in the list. */
    fun bind(isTop: Boolean, feature: Feature) {
        gap.visibility = if (isTop) View.GONE else View.VISIBLE
        header.text = feature.name
        header.setOnClickListener { onClickListener(feature) }
    }
}
