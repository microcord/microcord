package com.discord.widgets.channels.memberlist

import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.activity.ActivityType
import com.discord.api.presence.ClientStatus
import com.discord.views.StatusView
import com.discord.views.UsernameView
import com.discord.views.user.avatarUrl
import com.discord.widgets.roles.RoleIconView
import com.facebook.drawee.view.SimpleDraweeView

internal class ChannelMembersListAdapter(
    private val onUserClicked: ((Long) -> Unit)?,
    private val onAddMemberClicked: (() -> Unit)?,
    private val onJoinLeaveThreadClicked: (() -> Unit)?,
) : ListAdapter<ChannelMemberListRow, ChannelMembersListAdapter.Holder>(DIFF) {
    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = getItem(position).stableId
    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ChannelMemberListRow.Member -> MEMBER
        is ChannelMemberListRow.Header, is ChannelMemberListRow.RoleHeader -> HEADER
        is ChannelMemberListRow.PlaceholderHeader -> PLACEHOLDER_HEADER
        is ChannelMemberListRow.PlaceholderMember -> PLACEHOLDER_MEMBER
        is ChannelMemberListRow.AddMember, is ChannelMemberListRow.JoinLeaveThread -> ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = when (viewType) {
            MEMBER -> R.layout.widget_channel_members_list_item_user
            HEADER -> R.layout.widget_channel_members_list_item_header
            PLACEHOLDER_HEADER -> R.layout.widget_channel_members_list_item_placeholder_header
            PLACEHOLDER_MEMBER -> R.layout.widget_channel_members_list_item_loading
            else -> R.layout.widget_channel_members_list_item_add_or_leave
        }
        return Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position), position)

    internal inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(row: ChannelMemberListRow, position: Int) {
            when (row) {
                is ChannelMemberListRow.Member -> bindMember(row)
                is ChannelMemberListRow.Header -> bindHeader(row)
                is ChannelMemberListRow.RoleHeader -> bindRoleHeader(row)
                is ChannelMemberListRow.PlaceholderMember -> itemView.findViewById<Guideline>(R.id.username_placeholder_end_guideline)
                    .setGuidelinePercent(PLACEHOLDER_WIDTHS[position % PLACEHOLDER_WIDTHS.size])
                is ChannelMemberListRow.AddMember -> bindAction(row.title, R.drawable.ic_add_person_16dp, onAddMemberClicked)
                is ChannelMemberListRow.JoinLeaveThread -> bindAction(
                    if (row.joined) R.string.leave_thread else R.string.join_thread,
                    if (row.joined) themedDrawable(R.attr.ic_exit_to_app) else themedDrawable(R.attr.ic_person_add),
                    onJoinLeaveThreadClicked,
                    if (row.joined) R.attr.colorTextDanger else R.attr.colorInteractiveNormal,
                )
                is ChannelMemberListRow.PlaceholderHeader -> Unit
            }
        }

        private fun bindMember(row: ChannelMemberListRow.Member) {
            val avatar = itemView.findViewById<SimpleDraweeView>(R.id.channel_members_list_item_avatar)
            val name = itemView.findViewById<UsernameView>(R.id.channel_members_list_item_name)
            val status = itemView.findViewById<StatusView>(R.id.channel_members_list_item_presence)
            val activity = row.presence?.activities?.firstOrNull { it.type != ActivityType.CUSTOM_STATUS }
                ?: row.presence?.activities?.firstOrNull()
            val activityText = itemView.findViewById<TextView>(R.id.channel_members_list_item_game)
            val richPresence = itemView.findViewById<ImageView>(R.id.channel_members_list_item_rich_presence_iv)
            val owner = itemView.findViewById<ImageView>(R.id.channel_members_list_item_group_owner_indicator)
            val boosted = itemView.findViewById<ImageView>(R.id.channel_members_list_item_boosted_indicator)

            avatar.setImageURI(avatarUrl(row.user, row.guildMember, itemView.resources.getDimensionPixelSize(R.dimen.avatar_size_standard)))
            name.configureUsername(row.displayName)
            name.configureTag(row.user.isBot, R.string.bot_tag_bot, row.user.publicFlags and VERIFIED_BOT_FLAG != 0)
            name.setUsernameColor(row.guildMember?.color?.takeUnless { it == Color.BLACK || it == 0 } ?: themedColor(R.attr.primary_000))
            status.setPresence(row.presence)
            status.visibility = if (row.presence == null) View.GONE else View.VISIBLE
            status.contentDescription = statusDescription(row.presence?.status)
            activityText.text = activity?.details ?: activity?.state ?: activity?.name
            activityText.visibility = if (activityText.text.isNullOrBlank()) View.GONE else View.VISIBLE
            richPresence.visibility = if (activity != null && activity.type != ActivityType.CUSTOM_STATUS) View.VISIBLE else View.GONE
            owner.visibility = if (row.isOwner) View.VISIBLE else View.GONE
            owner.contentDescription = itemView.context.getString(if (row.guildMember == null) R.string.group_owner else R.string.guild_owner)
            boosted.visibility = if (row.guildMember?.premiumSince != null) View.VISIBLE else View.GONE
            boosted.contentDescription = itemView.context.getString(R.string.premium_guild_user_feature_boost_crystal)
            itemView.contentDescription = buildList {
                add(row.displayName)
                if (row.user.isBot) add(itemView.context.getString(R.string.bot_tag_bot))
                if (row.isOwner) add(owner.contentDescription)
                row.presence?.let { add(statusDescription(it.status)) }
            }.joinToString(", ")
            itemView.isClickable = onUserClicked != null
            itemView.setOnClickListener(if (onUserClicked == null) null else View.OnClickListener { onUserClicked.invoke(row.user.id) })
        }

        private fun bindHeader(row: ChannelMemberListRow.Header) {
            val label = itemView.findViewById<TextView>(R.id.channel_members_list_item_header_text)
            label.text = itemView.context.getString(R.string.manticord_member_group_count, itemView.context.getString(when (row.kind) {
                ChannelMemberListRow.Header.Kind.ONLINE -> R.string.status_online
                ChannelMemberListRow.Header.Kind.OFFLINE -> R.string.status_offline
                ChannelMemberListRow.Header.Kind.MEMBERS -> R.string.members
            }), row.count)
            itemView.findViewById<RoleIconView>(R.id.channel_members_list_item_header_role_icon).visibility = View.GONE
            ViewCompat.setAccessibilityHeading(label, true)
        }

        private fun bindRoleHeader(row: ChannelMemberListRow.RoleHeader) {
            val label = itemView.findViewById<TextView>(R.id.channel_members_list_item_header_text)
            label.text = itemView.context.getString(R.string.manticord_member_group_count, row.role.name, row.count)
            itemView.findViewById<RoleIconView>(R.id.channel_members_list_item_header_role_icon).apply {
                setRoleIconPreview(row.role)
                visibility = if (row.role.icon == null && row.role.unicodeEmoji == null) View.GONE else View.VISIBLE
            }
            ViewCompat.setAccessibilityHeading(label, true)
        }

        private fun bindAction(title: Int, icon: Int, callback: (() -> Unit)?, tint: Int = R.attr.colorInteractiveNormal) {
            val text = itemView.findViewById<TextView>(R.id.channel_members_list_item_add_or_leave_title)
            text.setText(title)
            itemView.findViewById<ImageView>(R.id.channel_members_list_item_add_or_leave).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(themedColor(tint))
            }
            itemView.contentDescription = text.text
            itemView.isClickable = callback != null
            itemView.setOnClickListener(if (callback == null) null else View.OnClickListener { callback() })
        }

        private fun statusDescription(status: ClientStatus?) = itemView.context.getString(when (status) {
            ClientStatus.ONLINE -> R.string.status_online
            ClientStatus.IDLE -> R.string.status_idle
            ClientStatus.DND -> R.string.status_dnd
            else -> R.string.status_offline
        })

        private fun themedColor(attribute: Int): Int = TypedValue().let {
            itemView.context.theme.resolveAttribute(attribute, it, true)
            it.data
        }

        private fun themedDrawable(attribute: Int): Int = TypedValue().let {
            itemView.context.theme.resolveAttribute(attribute, it, true)
            it.resourceId
        }
    }

    private companion object {
        const val MEMBER = 0
        const val HEADER = 1
        const val PLACEHOLDER_HEADER = 2
        const val PLACEHOLDER_MEMBER = 3
        const val ACTION = 4
        const val VERIFIED_BOT_FLAG = 1 shl 16
        val PLACEHOLDER_WIDTHS = floatArrayOf(0.7f, 0.3f, 0.6f, 0.4f, 0.6f, 0.8f, 0.3f, 0.5f)
        val DIFF = object : DiffUtil.ItemCallback<ChannelMemberListRow>() {
            override fun areItemsTheSame(oldItem: ChannelMemberListRow, newItem: ChannelMemberListRow) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: ChannelMemberListRow, newItem: ChannelMemberListRow) = oldItem == newItem
        }
    }
}
