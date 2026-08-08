package com.discord.widgets.notice

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isDM
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isPrivate
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.role.GuildRole
import com.discord.api.sticker.Sticker
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.fcm.NotificationClient
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.getDrawable
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
import com.facebook.drawee.span.DraweeSpanStringBuilder
import rx.Observable
import rx.functions.Func9

/**
 * Turns an incoming [Message] into an in-app notification banner.
 *
 * This is the only caller of [NoticePopup] that needs store data, so it lives beside it rather than
 * inside it: the banner has to name the guild and channel, resolve the author's per-guild nickname,
 * and render the message content through the same markdown pipeline the chat list uses.
 *
 * Nothing is shown if the user is already looking at the channel the message arrived in.
 */
object NoticePopupChannel {

    private const val IN_APP_NOTICE_SUFFIX = "InAppNotif"

    /**
     * Builds the banner model and hands it to [NoticePopup].
     *
     * The nine sources are read once, not observed: [takeSingleUntilTimeout] takes the first
     * complete combination and gives up if the stores cannot produce one, because a notification
     * for a message that arrived five seconds ago is not worth showing late.
     */
    fun enqueue(context: Context, noticeName: String, message: Message, onClick: (View) -> Unit) {
        val guildId = message.guildId ?: 0L
        Observable.combineLatest(
            Observable.just(context),
            Observable.just(message),
            StoreStream.getGuilds().observeFromChannelId(message.channelId),
            StoreStream.getChannels().observeChannel(message.channelId),
            StoreStream.getGuilds().observeComputed(guildId),
            StoreStream.getChannelsSelected().observeId(),
            StoreStream.getUsers().observeMe(),
            StoreStream.getChannels().observeNames(),
            StoreStream.getGuilds().observeRoles(guildId),
            Func9<
                Context, Message, Guild?, Channel?, Map<Long, GuildMember>, Long, User,
                Map<Long, String>, Map<Long, GuildRole>, Model?,
                > {
                    modelContext,
                    modelMessage,
                    guild,
                    channel,
                    members,
                    selectedChannelId,
                    meUser,
                    channelNames,
                    roles,
                ->
                createModel(
                    context = modelContext,
                    message = modelMessage,
                    guild = guild,
                    channel = channel,
                    members = members,
                    selectedChannelId = selectedChannelId,
                    meUser = meUser,
                    channelNames = channelNames,
                    roles = roles,
                )
            },
        )
            .takeSingleUntilTimeout()
            .computationLatest()
            .ui()
            .appSubscribe(NoticePopupChannel::class.java) { model ->
                if (model == null) return@appSubscribe
                NoticePopup.enqueue(
                    noticeName = noticeName,
                    noticeTitle = model.noticeTitle,
                    noticeSubtitle = model.noticeSubtitle,
                    noticeBody = model.noticeBody,
                    noticeBodyBackgroundDrawable = model.noticeBodyBackgroundDrawable,
                    noticeBodyImageUrl = model.noticeBodyImageUrl,
                    noticeBodyImageDrawable = model.noticeBodyImageDrawable,
                    noticeStickers = model.noticeStickers,
                    noticeIconUrl = model.noticeIconUrl,
                    noticeIconTopRight = model.noticeIconTopRight,
                    onClickTopRightIcon = model.onClickTopRightIcon,
                    onClick = onClick,
                )
            }
    }

    /**
     * Routes a foreground push into the in-app banner.
     *
     * The message body comes from the store rather than the payload: a foregrounded client has
     * already taken `MESSAGE_CREATE` over the gateway, and the notification carries only the
     * flattened fields. Returning false when it is absent lets the caller drop the notification,
     * which is what it did while this seam was unset.
     */
    fun install() {
        NotificationClient.inAppNotificationHandler = handler@{ context, data ->
            val channelId = data.channelId
            val messageId = data.messageId ?: return@handler false
            val message = StoreStream.getMessages().getMessages(channelId)
                .firstOrNull { it.id == messageId }
                ?: return@handler false
            enqueue(context, "$channelId$IN_APP_NOTICE_SUFFIX", message) {
                ChannelSelector.getInstance().selectChannel(data.guildId ?: 0L, channelId)
            }
            true
        }
    }

    /** @return `null` when there is no channel, or the user is already reading it. */
    private fun createModel(
        context: Context,
        message: Message,
        guild: Guild?,
        channel: Channel?,
        members: Map<Long, GuildMember>,
        selectedChannelId: Long?,
        meUser: User,
        channelNames: Map<Long, String>,
        roles: Map<Long, GuildRole>,
    ): Model? {
        if (channel == null || selectedChannelId == channel.id) return null

        val renderContext = MessageRenderContext(
            context = context,
            myId = meUser.id,
            isAnimationEnabled = true,
            userNames = MessageUtils.getNickOrUsernames(message, channel, members),
            channelNames = channelNames,
            roles = roles,
            // A banner is transient and unfocusable, so a spoiler cannot be revealed in place; it
            // gets its own darker background attribute rather than the chat list's.
            spoilerColorRes = ColorCompat.getThemedColor(context, R.attr.theme_chat_spoiler_inapp_bg),
        )

        // Masked links are disabled: a notification is not a surface where the user can inspect a
        // link's real destination before tapping it.
        val body = message.content?.let { content ->
            AstRenderer.render(
                DiscordParser.createParser(
                    allowMaskedLinks = false,
                    supportEntityMentions = true,
                    renderBlockQuotes = false,
                ).parse(content, MessageParseState.initialState),
                renderContext,
            )
        } ?: DraweeSpanStringBuilder()

        // An attachment- or embed-only message would otherwise show a blank banner.
        if (body.isBlank()) {
            body.append(message.embeds?.firstOrNull()?.title.orEmpty())
        }

        val isInGuild = channel.guildId != NO_GUILD_ID && channel.guildId != 0L
        val title: CharSequence = if (isInGuild) guild?.name.orEmpty() else channel.getDisplayName()
        val subtitle: CharSequence? = if (isInGuild) "#${channel.getDisplayName()}" else null

        val iconUrl = if (channel.isGuildTextyChannel()) {
            IconUtils.getForGuild(guild, DEFAULT_GUILD_ICON_ASSET)
        } else {
            IconUtils.getForChannel(channel)
        }

        // In a one-to-one DM the banner title is already the other person, so prefixing the body
        // with their name would say it twice.
        if (!channel.isDM()) {
            message.author?.let { author ->
                val user = CoreUser(author)
                val authorPrefix = SpannableStringBuilder(
                    "${GuildMember.getNickOrUsername(members[user.id], user)}: ",
                )
                listOf(
                    ForegroundColorSpan(ColorCompat.getThemedColor(context, R.attr.primary_000)),
                    StyleSpan(Typeface.BOLD),
                ).forEach { span ->
                    authorPrefix.setSpan(span, 0, authorPrefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                body.insert(0, authorPrefix)
            }
        }

        val attachment = message.attachments?.firstOrNull()
        var bodyImageUrl: String? = null
        var bodyImageDrawable: Drawable? = null
        var bodyBackgroundDrawable: Drawable? = null
        when {
            attachment == null -> Unit
            // A spoilered attachment shows the spoiler tile, never the image itself.
            attachment.isSpoiler ->
                bodyImageDrawable = ContextCompat.getDrawable(
                    context,
                    context.getThemedDrawableRes(R.attr.theme_chat_spoiler_inapp_bg),
                )
            attachment.type == MessageAttachmentType.IMAGE ->
                bodyImageUrl = attachment.proxyUrl
            attachment.type == MessageAttachmentType.VIDEO -> {
                bodyBackgroundDrawable = context.getDrawable(
                    R.drawable.rounded_rectangle_4dp_radius,
                    ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondary),
                )
                bodyImageDrawable = context.getDrawable(
                    R.drawable.ic_play_circle_outline_white_24dp,
                    ColorCompat.getThemedColor(context, R.attr.colorInteractiveActive),
                )
            }
        }

        // A DM has no per-channel notification settings to open, so its affordance just closes.
        val topRightIconAttr =
            if (channel.isPrivate()) R.attr.ic_close_24dp else R.attr.ic_settings

        return Model(
            noticeTitle = title,
            noticeSubtitle = subtitle,
            noticeBody = body,
            noticeIconUrl = iconUrl,
            noticeBodyBackgroundDrawable = bodyBackgroundDrawable,
            noticeBodyImageUrl = bodyImageUrl,
            noticeBodyImageDrawable = bodyImageDrawable,
            noticeIconTopRight = ContextCompat.getDrawable(
                context,
                context.getThemedDrawableRes(topRightIconAttr),
            ),
            onClickTopRightIcon = {
                if (!channel.isPrivate()) {
                    WidgetChannelNotificationSettings.launch(context, channel.id, true)
                }
            },
            noticeStickers = message.stickers,
        )
    }

    private data class Model(
        val noticeTitle: CharSequence,
        val noticeSubtitle: CharSequence?,
        val noticeBody: DraweeSpanStringBuilder,
        val noticeIconUrl: String?,
        val noticeBodyBackgroundDrawable: Drawable?,
        val noticeBodyImageUrl: String?,
        val noticeBodyImageDrawable: Drawable?,
        val noticeIconTopRight: Drawable?,
        val onClickTopRightIcon: (View) -> Unit,
        val noticeStickers: List<Sticker>?,
    )

    /** `Channel.guildId` for a channel that is not in a guild. */
    private const val NO_GUILD_ID = -1L

    private const val DEFAULT_GUILD_ICON_ASSET = "asset://asset/images/default_icon_selected.jpg"
}
