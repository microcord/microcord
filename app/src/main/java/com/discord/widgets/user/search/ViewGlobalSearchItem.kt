package com.discord.widgets.user.search

import android.content.Context
import android.graphics.Typeface
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
import com.discord.utilities.user.UserUtils.discriminatorWithPadding
import com.discord.views.GuildView
import com.discord.views.StatusView
import com.facebook.drawee.view.SimpleDraweeView

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
                group.setTextAndVisibility(recipient?.let { it.username + it.discriminatorWithPadding })
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
        IconUtils.setIcon(icon, IconUtils.getForChannel(channel))
    }

    private fun styledRecipients(channel: Channel): CharSequence {
        val color = ContextCompat.getColor(context, R.color.primary_dark_400)
        return SpannableStringBuilder().apply {
            channel.recipients.orEmpty().forEachIndexed { index, user ->
                if (index > 0) append(", ")
                append(user.username)
                val suffix = user.discriminatorWithPadding
                if (suffix.isEmpty()) return@forEachIndexed
                val start = length
                append(suffix)
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
        } ?: IconUtils.getDefaultAvatar(IconUtils.getDefaultAvatarIndex(user.id, user.discriminator, user.isWebhook))
    }
}

internal fun highlightRange(firstMatchIndex: Int, filterLength: Int): IntRange? =
    firstMatchIndex.takeIf { it >= 0 }?.let { it until it + filterLength }
