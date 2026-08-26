package com.discord.utilities.textprocessing

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.node.AttachmentLinkNode
import com.discord.utilities.textprocessing.node.BasicRenderContext
import com.discord.utilities.textprocessing.node.ChannelLinkNode
import com.discord.utilities.textprocessing.node.ChannelMentionNode
import com.discord.utilities.textprocessing.node.CommandMentionNode
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.textprocessing.node.RoleMentionNode
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.textprocessing.node.TimestampNode
import com.discord.utilities.textprocessing.node.UrlNode
import com.discord.utilities.textprocessing.node.UserLinkNode
import com.discord.utilities.textprocessing.node.UserMentionNode
import com.discord.utilities.uri.UriHandler

/**
 * Everything a parsed message AST needs in order to turn itself into spans: the entity name caches
 * used to resolve mentions, the viewer's identity and animation preference, and the click handlers
 * the surface wants to install.
 *
 * A surface that only renders text can leave the handlers null, in which case mentions, spoilers
 * and emoji still render with their colours but are not tappable.
 */
data class MessageRenderContext(
    override val context: Context,

    /** The viewer, so a surface can tell whether a mention targets them. */
    override val myId: Long,

    /** Mirrors the user's animated-emoji setting; animated emoji fall back to a still frame. */
    override val isAnimationEnabled: Boolean,

    override val userNames: Map<Long, String>? = null,
    override val channelNames: Map<Long, String>? = null,
    override val roles: Map<Long, GuildRole>? = null,
    override val userAvatarUrls: Map<Long, String>? = null,
    override val guildNames: Map<Long, String>? = null,
    override val guildIconUrls: Map<Long, String>? = null,
    override val viewingGuildId: Long = 0L,

    @AttrRes override val linkColorAttrResId: Int = R.attr.colorTextLink,
    override val onClickUrl: (Context, String, String?) -> Unit = DEFAULT_URL_CLICK,
    override val onLongPressUrl: (String) -> Unit = DEFAULT_URL_LONG_PRESS,

    @ColorInt override val spoilerColorRes: Int = ColorCompat.getThemedColor(context, R.attr.theme_chat_spoiler_bg),
    @ColorInt override val spoilerRevealedColorRes: Int =
        ColorCompat.getThemedColor(context, R.attr.theme_chat_spoiler_bg_visible),

    override val spoilerOnClick: ((SpoilerNode<*>) -> Unit)? = null,
    override val userMentionOnClick: ((Long) -> Unit)? = null,
    override val channelMentionOnClick: ((Long) -> Unit)? = null,

    /** Opens the channel/message a `discord.com/channels/...` link points at; null keeps it a plain URL. */
    override val channelLinkOnClick: ((channelId: Long, guildId: Long?, messageId: Long?) -> Unit)? = null,

    /** Opens the profile a `discord.com/users/...` link points at; null keeps it a plain URL. */
    override val userLinkOnClick: ((Long) -> Unit)? = null,

    /** Opens the emoji detail sheet; null keeps emoji inert. */
    val emojiClickHandler: ((EmojiNode.EmojiIdAndType) -> Unit)? = null,

    /** Shows the absolute value of a relative timestamp; null keeps timestamps inert. */
    val timestampClickHandler: ((CharSequence) -> Unit)? = null,

    override val commandMentionOnClick: ((Long, String) -> Unit)? = null,
) : BasicRenderContext,
    UserMentionNode.RenderContext,
    EmojiNode.RenderContext,
    ChannelMentionNode.RenderContext,
    ChannelLinkNode.RenderContext,
    UserLinkNode.RenderContext,
    AttachmentLinkNode.RenderContext,
    CommandMentionNode.RenderContext,
    RoleMentionNode.RenderContext,
    UrlNode.RenderContext,
    SpoilerNode.RenderContext,
    TimestampNode.RenderContext {

    override fun onEmojiClicked(emojiIdAndType: EmojiNode.EmojiIdAndType) {
        emojiClickHandler?.invoke(emojiIdAndType)
    }

    override fun onTimestampClicked(full: CharSequence) {
        timestampClickHandler?.invoke(full)
    }

    companion object {
        /** Routes links through the untrusted-link confirmation, exactly as the reference does. */
        private val DEFAULT_URL_CLICK: (Context, String, String?) -> Unit =
            { clickContext, url, mask -> UriHandler.handleOrUntrusted(clickContext, url, mask) }

        private val DEFAULT_URL_LONG_PRESS: (String) -> Unit = { }
    }
}
