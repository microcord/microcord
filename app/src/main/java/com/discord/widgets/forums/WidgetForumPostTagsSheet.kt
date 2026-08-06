package com.discord.widgets.forums

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton

/**
 * The tag picker, shared by the composer ("Select Tags") and an existing post ("Edit Tags").
 *
 * A post carries at most [MAX_POST_TAGS] tags, and a moderated tag can only be applied by someone
 * who can manage threads — it is hidden rather than shown disabled, since a member cannot act on it.
 * An existing post is saved here; a draft has nowhere to save to yet, so its choice is reported back
 * to the composer instead.
 */
class WidgetForumPostTagsSheet : AppBottomSheet() {
    override fun getContentViewResId() = R.layout.widget_forum_post_tags_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val parentChannelId = args.getLong(ARG_PARENT_CHANNEL_ID)
        val threadId = args.getLong(ARG_THREAD_ID).takeIf { it != 0L }
        val canManage = args.getBoolean(ARG_CAN_MANAGE)
        val selected = (args.getLongArray(ARG_SELECTED) ?: LongArray(0)).toMutableList()

        view.findViewById<TextView>(R.id.post_tags_title)
            .setText(if (threadId != null) R.string.forum_tag_post_edit else R.string.forum_tag_post_select)

        val tags = StoreStream.getChannels().getChannel(parentChannelId)?.availableTags.orEmpty()
            .filter { canManage || !it.moderated }
        val rows = view.findViewById<FlexboxLayout>(R.id.post_tags_rows)
        val pills = tags.map { tag ->
            ForumPostTagView(requireContext()).apply {
                configureTag(PostTagData.fromTag(tag))
                rows.addView(
                    this,
                    FlexboxLayout.LayoutParams(
                        FlexboxLayout.LayoutParams.WRAP_CONTENT,
                        FlexboxLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(dpToPixels(6), dpToPixels(6), dpToPixels(6), dpToPixels(6)) },
                )
            } to tag
        }

        fun render() {
            val atMax = selected.size >= MAX_POST_TAGS
            pills.forEach { (pill, tag) ->
                val isSelected = tag.id in selected
                pill.isSelected = isSelected
                // At the cap the remaining tags stop responding rather than reporting a failure.
                val enabled = canManage && (isSelected || !atMax)
                pill.isEnabled = enabled
                pill.alpha = if (enabled) 1f else 0.6f
            }
        }

        pills.forEach { (pill, tag) ->
            pill.setOnClickListener {
                if (!pill.isEnabled) return@setOnClickListener
                // Order matters: the reference shows tags in the order they were picked.
                if (!selected.remove(tag.id)) selected.add(tag.id)
                render()
            }
        }
        render()

        view.findViewById<MaterialButton>(R.id.post_tags_save).setOnClickListener {
            if (threadId != null) {
                // An archived post has to come back first, or the tag change is refused.
                val archived = StoreStream.getChannels().getChannel(threadId)?.threadMetadata?.archived == true
                RestAPI.api.editThread(
                    threadId,
                    RestAPIParams.ThreadSettings(archived = false.takeIf { archived }, appliedTags = selected),
                )
                    .restSubscribeOn()
                    .appSubscribe(WidgetForumPostTagsSheet::class.java, context = context) { }
            } else {
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_TAGS to selected.toLongArray()))
            }
            dismiss()
        }
    }

    companion object {
        const val MAX_POST_TAGS = 5
        internal const val RESULT_KEY = "forum_post_tags_result"
        internal const val RESULT_TAGS = "tags"
        private const val ARG_PARENT_CHANNEL_ID = "parent_channel_id"
        private const val ARG_THREAD_ID = "thread_id"
        private const val ARG_SELECTED = "selected"
        private const val ARG_CAN_MANAGE = "can_manage"

        /** Only the composer needs this; an existing post's tags are saved by the sheet itself. */
        fun registerForResult(fragment: Fragment, onPicked: (List<Long>) -> Unit) {
            fragment.setFragmentResultListener(RESULT_KEY) { _, bundle ->
                onPicked((bundle.getLongArray(RESULT_TAGS) ?: LongArray(0)).toList())
            }
        }

        fun show(
            fragmentManager: FragmentManager,
            parentChannelId: Long,
            selected: List<Long>,
            threadId: Long? = null,
            canManageThreads: Boolean,
        ) {
            WidgetForumPostTagsSheet().apply {
                arguments = bundleOf(
                    ARG_PARENT_CHANNEL_ID to parentChannelId,
                    ARG_SELECTED to selected.toLongArray(),
                    ARG_THREAD_ID to (threadId ?: 0L),
                    ARG_CAN_MANAGE to canManageThreads,
                )
            }.show(fragmentManager, WidgetForumPostTagsSheet::class.java.name)
        }
    }
}
