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
import com.discord.databinding.WidgetGuildInviteInfoViewBinding
import com.discord.i18n.setI18nText
import com.discord.models.domain.ModelInvite
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.guilds.computeShortName
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.user.UserUtils.isPremiumTier2
import com.discord.views.VitoImageView

class WidgetInviteInfo @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    /**
     * Why the invite could not be shown. Only `30001` (guild limit reached) gets bespoke copy;
     * every other code — expired, revoked, unknown — falls through to the generic "expired" state,
     * which is what the reference does too.
     */
    data class Failure(val code: Int? = null)

    private val binding = WidgetGuildInviteInfoViewBinding.inflate(LayoutInflater.from(context), this)

    init {
        setBackgroundResource(R.drawable.bg_guild_scheduled_event_list_item_primary)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
    }

    fun configureUI(model: ModelInvite, onAcceptClick: () -> Unit) {
        binding.guildInviteAccept.visibility = View.VISIBLE
        binding.guildInviteAccept.setText(
            if (model.requiresApplication()) R.string.invite_button_apply_to_join else R.string.instant_invite_accept,
        )
        binding.guildInviteAccept.setOnClickListener { onAcceptClick() }
        binding.inviteMessage.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        when {
            model.guild != null -> configureForGuild(model)
            model.channel != null -> configureForGdm(model)
            model.getInviter() != null -> configureForDirectFriend(model)
        }
    }

    fun configureUIFailure(failure: Failure? = null) {
        binding.inviteTitle.setText(R.string.instant_invite_expired)
        binding.inviteMessage.setText(R.string.invite_button_expired)
        binding.guildInviteAccept.visibility = View.GONE
        if (failure?.code == ERROR_MAX_GUILDS) {
            // The limit is a property of the *viewer*, not of the error payload: Nitro doubles it.
            val guildLimit = if (StoreStream.getUsers().getMe().isPremiumTier2) 200 else 100
            binding.inviteTitle.setI18nText(
                R.string.too_many_user_guilds_alert_description,
                "quantity" to guildLimit,
            )
            binding.inviteMessage.setText(R.string.too_many_user_guilds_description)
            binding.inviteMessage.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.uikit_textsize_medium),
            )
        }
        binding.inviteMessage.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        binding.inviteMemberContainerLayout.visibility = View.GONE
        binding.inviteAvatar.configure(null, null)
        val image = requireNotNull<VitoImageView>(binding.inviteAvatar.findViewById(R.id.guild_avatar))
        image.setActualImageResource(context.getThemedDrawableRes(R.attr.img_poop, R.drawable.img_poop_dark))
        requireNotNull<TextView>(binding.inviteAvatar.findViewById(R.id.guild_text)).visibility = View.GONE
        binding.inviteAvatarSmall.visibility = View.GONE
    }

    private fun configureForDirectFriend(model: ModelInvite) {
        val inviter = model.getInviter()
        val username = inviter?.username.orEmpty()
        binding.inviteAvatar.configure(userAvatarUrl(inviter?.let(::CoreUser)), null)
        binding.inviteAvatarSmall.visibility = View.GONE
        binding.inviteMemberContainerLayout.visibility = View.GONE
        binding.inviteTitle.setI18nText(R.string.instant_invite_you_have_been_invited_to_chat, "username" to username)
        binding.inviteTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        val tag = "$username#${inviter?.discriminator.orEmpty()}"
        binding.inviteMessage.setI18nText(R.string.instant_invite_direct_friend_description, "username" to tag)
        binding.inviteMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    private fun configureForGdm(model: ModelInvite) {
        val channel = model.channel ?: return
        val inviter = model.getInviter()
        binding.inviteAvatarSmall.setImageURI(userAvatarUrl(inviter?.let(::CoreUser)))
        val named = !channel.icon.isNullOrEmpty()
        binding.inviteAvatar.configure(groupDmIconUrl(channel), null)
        binding.inviteAvatarSmall.visibility = if (named) View.VISIBLE else View.GONE
        val channelName = channel.name.orEmpty().ifBlank { inviter?.username.orEmpty() }
        binding.inviteMessage.text = bold(channelName)
        if (channel.name.isNullOrEmpty()) {
            binding.inviteTitle.setText(R.string.instant_invite_you_have_been_invited_to_join_group_dm)
        } else {
            binding.inviteTitle.setI18nText(R.string.auth_message_invited_by, "username" to inviter?.username.orEmpty())
        }
        binding.inviteMemberContainerLayout.visibility = View.VISIBLE
        binding.inviteMemberContainerLayout.setOnline(null)
        binding.inviteMemberContainerLayout.setMembers(model.getApproximateMemberCount())
    }

    private fun configureForGuild(model: ModelInvite) {
        val guild = model.guild ?: return
        binding.inviteAvatar.configure(IconUtils.getForGuild(guild.id, guild.icon), computeShortName(guild.name))
        val showInviter = model.getInviter() != null && model.getApproximateMemberCount() < 200
        binding.inviteAvatarSmall.visibility = if (showInviter) View.VISIBLE else View.GONE
        if (showInviter) binding.inviteAvatarSmall.setImageURI(userAvatarUrl(model.getInviter()?.let(::CoreUser)))
        binding.inviteMemberContainerLayout.visibility = View.VISIBLE
        binding.inviteMemberContainerLayout.setMembers(if (showInviter) null else model.getApproximateMemberCount())
        binding.inviteMemberContainerLayout.setOnline(if (showInviter) null else model.getApproximatePresenceCount())
        binding.inviteMessage.text = bold(guild.name)
        when {
            GuildFeature.VERIFIED in guild.features -> TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
                binding.inviteMessage, R.drawable.ic_verified_badge, 0, 0, 0,
            )
            GuildFeature.PARTNERED in guild.features -> TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
                binding.inviteMessage, R.drawable.ic_partnered_badge, 0, 0, 0,
            )
        }
        val inviterName = model.getInviter()?.username
        if (inviterName != null) {
            binding.inviteTitle.setI18nText(R.string.auth_message_invited_by, "username" to inviterName)
        } else {
            binding.inviteTitle.setText(R.string.instant_invite_you_have_been_invited_to_join)
        }
    }

    private fun bold(text: String, value: String = text): CharSequence = SpannableString(text).apply {
        val start = text.indexOf(value)
        if (start >= 0 && value.isNotEmpty()) {
            setSpan(StyleSpan(Typeface.BOLD), start, start + value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun userAvatarUrl(user: CoreUser?): String? = IconUtils.getForUser(user)

    private fun groupDmIconUrl(channel: Channel): String? = IconUtils.getForChannel(channel)

    private companion object {
        /** `MAX_GUILDS` — the viewer is already in as many servers as their tier allows. */
        private const val ERROR_MAX_GUILDS = 30001
    }
}
