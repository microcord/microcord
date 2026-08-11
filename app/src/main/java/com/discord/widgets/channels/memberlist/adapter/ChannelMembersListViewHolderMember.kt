package com.discord.widgets.channels.memberlist.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.presence.ClientStatus
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.presence.statusIcon
import com.discord.databinding.WidgetChannelMembersListItemUserBinding
import com.discord.utilities.color.ColorCompat
import com.discord.views.AvatarDecorationView
import com.discord.views.StatusView
import com.discord.views.NameplateView
import com.discord.views.UsernameView
import com.facebook.drawee.view.SimpleDraweeView

private const val ROW_EDGE_PADDING_DP = 16
private const val NAME_END_MARGIN_DP = 4

class ChannelMembersListViewHolderMember(private val binding: WidgetChannelMembersListItemUserBinding) :
    RecyclerView.ViewHolder(binding.root) {

    /**
     * Keeps the name and status clear of the artwork. Only the text moves: the crown and boost
     * badge stay pinned to the row edge so they line up across every row.
     */
    private fun reserveArtGutter(hasNameplate: Boolean) {
        val density = binding.root.resources.displayMetrics.density
        val extra = if (hasNameplate) {
            ((NameplateView.ART_GUTTER_DP - ROW_EDGE_PADDING_DP) * density).toInt()
        } else {
            0
        }
        setEndMargin(R.id.channel_members_list_item_name, (NAME_END_MARGIN_DP * density).toInt() + extra)
        setEndMargin(R.id.channel_members_list_item_game, extra)
    }

    private fun setEndMargin(viewId: Int, margin: Int) {
        val view = binding.root.findViewById<View>(viewId)
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        if (params.marginEnd != margin) {
            params.marginEnd = margin
            view.layoutParams = params
        }
    }

    fun bind(data: ChannelMembersListAdapter.Item.Member, onClicked: () -> Unit, onLongClicked: (() -> Unit)?) {
        binding.root.setOnClickListener { onClicked() }
        binding.root.setOnLongClickListener(onLongClicked?.let { callback ->
            View.OnLongClickListener { callback(); true }
        })
        binding.root.findViewById<NameplateView>(R.id.channel_members_list_item_nameplate).apply {
            bind(data.nameplate, NameplateView.Placement.MEMBER_LIST, selected = false)
            binding.root.setOnHoverListener { _, event ->
                setNameplateHovered(
                    event.action == MotionEvent.ACTION_HOVER_ENTER || event.action == MotionEvent.ACTION_HOVER_MOVE,
                )
                false
            }
        }
        reserveArtGutter(data.nameplate != null)
        binding.root.findViewById<SimpleDraweeView>(R.id.channel_members_list_item_avatar).setImageURI(data.avatarUrl)
        binding.root.findViewById<AvatarDecorationView>(R.id.channel_members_list_item_avatar_decoration)
            .bind(data.avatarDecoration)
        binding.root.findViewById<UsernameView>(R.id.channel_members_list_item_name).apply {
            configureUsername(data.name)
            configureGuildTag(data.primaryGuild)
            configureTag(data.isBot, data.tagText ?: R.string.bot_tag_bot, data.tagVerified)
            setUsernameColor(data.color?.takeUnless { it == Color.BLACK || it == 0 }
                ?: ColorCompat.getThemedColor(context, R.attr.primary_000))
        }
        binding.root.findViewById<StatusView>(R.id.channel_members_list_item_presence).apply {
            setPresence(data.presence)
            visibility = if (data.presence?.status == null ||
                data.presence.status == ClientStatus.OFFLINE || data.presence.status == ClientStatus.INVISIBLE
            ) View.GONE else View.VISIBLE
        }
        val statusIcon = PresenceUtils.setStatusLine(
            data.presence,
            data.isApplicationStreaming,
            binding.root.findViewById(R.id.channel_members_list_item_game),
        )
        binding.root.findViewById<ImageView>(R.id.channel_members_list_item_rich_presence_iv).apply {
            val icon = statusIcon
            if (icon == null) {
                visibility = View.GONE
            } else {
                setImageResource(icon)
                ImageViewCompat.setImageTintList(
                    this,
                    if (data.isApplicationStreaming) {
                        null
                    } else {
                        ColorStateList.valueOf(
                            ColorCompat.getThemedColor(context, R.attr.colorTextPositive),
                        )
                    },
                )
                visibility = View.VISIBLE
            }
        }
        binding.root.findViewById<ImageView>(R.id.channel_members_list_item_group_owner_indicator).visibility =
            if (data.showOwnerIndicator) View.VISIBLE else View.GONE
        binding.root.findViewById<ImageView>(R.id.channel_members_list_item_boosted_indicator).visibility =
            if (data.premiumSince != null) View.VISIBLE else View.GONE
    }
}
