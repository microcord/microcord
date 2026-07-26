package com.discord.widgets.bugreports

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.bugreport.Feature

/**
 * The feature-area picker list: a squad heading followed by that squad's features, repeated.
 *
 * [setData] takes the flat, already-sorted feature list the view model produces and inserts a
 * heading every time the squad changes — which is why the input has to stay sorted by
 * `squad + name`. The heading carries a synthetic [Feature] whose `name` *is* the squad, so
 * selecting a heading files the report against the squad as a whole.
 *
 * The reference calls `notifyDataSetChanged` here; this is a [ListAdapter] instead, per the
 * project's recycler contract.
 */
class BugReportFeatureAdapter(
    private val onClickListener: (Feature) -> Unit,
) : ListAdapter<BugReportFeatureAdapter.BugReportFeatureItem, RecyclerView.ViewHolder>(DIFFER) {

    sealed class BugReportFeatureItem {
        data class FeatureItem(val feature: Feature) : BugReportFeatureItem()
        data class HeaderItem(val header: Feature) : BugReportFeatureItem()
    }

    fun setData(data: List<Feature>) {
        val items = ArrayList<BugReportFeatureItem>(data.size)
        var currentSquad: String? = null
        for (feature in data) {
            if (feature.squad != currentSquad) {
                items.add(
                    BugReportFeatureItem.HeaderItem(
                        Feature(feature.squad, feature.squad, feature.asanaInboxId),
                    ),
                )
                currentSquad = feature.squad
            }
            items.add(BugReportFeatureItem.FeatureItem(feature))
        }
        submitList(items)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is BugReportFeatureItem.FeatureItem -> ITEM_TYPE_FEATURE
        is BugReportFeatureItem.HeaderItem -> ITEM_TYPE_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ITEM_TYPE_FEATURE -> BugReportFeatureViewHolder(
                inflater.inflate(R.layout.widget_bug_report_feature_list_item, parent, false),
                onClickListener,
            )

            ITEM_TYPE_HEADER -> BugReportFeatureHeaderViewHolder(
                inflater.inflate(R.layout.widget_bug_report_feature_header_list_item, parent, false),
                onClickListener,
            )

            else -> error("Unknown view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BugReportFeatureItem.FeatureItem ->
                (holder as BugReportFeatureViewHolder).bind(item.feature)

            is BugReportFeatureItem.HeaderItem ->
                (holder as BugReportFeatureHeaderViewHolder).bind(position == 0, item.header)
        }
    }

    companion object {
        private const val ITEM_TYPE_FEATURE = 0
        private const val ITEM_TYPE_HEADER = 1

        private val DIFFER = object : DiffUtil.ItemCallback<BugReportFeatureItem>() {
            override fun areItemsTheSame(
                oldItem: BugReportFeatureItem,
                newItem: BugReportFeatureItem,
            ): Boolean = when {
                oldItem is BugReportFeatureItem.FeatureItem && newItem is BugReportFeatureItem.FeatureItem ->
                    oldItem.feature.name == newItem.feature.name &&
                        oldItem.feature.squad == newItem.feature.squad

                oldItem is BugReportFeatureItem.HeaderItem && newItem is BugReportFeatureItem.HeaderItem ->
                    oldItem.header.squad == newItem.header.squad

                else -> false
            }

            override fun areContentsTheSame(
                oldItem: BugReportFeatureItem,
                newItem: BugReportFeatureItem,
            ): Boolean = oldItem == newItem
        }
    }
}
