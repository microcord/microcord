package com.discord.widgets.chat.input.autocomplete.adapter

import android.content.Context
import android.graphics.PorterDuff
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isThread
import com.discord.models.commands.displayName
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.user.UserUtils
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.StatusView
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandAutocompletable
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandChoiceAutocompletable
import com.discord.widgets.chat.input.autocomplete.Autocompletable
import com.discord.widgets.chat.input.autocomplete.ChannelAutocompletable
import com.discord.widgets.chat.input.autocomplete.EmojiAutocompletable
import com.discord.widgets.chat.input.autocomplete.GlobalRoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.RoleAutocompletable
import com.discord.widgets.chat.input.autocomplete.UserAutocompletable
import com.discord.views.VitoImageView
import java.util.Locale

/**
 * One autocomplete row. A single layout serves users, roles, channels, emoji and commands, so
 * every bind starts from [clearBinding] — leaving a stale avatar tint or a stale right-hand label
 * behind is the classic recycled-view bug here.
 */
class AutocompleteItemViewHolder(
    itemView: View,
    private val onItemSelected: (Autocompletable) -> Unit,
) : RecyclerView.ViewHolder(itemView) {

    private val avatar: VitoImageView = itemView.findViewById(R.id.chat_input_item_avatar)
    private val status: StatusView = itemView.findViewById(R.id.chat_input_item_status)
    private val emojiView: SimpleDraweeSpanTextView = itemView.findViewById(R.id.chat_input_item_emoji)
    private val name: TextView = itemView.findViewById(R.id.chat_input_item_name)
    private val description: TextView = itemView.findViewById(R.id.chat_input_item_description)
    private val nameRight: TextView = itemView.findViewById(R.id.chat_input_item_name_right)

    private val defaultItemTextColor: Int = name.currentTextColor

    fun bind(autocompletable: Autocompletable) {
        clearBinding()
        when (autocompletable) {
            is ApplicationCommandChoiceAutocompletable -> bindCommandChoice(autocompletable)
            is ApplicationCommandAutocompletable -> bindCommand(autocompletable)
            is ChannelAutocompletable -> bindChannel(autocompletable)
            is EmojiAutocompletable -> bindEmoji(autocompletable)
            is GlobalRoleAutocompletable -> bindGlobalRole(autocompletable)
            is RoleAutocompletable -> bindRole(autocompletable)
            is UserAutocompletable -> bindUser(autocompletable)
            else -> error("Cannot bind autocompletable. Not configured: $autocompletable")
        }
        itemView.setOnClickListener { onItemSelected(autocompletable) }
    }

    private fun bindUser(item: UserAutocompletable) {
        val displayName = item.user.getDisplayName(item.nickname)
        val accountName = item.user.getUserNameWithDiscriminator()
        name.text = displayName
        nameRight.visibility = if (accountName.toString() == displayName) View.GONE else View.VISIBLE
        nameRight.text = accountName
        avatar.visibility = View.VISIBLE
        IconUtils.setIcon(
            imageView = avatar,
            user = item.user,
            sizeDimenRes = R.dimen.avatar_size_standard,
            guildMember = item.guildMember,
        )
        val presence = item.presence
        if (presence != null) {
            status.visibility =
                if (UserUtils.isStatusVisible(item.user, presence, true)) View.VISIBLE else View.GONE
            status.setPresence(presence)
        }
    }

    private fun bindRole(item: RoleAutocompletable) {
        val color = RoleUtils.getOpaqueColor(item.role, ContextCompat.getColor(context, R.color.primary_100))
        name.text = item.role.name
        name.setTextColor(color)
        avatar.visibility = View.VISIBLE
        ResourcesCompat.getDrawable(itemView.resources, R.drawable.ic_mentions_white_24dp, context.theme)?.let {
            avatar.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
            MGImages.setImage(avatar, it)
        }
    }

    private fun bindGlobalRole(item: GlobalRoleAutocompletable) {
        name.text = item.text
        avatar.visibility = View.VISIBLE
        ResourcesCompat.getDrawable(itemView.resources, R.drawable.ic_mentions_white_24dp, context.theme)?.let {
            avatar.setColorFilter(ContextCompat.getColor(context, R.color.primary_100), PorterDuff.Mode.SRC_ATOP)
            MGImages.setImage(avatar, it)
        }
    }

    private fun bindChannel(item: ChannelAutocompletable) {
        val channel = item.channel
        name.text = channel.name
        avatar.visibility = View.VISIBLE
        val padding = dpToPixels(CHANNEL_ICON_PADDING_DP)
        avatar.setPadding(padding, padding, padding, padding)
        MGImages.setImage(avatar, channelIcon(channel))
        avatar.setColorFilter(ColorCompat.getThemedColor(context, R.attr.colorTextMuted), PorterDuff.Mode.SRC_ATOP)
    }

    private fun bindEmoji(item: EmojiAutocompletable) {
        val emoji = item.emoji
        val node = EmojiNode<EmojiRenderContext>(
            emojiName = emoji.firstName,
            urlProvider = { isAnimated, sizePx, ctx -> emoji.getImageUri(isAnimated, sizePx, ctx) },
            emojiIdAndType = EmojiNode.EmojiIdAndType.Unicode(emoji.firstName),
        )
        emojiView.visibility = View.VISIBLE
        emojiView.setDraweeSpanStringBuilder(
            AstRenderer.render(listOf(node), EmojiRenderContext(item.animationsEnabled, context)),
        )
        name.text = ":${emoji.firstName}:"
    }

    private fun bindCommand(item: ApplicationCommandAutocompletable) {
        if (!item.hasPermissionToUse) {
            itemView.isEnabled = false
            itemView.alpha = DISABLED_ALPHA
        }
        name.text = "/${item.command.name}"
        item.command.description?.takeIf { it.isNotBlank() }?.let {
            description.visibility = View.VISIBLE
            description.text = it.trim()
        }
        val application = item.application
        if (application != null) {
            nameRight.visibility = View.VISIBLE
            nameRight.text = application.displayName().uppercase(Locale.getDefault())
        }
        if (item.showAvatar) {
            avatar.visibility = View.VISIBLE
            MGImages.setImage(avatar, R.drawable.ic_slash_command_24dp)
            avatar.setColorFilter(ColorCompat.getThemedColor(context, R.attr.colorTextMuted), PorterDuff.Mode.SRC_ATOP)
        }
    }

    private fun bindCommandChoice(item: ApplicationCommandChoiceAutocompletable) {
        name.text = item.choice.displayName
    }

    private fun clearBinding() {
        itemView.isEnabled = true
        itemView.alpha = 1f
        nameRight.text = null
        nameRight.visibility = View.GONE
        name.text = null
        name.setTextColor(defaultItemTextColor)
        description.text = null
        description.visibility = View.GONE
        emojiView.text = null
        emojiView.visibility = View.GONE
        status.visibility = View.GONE
        avatar.visibility = View.GONE
        avatar.setImageURI(null as String?)
        avatar.setPadding(0, 0, 0, 0)
        avatar.clearColorFilter()
        itemView.contentDescription = null
    }

    /**
     * Icon precedence matches the reference exactly: vocal wins over thread, thread over text, and
     * anything else (a category) falls back to the folder glyph.
     */
    private fun channelIcon(channel: Channel): Int = when {
        channel.isGuildVocalChannel() -> R.drawable.ic_channel_voice
        channel.isThread() -> R.drawable.ic_thread
        channel.isGuildTextyChannel() -> R.drawable.ic_channel_text
        else -> R.drawable.ic_guild_folder
    }

    private val context: Context get() = itemView.context


    /** Minimal [EmojiNode.RenderContext]; the autocomplete row has no emoji click behaviour. */
    private class EmojiRenderContext(
        override val isAnimationEnabled: Boolean,
        override val context: Context,
    ) : EmojiNode.RenderContext

    private companion object {
        private const val CHANNEL_ICON_PADDING_DP = 6
        private const val DISABLED_ALPHA = 0.3f
    }
}
