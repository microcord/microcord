package com.discord.widgets.channels.memberlist.adapter

import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.activity.ActivityType
import com.discord.databinding.WidgetChannelMembersListItemUserBinding
import com.discord.utilities.color.ColorCompat
import com.discord.views.StatusView
import com.discord.views.NameplateView
import com.discord.views.UsernameView
import com.facebook.drawee.view.SimpleDraweeView

class ChannelMembersListViewHolderMember(private val binding: WidgetChannelMembersListItemUserBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(data: ChannelMembersListAdapter.Item.Member, onClicked: () -> Unit, onLongClicked: (() -> Unit)?) {
        binding.root.setOnClickListener { onClicked() }
        binding.root.setOnLongClickListener(onLongClicked?.let { callback ->
            View.OnLongClickListener { callback(); true }
        })
        binding.root.findViewById<NameplateView>(R.id.channel_members_list_item_nameplate).apply {
            bind(
                data.nameplate,
                NameplateView.Placement.MEMBER_LIST,
                selected = false,
                binding.root.findViewById(R.id.channel_members_list_item_content_end),
            )
            binding.root.setOnHoverListener { _, event ->
                setNameplateHovered(
                    event.action == MotionEvent.ACTION_HOVER_ENTER || event.action == MotionEvent.ACTION_HOVER_MOVE,
                )
                false
            }
        }
        binding.root.findViewById<SimpleDraweeView>(R.id.channel_members_list_item_avatar).setImageURI(data.avatarUrl)
        binding.root.findViewById<UsernameView>(R.id.channel_members_list_item_name).apply {
            configureUsername(data.name)
            configureTag(data.isBot, data.tagText ?: R.string.bot_tag_bot, data.tagVerified)
            setUsernameColor(data.color?.takeUnless { it == Color.BLACK || it == 0 }
                ?: ColorCompat.getThemedColor(context, R.attr.primary_000))
        }
        binding.root.findViewById<StatusView>(R.id.channel_members_list_item_presence).apply {
            setPresence(data.presence)
            visibility = if (data.presence == null) View.GONE else View.VISIBLE
        }
        val activity = data.presence?.activities?.firstOrNull { it.type != ActivityType.CUSTOM_STATUS }
            ?: data.presence?.activities?.firstOrNull()
        binding.root.findViewById<TextView>(R.id.channel_members_list_item_game).apply {
            text = activity?.details ?: activity?.state ?: activity?.name
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        binding.root.findViewById<ImageView>(R.id.channel_members_list_item_rich_presence_iv).apply {
            setImageResource(if (data.isApplicationStreaming) R.drawable.ic_status_streaming_16dp else R.drawable.ic_rich_presence_grey_16dp)
            visibility = if (data.isApplicationStreaming || activity != null) View.VISIBLE else View.GONE
        }
        binding.root.findViewById<ImageView>(R.id.channel_members_list_item_group_owner_indicator).visibility =
            if (data.showOwnerIndicator) View.VISIBLE else View.GONE
        binding.root.findViewById<ImageView>(R.id.channel_members_list_item_boosted_indicator).visibility =
            if (data.premiumSince != null) View.VISIBLE else View.GONE
    }
}
