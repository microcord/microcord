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
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.presence.statusIcon
import com.discord.databinding.WidgetChannelMembersListItemUserBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.RoleColorMode
import com.discord.utilities.feature.RoleColors
import com.discord.utilities.displaynamestyles.DisplayNameStyleRenderer
import com.discord.views.NameplateView

private const val ROW_EDGE_PADDING_DP = 16
private const val NAME_END_MARGIN_DP = 4

class ChannelMembersListViewHolderMember(private val binding: WidgetChannelMembersListItemUserBinding) :
    RecyclerView.ViewHolder(binding.root) {

    /**
     * Held per-holder (rather than the module-wide default `AlwaysUpdateChangeDetector`) so
     * scrolling a row back into view doesn't re-issue an identical avatar request — this list
     * stays open and gets scrolled constantly while chatting.
     */
    private val avatarChangeDetector = MGImages.DistinctChangeDetector()

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
        setEndMargin(binding.channelMembersListItemName, (NAME_END_MARGIN_DP * density).toInt() + extra)
        setEndMargin(binding.channelMembersListItemGame, extra)
    }

    private fun setEndMargin(view: View, margin: Int) {
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
        binding.channelMembersListItemNameplate.apply {
            bind(data.nameplate, NameplateView.Placement.MEMBER_LIST, selected = false)
            binding.root.setOnHoverListener { _, event ->
                setNameplateHovered(
                    event.action == MotionEvent.ACTION_HOVER_ENTER || event.action == MotionEvent.ACTION_HOVER_MOVE,
                )
                false
            }
        }
        reserveArtGutter(data.nameplate != null)
        IconUtils.setIcon(
            binding.channelMembersListItemAvatar,
            data.avatarUrl,
            R.dimen.avatar_size_standard,
            changeDetector = avatarChangeDetector,
        )
        binding.channelMembersListItemAvatarDecoration.bind(data.avatarDecoration)
        binding.channelMembersListItemName.apply {
            configureUsername(data.name)
            configureGuildTag(data.primaryGuild)
            configureTag(data.isBot, data.tagText ?: R.string.bot_tag_bot, data.tagVerified)
            val roleColor = data.color?.takeUnless { it == Color.BLACK || it == 0 }
            setUsernameColor(roleColor.takeIf { RoleColors.mode() == RoleColorMode.NAMES }
                ?: ColorCompat.getThemedColor(context, R.attr.primary_000))
            // Font only: a member's role colour is what a moderator reads this list for, so the
            // reference leaves it in place rather than overpainting it with the name style.
            setDisplayNameStyles(data.displayNameStyles, DisplayNameStyleRenderer.Mode.FONT)
            setRoleDot(roleColor)
        }
        binding.channelMembersListItemPresence.apply {
            setPresence(data.presence)
            visibility = if (data.presence?.status == null ||
                data.presence.status == ClientStatus.OFFLINE || data.presence.status == ClientStatus.INVISIBLE
            ) View.GONE else View.VISIBLE
        }
        val statusIcon = PresenceUtils.setStatusLine(
            data.presence,
            data.isApplicationStreaming,
            binding.channelMembersListItemGame,
        )
        binding.channelMembersListItemRichPresenceIv.apply {
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
        binding.channelMembersListItemGroupOwnerIndicator.visibility =
            if (data.showOwnerIndicator) View.VISIBLE else View.GONE
        binding.channelMembersListItemBoostedIndicator.visibility =
            if (data.premiumSince != null) View.VISIBLE else View.GONE
    }
}
