package com.discord.widgets.auth

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.user.User
import com.discord.models.domain.ModelGuildTemplate
import com.discord.models.domain.ModelInvite
import com.facebook.drawee.view.SimpleDraweeView

class AuthInviteInfoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {
    private val guildAvatar: SimpleDraweeView
    private val guildName: TextView
    private val invitedText: TextView
    private val templateIcon: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_auth_invite_info, this, true)
        guildAvatar = requireNotNull(findViewById(R.id.auth_invite_info_guild_avatar))
        guildName = requireNotNull(findViewById(R.id.auth_invite_info_guild_name))
        invitedText = requireNotNull(findViewById(R.id.auth_invite_info_invited_text))
        templateIcon = requireNotNull(findViewById(R.id.auth_invite_info_template_icon))
    }

    fun configureGuildTemplate(guildTemplate: ModelGuildTemplate) {
        guildAvatar.visibility = View.GONE
        templateIcon.visibility = View.VISIBLE
        invitedText.setText(R.string.guild_template_create_discord)
        guildName.text = guildTemplate.name
    }

    fun configureInvite(invite: ModelInvite) {
        guildAvatar.visibility = View.VISIBLE
        templateIcon.visibility = View.GONE

        when {
            invite.guild != null && invite.channel?.type in GUILD_CHANNEL_TYPES -> configureGuildInvite(invite)
            invite.channel?.type == Channel.GROUP_DM -> configureGroupInvite(invite)
            invite.channel == null -> configureDirectInvite(invite)
        }
    }

    private fun configureGuildInvite(invite: ModelInvite) {
        val guild = requireNotNull(invite.guild)
        guildAvatar.setImageURI(guild.icon?.let {
            "https://cdn.discordapp.com/icons/${guild.id}/$it.webp?size=128"
        } ?: DEFAULT_ICON)
        invitedText.text = if (invite.getApproximateMemberCount() < 200 && !invite.getInviter()?.username.isNullOrBlank()) {
            usernameText(R.string.auth_message_invited_by, invite.getInviter()!!.username)
        } else {
            context.getText(R.string.instant_invite_you_have_been_invited_to_join)
        }
        guildName.text = guild.name
    }

    private fun configureGroupInvite(invite: ModelInvite) {
        setInviterAvatar(invite.getInviter())
        invitedText.text = if (invite.channel?.name.isNullOrBlank() || invite.getInviter()?.username.isNullOrBlank()) {
            context.getText(R.string.instant_invite_you_have_been_invited_to_join_group_dm)
        } else {
            usernameText(R.string.auth_message_invited_by, invite.getInviter()!!.username)
        }
        guildName.text = invite.channel?.name.orEmpty().ifBlank { invite.getInviter()?.username.orEmpty() }
    }

    private fun configureDirectInvite(invite: ModelInvite) {
        setInviterAvatar(invite.getInviter())
        invitedText.text = context.getText(R.string.instant_invite_you_have_been_invited_to_chat_with)
        guildName.text = invite.getInviter()?.let { "${it.username}#${it.discriminator}" }.orEmpty()
    }

    private fun setInviterAvatar(user: User?) {
        val hash = user?.avatar?.value
        guildAvatar.setImageURI(if (user != null && hash != null) {
            val extension = if (hash.startsWith("a_")) "gif" else "webp"
            "https://cdn.discordapp.com/avatars/${user.id}/$hash.$extension?size=128"
        } else DEFAULT_ICON)
    }

    private fun usernameText(resourceId: Int, username: String): CharSequence {
        val text = context.getString(resourceId).replace("!!{username}!!", username).replace("**", "")
        return SpannableString(text).apply {
            val start = text.indexOf(username)
            if (start >= 0) setSpan(StyleSpan(Typeface.BOLD), start, start + username.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        val GUILD_CHANNEL_TYPES = setOf(Channel.GUILD_TEXT, Channel.GUILD_VOICE, Channel.GUILD_ANNOUNCEMENT, Channel.GUILD_STAGE_VOICE)
        const val DEFAULT_ICON = "asset://asset/images/default_icon_selected.jpg"
    }
}
