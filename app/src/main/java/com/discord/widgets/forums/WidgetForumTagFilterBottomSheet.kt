package com.discord.widgets.forums

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreStream
import com.discord.views.CheckedSetting

/**
 * "Filter by tags": which of a forum's tags a post must carry to be listed.
 *
 * Every tag the channel offers is listed, moderated ones included — the restriction is on applying
 * a tag, not on filtering by it. Whether a post needs one or all of them is [ForumTagSetting],
 * chosen in [WidgetForumSortBottomSheet].
 */
class WidgetForumTagFilterBottomSheet : AppBottomSheet() {
    override fun getContentViewResId() = R.layout.widget_forum_tag_filter_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val channelId = requireArguments().getLong(ARG_CHANNEL_ID)
        val tags = StoreStream.getChannels().getChannel(channelId)?.availableTags.orEmpty()
        val rows = view.findViewById<LinearLayout>(R.id.tag_filter_rows)
        val subtitle = view.findViewById<TextView>(R.id.tag_filter_subtitle)
        val selected = ForumUtils.getTagFilter(channelId).toMutableSet()
        val inflater = android.view.LayoutInflater.from(requireContext())

        fun publish() {
            ForumUtils.setTagFilter(channelId, selected)
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(ARG_CHANNEL_ID to channelId))
        }

        fun renderSubtitle() {
            subtitle.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            subtitle.text = getString(R.string.forum_tag_filter_subheader)
                .replace("{count}", selected.size.toString())
        }

        val checks = tags.map { tag ->
            val id = tag.id.toString()
            val row = inflater.inflate(R.layout.forum_tag_filter_row, rows, false) as CheckedSetting
            row.setText(tag.name)
            row.isChecked = id in selected
            row.setContainerClickListener {
                if (!selected.add(id)) selected.remove(id)
                row.isChecked = id in selected
                renderSubtitle()
                publish()
            }
            rows.addView(row)
            row
        }

        renderSubtitle()
        view.findViewById<TextView>(R.id.tag_filter_clear).setOnClickListener {
            selected.clear()
            checks.forEach { it.isChecked = false }
            renderSubtitle()
            publish()
        }
    }

    companion object {
        internal const val ARG_CHANNEL_ID = "channel_id"
        internal const val RESULT_KEY = "forum_tag_filter_result"

        fun show(fragmentManager: FragmentManager, channelId: Long) {
            WidgetForumTagFilterBottomSheet().apply {
                arguments = bundleOf(ARG_CHANNEL_ID to channelId)
            }.show(fragmentManager, WidgetForumTagFilterBottomSheet::class.java.name)
        }
    }
}
