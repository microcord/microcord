package com.discord.widgets.chat.list.adapter

import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.ForumTag
import com.discord.i18n.Hook
import com.discord.i18n.I18nHooks
import com.discord.models.member.GuildMember
import com.discord.primitives.ForumTagId
import com.discord.utilities.dimen.dpToPixels
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.StartOfChatEntry
import com.discord.widgets.forums.ForumPostTagView
import com.discord.widgets.forums.PostTagData
import com.google.android.flexbox.FlexboxLayout

/**
 * Head of a guild channel, thread or forum post — the "Welcome to #general!" panel at the very top
 * of the history.
 *
 * One layout serves three quite different panels, so each branch is responsible for restoring every
 * view it does not use: a recycled forum-post header would otherwise keep the thread's
 * "Started by …" subheader.
 */
class WidgetChatListAdapterItemStart(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_start, adapter) {

    private val editChannel: TextView =
        itemView.findViewById(R.id.chat_list_adapter_item_edit_channel)
    private val header: TextView = itemView.findViewById(R.id.chat_list_adapter_item_header)
    private val subheader1: TextView = itemView.findViewById(R.id.chat_list_adapter_item_subheader1)
    private val subheader2: TextView = itemView.findViewById(R.id.chat_list_adapter_item_subheader2)
    private val tags: FlexboxLayout = itemView.findViewById(R.id.chat_list_adapter_tags)
    private val headerIcon: ImageView =
        itemView.findViewById(R.id.chat_list_adapter_thread_header_icon)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as StartOfChatEntry
        when {
            entry.isGuildForumPost && entry.canAccessRedesignedForumChannels ->
                configureForumPost(entry.channelName, entry.appliedTags, entry.availableTags)
            entry.isThread -> configureThread(entry)
            else -> configureChannel(entry)
        }
    }

    private fun configureChannel(entry: StartOfChatEntry) {
        header.text = I18nHooks.format(
            header.context,
            R.string.android_welcome_message_title_channel,
            mapOf("channelName" to entry.channelName),
        )
        subheader1.visibility = View.GONE
        // Divergence from the reference, which never resets the tag strip here: without this a
        // holder recycled from a forum post keeps that post's tags above the channel header.
        tags.visibility = View.GONE

        // Text-in-voice channels get the chat bubble; an ordinary text channel has no header icon.
        if (entry.isTextInVoice) {
            headerIcon.visibility = View.VISIBLE
            headerIcon.setImageResource(R.drawable.ic_chat_message_white_24dp)
        } else {
            headerIcon.visibility = View.GONE
        }

        val subtitle = if (entry.canReadMessageHistory) {
            R.string.android_welcome_message_subtitle_channel
        } else {
            R.string.beginning_channel_no_history
        }
        subheader2.visibility = View.VISIBLE
        subheader2.text = I18nHooks.format(
            subheader2.context,
            subtitle,
            mapOf("channelName" to entry.channelName),
        )

        // TODO(parity): the reference opens WidgetTextChannelSettings here; that screen has not
        //  been reconstructed yet, so the row is shown by the same rule but does nothing.
        editChannel.setOnClickListener(null)
        editChannel.setText(R.string.edit_channel)
        editChannel.visibility = if (entry.canManageChannel) View.VISIBLE else View.GONE
    }

    private fun configureThread(entry: StartOfChatEntry) {
        val context = header.context
        header.text = entry.channelName
        subheader1.visibility = View.VISIBLE
        subheader2.visibility = View.VISIBLE
        headerIcon.visibility = View.VISIBLE
        tags.visibility = View.GONE
        headerIcon.setImageResource(R.drawable.ic_thread)

        subheader1.text = I18nHooks.format(
            context,
            R.string.thread_started_by,
            hooks = mapOf(
                "usernameHook" to Hook(
                    text = entry.threadCreatorName,
                    spans = listOf(
                        StyleSpan(Typeface.BOLD),
                        ForegroundColorSpan(authorTextColor(entry.threadCreatorMember)),
                    ),
                ),
            ),
        )

        subheader2.text = if (entry.canReadMessageHistory) {
            I18nHooks.format(
                context,
                R.string.beginning_thread_archive_description,
                hooks = mapOf(
                    "autoArchiveDurationHook" to Hook(
                        text = autoArchiveDurationName(entry.threadAutoArchiveDuration ?: 0),
                        spans = listOf(
                            ForegroundColorSpan(
                                ThemedColor.resolve(context, R.attr.colorHeaderPrimary),
                            ),
                        ),
                    ),
                ),
            )
        } else {
            I18nHooks.format(
                context,
                R.string.beginning_channel_no_history,
                mapOf("channelName" to entry.channelName),
            )
        }

        // TODO(parity): the reference opens WidgetThreadSettings here; not yet reconstructed.
        editChannel.setOnClickListener(null)
        editChannel.setText(R.string.edit_thread)
        editChannel.visibility = if (entry.canManageThread) View.VISIBLE else View.GONE
    }

    private fun configureForumPost(
        channelName: String,
        appliedTags: List<ForumTagId>?,
        availableTags: List<ForumTag>?,
    ) {
        subheader1.visibility = View.GONE
        subheader2.visibility = View.GONE
        editChannel.visibility = View.GONE
        headerIcon.visibility = View.VISIBLE
        header.text = channelName
        headerIcon.setImageResource(R.drawable.ic_channel_forum_post)

        val applied = availableTags.orEmpty().filter { appliedTags?.contains(it.id) == true }
        tags.visibility = if (applied.isNotEmpty()) View.VISIBLE else View.GONE

        // Tag chips are recycled inside the row: surplus chips are hidden rather than removed, so a
        // post with fewer tags than the previous one does not force a re-inflate.
        val recyclableCount = tags.childCount
        for (index in applied.size until recyclableCount) {
            tags.getChildAt(index).visibility = View.GONE
        }
        applied.forEachIndexed { index, tag ->
            val tagView = if (index < recyclableCount) {
                tags.getChildAt(index) as ForumPostTagView
            } else {
                ForumPostTagView(tags.context).also { tags.addView(it) }
            }
            tagView.visibility = View.VISIBLE
            (tagView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.setMargins(params.leftMargin, params.topMargin, dpToPixels(TAG_SPACING_DP), dpToPixels(TAG_SPACING_DP))
                tagView.layoutParams = params
            }
            tagView.configureTag(PostTagData.fromTag(tag, animated = false))
        }
    }

    private fun authorTextColor(member: GuildMember?): Int = GuildMember.getColor(
        member,
        ThemedColor.resolve(itemView.context, R.attr.colorHeaderPrimary),
    )

    /**
     * Reference: `ThreadUtils.autoArchiveDurationName`. That utility has not been reconstructed
     * yet, so the four durations Discord offers are mapped here; anything else renders blank rather
     * than showing a raw minute count.
     */
    private fun autoArchiveDurationName(durationMinutes: Int): String = when (durationMinutes) {
        ONE_HOUR_MINUTES -> itemView.context.getString(R.string.auto_archive_duration_1_hour)
        ONE_DAY_MINUTES -> itemView.context.getString(R.string.auto_archive_duration_24_hours)
        THREE_DAYS_MINUTES -> itemView.context.getString(R.string.auto_archive_duration_3_days)
        ONE_WEEK_MINUTES -> itemView.context.getString(R.string.auto_archive_duration_1_week)
        else -> ""
    }


    private companion object {
        private const val TAG_SPACING_DP = 6

        private const val ONE_HOUR_MINUTES = 60
        private const val ONE_DAY_MINUTES = 1_440
        private const val THREE_DAYS_MINUTES = 4_320
        private const val ONE_WEEK_MINUTES = 10_080
    }
}
