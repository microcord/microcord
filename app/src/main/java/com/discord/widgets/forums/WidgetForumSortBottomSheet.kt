package com.discord.widgets.forums

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.views.CheckedSetting

class WidgetForumSortBottomSheet : AppBottomSheet() {
    override fun getContentViewResId() = R.layout.widget_forum_sort_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val channelId = requireArguments().getLong(ARG_CHANNEL_ID)
        val activity = view.findViewById<CheckedSetting>(R.id.forum_sort_activity)
        val creation = view.findViewById<CheckedSetting>(R.id.forum_sort_creation)
        val creationSelected = ForumUtils.getSortOrder(channelId) == ForumSortOrder.CREATION_DATE
        activity.isChecked = !creationSelected
        creation.isChecked = creationSelected
        fun select(order: ForumSortOrder) {
            ForumUtils.setSortOrder(channelId, order)
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(ARG_CHANNEL_ID to channelId))
            dismiss()
        }
        activity.setContainerClickListener { select(ForumSortOrder.LATEST_ACTIVITY) }
        creation.setContainerClickListener { select(ForumSortOrder.CREATION_DATE) }
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
