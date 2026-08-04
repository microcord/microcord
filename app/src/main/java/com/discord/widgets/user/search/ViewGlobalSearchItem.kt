package com.discord.widgets.user.search

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.discord.api.channel.getDisplayName
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.models.user.User
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.views.GuildView
import com.discord.views.StatusView
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.checkbox.MaterialCheckBox

class ViewGlobalSearchItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val description: TextView
    private val group: TextView
    private val guildIcon: GuildView
    private val icon: SimpleDraweeView
    private val mentions: TextView
    private val name: TextView
    private val unread: ImageView
    private val status: StatusView
    private val toggle: MaterialCheckBox

    /** Reference `UserRowModes`; [TOGGLE] is what the forward and share destination pickers use. */
    enum class RowMode { NONE, TOGGLE }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_global_search_item, this)
        requireNotNull<View>(findViewById(R.id.guideline))
        description = requireNotNull(findViewById(R.id.item_description_tv))
        group = requireNotNull(findViewById(R.id.item_group_tv))
        guildIcon = requireNotNull(findViewById(R.id.item_guild_icon))
        icon = requireNotNull(findViewById(R.id.item_icon_iv))
        mentions = requireNotNull(findViewById(R.id.item_mentions_tv))
        name = requireNotNull(findViewById(R.id.item_name_tv))
        unread = requireNotNull(findViewById(R.id.item_unread))
        status = requireNotNull(findViewById(R.id.item_status))
        toggle = requireNotNull(findViewById(R.id.item_toggle))
        MGImages.setRoundingParams(
            icon,
            0f,
            circle = true,
            overlayColor = ColorCompat.getThemedColor(context, R.attr.colorBackgroundPrimary),
        )
    }

    fun configure(data: WidgetGlobalSearchModel.ItemDataPayload) {
        guildIcon.visibility = View.GONE
        icon.visibility = View.VISIBLE
        mentions.setTextAndVisibility(data.mentions.takeIf { it > 0 }?.toString())
        unread.visibility = if (data.unread) View.VISIBLE else View.GONE
        setDestinationState(RowMode.NONE)
    }

    /**
     * Reference `SearchableDestinationList`: a destination that cannot receive the message states why
     * in place of its subtitle instead of disappearing, and drops its checkbox so there is nothing
     * to tick.
     */
    fun setDestinationState(
        mode: RowMode,
        selected: Boolean = false,
        enabled: Boolean = true,
        unavailableReason: CharSequence? = null,
    ) {
        toggle.visibility = if (mode == RowMode.TOGGLE && unavailableReason == null) View.VISIBLE else View.GONE
        toggle.isChecked = selected
        if (unavailableReason != null) {
            group.visibility = View.GONE
            mentions.visibility = View.GONE
            description.isAllCaps = false
            description.setSingleLine(false)
            description.maxLines = 2
            description.setTextAndVisibility(unavailableReason)
        } else {
            description.setSingleLine(true)
            description.maxLines = 1
        }
        isEnabled = enabled
        alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    fun onConfigure(data: WidgetGlobalSearchModel.ItemChannel) {
        configure(data)
        status.apply {
            visibility = if (data.channel.type == Channel.DM) View.VISIBLE else View.GONE
            setPresence(data.presence)
        }
        icon.controller = null
        when (data.channel.type) {
            Channel.DM, Channel.GROUP_DM -> setPrivateChannelIcon(data.channel)
            Channel.GUILD_VOICE, Channel.GUILD_STAGE_VOICE -> icon.setActualImageResource(R.drawable.ic_channel_voice_grey_18dp)
            Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD ->
                icon.setActualImageResource(R.drawable.ic_thread_grey_18dp)
            else -> icon.setActualImageResource(R.drawable.ic_channel_text_grey_18dp)
        }
        group.setTextAndVisibility(data.guild?.name)
        // `channel.name` is null for every DM and for unnamed group DMs; `getDisplayName()` is what
        // resolves those from their recipients, friend nicknames included.
        name.text = styledText(
            data.matchedResult,
            data.channel.getDisplayName().takeUnless(String::isBlank)
                ?: data.matchedResult.value.toString(),
        )

        when (data.channel.type) {
            Channel.GROUP_DM -> {
                description.text = styledRecipients(data.channel)
                description.visibility = if (description.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                description.isAllCaps = false
            }
            Channel.DM -> {
                description.setTextAndVisibility(data.presence?.activities.orEmpty().firstOrNull()?.let { it.state ?: it.details ?: it.name })
                description.isAllCaps = false
                val recipient = data.channel.recipients?.firstOrNull()
                group.setTextAndVisibility(recipient?.let { "${it.username}#${it.discriminator}" })
            }
            Channel.GUILD_TEXT, Channel.GUILD_VOICE, Channel.GUILD_ANNOUNCEMENT,
            Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD,
            Channel.GUILD_STAGE_VOICE -> {
                description.setTextAndVisibility(data.parentChannel?.name)
                description.isAllCaps = true
            }
        }
    }

    fun onConfigure(data: WidgetGlobalSearchModel.ItemGuild) {
        configure(data)
        status.visibility = View.GONE
        icon.visibility = View.GONE
        guildIcon.visibility = View.VISIBLE
        guildIcon.configure(IconUtils.getForGuild(data.guild), data.guild.shortName)
        name.text = styledText(data.matchedResult, data.guild.name)
        group.setTextAndVisibility(null)
        description.setTextAndVisibility(null)
    }

    fun onConfigure(data: WidgetGlobalSearchModel.ItemUser) {
        configure(data)
        status.apply {
            visibility = View.VISIBLE
            setPresence(data.presence)
        }
        icon.setImageURI(userAvatar(data.user))
        group.text = if (data.isFriend) context.getText(R.string.friends) else null
        name.text = styledText(data.matchedResult, data.matchedResult.value.toString())
        name.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        group.setTextAndVisibility(data.aliases.firstOrNull())
        description.isAllCaps = false
        description.setTextAndVisibility(
            data.aliases.drop(1).filterNot { it == data.matchedResult.value }.joinToString(),
        )
    }

    private fun setPrivateChannelIcon(channel: Channel) {
        val recipient = channel.recipients?.firstOrNull()
        val hash = if (channel.type == Channel.GROUP_DM) channel.icon else recipient?.avatar?.value
        val uri = when {
            hash != null && channel.type == Channel.GROUP_DM ->
                "https://cdn.discordapp.com/channel-icons/${channel.id}/$hash.webp"
            hash != null && recipient != null ->
                "https://cdn.discordapp.com/avatars/${recipient.id}/$hash.webp"
            recipient != null ->
                "asset://asset/images/default_avatar_${recipient.discriminator.toIntOrNull()?.rem(5) ?: 0}.png"
            else -> "asset://asset/images/default_icon.jpg"
        }
        icon.setImageURI(Uri.parse(uri))
    }

    private fun styledRecipients(channel: Channel): CharSequence {
        val color = ContextCompat.getColor(context, R.color.primary_dark_400)
        return SpannableStringBuilder().apply {
            channel.recipients.orEmpty().forEachIndexed { index, user ->
                if (index > 0) append(", ")
                append(user.username)
                val start = length
                append('#').append(user.discriminator)
                setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(0.75f), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun TextView.setTextAndVisibility(value: CharSequence?) {
        text = value
        visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val DISABLED_ALPHA = 0.5f

        internal fun styledText(
            result: WidgetGlobalSearchModel.MatchedResult,
            defaultValue: String,
        ): CharSequence {
            val range = highlightRange(result.firstMatchIndex, result.filterLength) ?: return defaultValue
            return SpannableStringBuilder(result.value).apply {
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    range.first,
                    range.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        private fun userAvatar(user: User): String = user.avatar?.let {
            val extension = if (it.startsWith("a_")) "gif" else "webp"
            "https://cdn.discordapp.com/avatars/${user.id}/$it.$extension"
        } ?: "asset://asset/images/default_avatar_${user.discriminator.rem(5)}.png"
    }
}

internal fun highlightRange(firstMatchIndex: Int, filterLength: Int): IntRange? =
    firstMatchIndex.takeIf { it >= 0 }?.let { it until it + filterLength }
