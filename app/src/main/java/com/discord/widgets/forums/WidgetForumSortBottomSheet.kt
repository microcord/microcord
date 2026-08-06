package com.discord.widgets.forums

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.isGuildMediaChannel
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreStream
import com.discord.views.CheckedSetting

/**
 * "Sort & View": how the viewer wants this forum's posts ordered, laid out and tag-matched.
 *
 * The layout group is hidden for a media channel, which is always a gallery, and tag matching is
 * hidden when the channel has no tags to match on.
 */
class WidgetForumSortBottomSheet : AppBottomSheet() {
    override fun getContentViewResId() = R.layout.widget_forum_sort_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val channelId = requireArguments().getLong(ARG_CHANNEL_ID)
        val channel = StoreStream.getChannels().getChannel(channelId)

        val sortActivity = view.findViewById<CheckedSetting>(R.id.forum_sort_activity)
        val sortCreation = view.findViewById<CheckedSetting>(R.id.forum_sort_creation)
        val layoutList = view.findViewById<CheckedSetting>(R.id.forum_layout_list)
        val layoutGallery = view.findViewById<CheckedSetting>(R.id.forum_layout_gallery)
        val tagSome = view.findViewById<CheckedSetting>(R.id.forum_tag_match_some)
        val tagAll = view.findViewById<CheckedSetting>(R.id.forum_tag_match_all)

        val showLayout = channel?.isGuildMediaChannel() != true
        listOf(view.findViewById<TextView>(R.id.forum_layout_header), layoutList, layoutGallery)
            .forEach { it.visibility = if (showLayout) View.VISIBLE else View.GONE }
        val showTagMatching = !channel?.availableTags.isNullOrEmpty()
        listOf(view.findViewById<TextView>(R.id.forum_tag_matching_header), tagSome, tagAll)
            .forEach { it.visibility = if (showTagMatching) View.VISIBLE else View.GONE }

        fun render() {
            val sort = ForumUtils.getSortOrder(channelId, channel)
            sortActivity.isChecked = sort == ForumSortOrder.LATEST_ACTIVITY
            sortCreation.isChecked = sort == ForumSortOrder.CREATION_DATE
            val layout = ForumUtils.getLayout(channelId, channel)
            layoutList.isChecked = layout == ForumLayout.LIST
            layoutGallery.isChecked = layout == ForumLayout.GALLERY
            val tagSetting = ForumUtils.getTagSetting(channelId, channel)
            tagSome.isChecked = tagSetting == ForumTagSetting.MATCH_SOME
            tagAll.isChecked = tagSetting == ForumTagSetting.MATCH_ALL
        }

        // The sheet stays open so several settings can be changed in one visit, as the reference's
        // does; each choice is published immediately so the list behind it follows along.
        fun apply(change: () -> Unit) {
            change()
            render()
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(ARG_CHANNEL_ID to channelId))
        }

        render()
        sortActivity.setContainerClickListener {
            apply { ForumUtils.setSortOrder(channelId, ForumSortOrder.LATEST_ACTIVITY) }
        }
        sortCreation.setContainerClickListener {
            apply { ForumUtils.setSortOrder(channelId, ForumSortOrder.CREATION_DATE) }
        }
        layoutList.setContainerClickListener { apply { ForumUtils.setLayout(channelId, ForumLayout.LIST) } }
        layoutGallery.setContainerClickListener { apply { ForumUtils.setLayout(channelId, ForumLayout.GALLERY) } }
        tagSome.setContainerClickListener {
            apply { ForumUtils.setTagSetting(channelId, ForumTagSetting.MATCH_SOME) }
        }
        tagAll.setContainerClickListener {
            apply { ForumUtils.setTagSetting(channelId, ForumTagSetting.MATCH_ALL) }
        }
        view.findViewById<TextView>(R.id.forum_display_reset).setOnClickListener {
            apply { ForumUtils.resetDisplaySettings(channelId) }
        }
    }

    companion object {
        internal const val ARG_CHANNEL_ID = "channel_id"
        internal const val RESULT_KEY = "forum_sort_result"

        fun show(fragmentManager: FragmentManager, channelId: Long) {
            WidgetForumSortBottomSheet().apply {
                arguments = bundleOf(ARG_CHANNEL_ID to channelId)
            }.show(fragmentManager, WidgetForumSortBottomSheet::class.java.name)
        }
    }
}
