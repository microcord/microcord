package com.discord.widgets.guilds.invite

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.TextViewCompat
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.guild.GuildFeature
import com.discord.models.domain.ModelInvite
import com.discord.models.user.CoreUser
import com.discord.views.GuildView
import com.discord.views.guilds.ServerMemberCount
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton

class WidgetInviteInfo @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    data class Failure(val code: Int? = null, val guildLimit: Int = 100)

    private val accept: MaterialButton
    private val avatar: GuildView
    private val smallAvatar: SimpleDraweeView
    private val memberCount: ServerMemberCount
    private val message: TextView
    private val title: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_guild_invite_info_view, this, true)
        accept = requireNotNull(findViewById(R.id.guild_invite_accept))
        avatar = requireNotNull(findViewById(R.id.invite_avatar))
        smallAvatar = requireNotNull(findViewById(R.id.invite_avatar_small))
        memberCount = requireNotNull(findViewById(R.id.invite_member_container_layout))
        message = requireNotNull(findViewById(R.id.invite_message))
        title = requireNotNull(findViewById(R.id.invite_title))
        setBackgroundResource(R.drawable.bg_guild_scheduled_event_list_item_primary)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
    }

    fun configureUI(model: ModelInvite, onAcceptClick: () -> Unit) {
        accept.visibility = View.VISIBLE
        accept.setOnClickListener { onAcceptClick() }
        message.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        when {
            model.guild != null -> configureForGuild(model)
            model.channel != null -> configureForGdm(model)
            model.getInviter() != null -> configureForDirectFriend(model)
        }
    }

    fun configureUIFailure(failure: Failure? = null) {
        title.setText(R.string.instant_invite_expired)
        message.setText(R.string.invite_button_expired)
        accept.visibility = View.GONE
        if (failure?.code == 30001) {
            title.text = context.getString(R.string.too_many_user_guilds_alert_description)
                .replace("{quantity}", failure.guildLimit.toString())
            message.text = context.getString(R.string.too_many_user_guilds_description)
                .replace("{quantity}", failure.guildLimit.toString())
            message.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_textsize_large))
        }
        message.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        memberCount.visibility = View.GONE
        avatar.configure(null, null)
        val image = requireNotNull<SimpleDraweeView>(avatar.findViewById(R.id.guild_avatar))
        image.setActualImageResource(R.drawable.img_poop_dark)
        requireNotNull<TextView>(avatar.findViewById(R.id.guild_text)).visibility = View.GONE
        smallAvatar.visibility = View.GONE
    }

    private fun configureForDirectFriend(model: ModelInvite) {
        val inviter = model.getInviter()
        val username = inviter?.username.orEmpty()
        avatar.configure(userAvatarUrl(inviter?.let(::CoreUser)), null)
        smallAvatar.visibility = View.GONE
        memberCount.visibility = View.GONE
        title.text = markedText(R.string.instant_invite_you_have_been_invited_to_chat, username)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        val tag = "$username#${inviter?.discriminator.orEmpty()}"
        message.text = markedText(R.string.instant_invite_direct_friend_description, tag)
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    private fun configureForGdm(model: ModelInvite) {
        val channel = model.channel ?: return
        val inviter = model.getInviter()
        smallAvatar.setImageURI(userAvatarUrl(inviter?.let(::CoreUser)))
        val named = !channel.icon.isNullOrEmpty()
        avatar.configure(groupDmIconUrl(channel), null)
        smallAvatar.visibility = if (named) View.VISIBLE else View.GONE
        val channelName = channel.name.orEmpty().ifBlank { inviter?.username.orEmpty() }
        message.text = bold(channelName)
        title.text = if (channel.name.isNullOrEmpty()) {
            context.getText(R.string.instant_invite_you_have_been_invited_to_join_group_dm)
        } else {
            markedText(R.string.auth_message_invited_by, inviter?.username.orEmpty())
        }
        memberCount.visibility = View.VISIBLE
        memberCount.setOnline(null)
        memberCount.setMembers(model.getApproximateMemberCount())
    }

    private fun configureForGuild(model: ModelInvite) {
        val guild = model.guild ?: return
        avatar.configure(guild.icon?.let { "https://cdn.discordapp.com/icons/${guild.id}/$it.webp?size=128" }, initials(guild.name))
        val showInviter = model.getInviter() != null && model.getApproximateMemberCount() < 200
        smallAvatar.visibility = if (showInviter) View.VISIBLE else View.GONE
        if (showInviter) smallAvatar.setImageURI(userAvatarUrl(model.getInviter()?.let(::CoreUser)))
        memberCount.visibility = View.VISIBLE
        memberCount.setMembers(if (showInviter) null else model.getApproximateMemberCount())
        memberCount.setOnline(if (showInviter) null else model.getApproximatePresenceCount())
        message.text = bold(guild.name)
        when {
            GuildFeature.VERIFIED in guild.features -> TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
                message, R.drawable.ic_verified_badge, 0, 0, 0,
            )
            GuildFeature.PARTNERED in guild.features -> TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
                message, R.drawable.ic_partnered_badge, 0, 0, 0,
            )
        }
        title.text = model.getInviter()?.username?.let {
            markedText(R.string.auth_message_invited_by, it)
        } ?: context.getText(R.string.instant_invite_you_have_been_invited_to_join)
    }

    private fun markedText(resource: Int, value: String): CharSequence {
        val raw = context.getString(resource)
        val marker = when {
            "!!{username}!!" in raw -> "!!{username}!!"
            "{username}" in raw -> "{username}"
            else -> return raw
        }
        val text = raw.replace(marker, value).replace("**", "")
        return bold(text, value)
    }

    private fun bold(text: String, value: String = text): CharSequence = SpannableString(text).apply {
        val start = text.indexOf(value)
        if (start >= 0 && value.isNotEmpty()) {
            setSpan(StyleSpan(Typeface.BOLD), start, start + value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun userAvatarUrl(user: CoreUser?): String? = user?.avatar?.let {
        val extension = if (it.startsWith("a_")) "gif" else "webp"
        "https://cdn.discordapp.com/avatars/${user.id}/$it.$extension?size=128"
    }

    private fun groupDmIconUrl(channel: Channel): String = channel.icon?.let {
        "https://cdn.discordapp.com/channel-icons/${channel.id}/$it.webp?size=128"
    } ?: "asset:///images/group_dm_icon_${Math.floorMod(channel.id, 8).toInt()}.png"

    private fun initials(name: String) = name.replace(Regex("'s "), " ")
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .joinToString("") { it.take(1) }
}
