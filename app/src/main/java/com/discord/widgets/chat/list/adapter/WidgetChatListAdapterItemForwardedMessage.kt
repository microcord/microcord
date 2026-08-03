package com.discord.widgets.chat.list.adapter

import android.graphics.Color
import android.net.Uri
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.botuikit.ActionRowComponent
import com.discord.api.botuikit.ButtonComponent
import com.discord.api.botuikit.Component
import com.discord.api.botuikit.SelectComponent
import com.discord.api.botuikit.TextComponent
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isPrivate
import com.discord.api.message.MessageFlags
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.embed.MessageEmbed
import com.discord.databinding.WidgetChatListAdapterItemForwardedMessageBinding
import com.discord.stores.StoreStream
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import com.discord.views.ThemedColor
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.discord.widgets.chat.list.entries.ForwardedSourcePrivacy
import com.discord.widgets.chat.list.entries.StickerEntry
import com.discord.widgets.media.WidgetMedia

class WidgetChatListAdapterItemForwardedMessage(
    private val parentAdapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_forwarded_message, parentAdapter), FragmentLifecycleListener {
    private val binding = WidgetChatListAdapterItemForwardedMessageBinding.bind(itemView)
    private val mediaViews = ArrayList<InlineMediaView>()

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as ForwardedMessageEntry
        val content = entry.state == ForwardedMessageEntry.State.CONTENT
        binding.forwardedMessageLoading.visibility = if (entry.state == ForwardedMessageEntry.State.LOADING) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.forwardedMessageRows.visibility = if (content) View.VISIBLE else View.GONE
        binding.forwardedMessageError.apply {
            val textId = when (entry.state) {
                ForwardedMessageEntry.State.DELETED -> R.string.forwarded_message_deleted
                ForwardedMessageEntry.State.UNAVAILABLE -> R.string.forwarded_message_unavailable
                ForwardedMessageEntry.State.DEPTH_LIMIT -> R.string.forwarded_message_nested_unavailable
                else -> null
            }
            visibility = if (textId == null) View.GONE else View.VISIBLE
            textId?.let(::setText)
        }

        bindContent(entry)
        bindRows(entry)
        binding.forwardedMessagePoll.apply {
            val question = entry.message?.poll?.question?.text
            visibility = if (question.isNullOrBlank()) View.GONE else View.VISIBLE
            text = question
            contentDescription = question?.let { context.getString(R.string.forwarded_message_poll_a11y, it) }
        }
        bindSource(entry)
        binding.root.contentDescription = when (entry.state) {
            ForwardedMessageEntry.State.LOADING -> itemView.context.getString(R.string.forwarded_message_loading)
            ForwardedMessageEntry.State.CONTENT -> null
            ForwardedMessageEntry.State.DELETED -> itemView.context.getString(R.string.forwarded_message_deleted)
            ForwardedMessageEntry.State.UNAVAILABLE -> itemView.context.getString(R.string.forwarded_message_unavailable)
            ForwardedMessageEntry.State.DEPTH_LIMIT -> itemView.context.getString(R.string.forwarded_message_nested_unavailable)
        }
    }

    private fun bindContent(entry: ForwardedMessageEntry) {
        val message = entry.message
        if (entry.state != ForwardedMessageEntry.State.CONTENT || message == null) {
            binding.forwardedMessageContent.visibility = View.GONE
            binding.forwardedMessageContent.text = null
            return
        }
        val names = message.mentions.orEmpty().associate { mention ->
            mention.id to (mention.globalName?.takeIf(String::isNotBlank) ?: mention.username)
        }
        val renderContext = MessageRenderContext(
            context = itemView.context,
            myId = parentAdapter.data.userId,
            isAnimationEnabled = true,
            userNames = names,
            channelNames = parentAdapter.data.channelNames,
            roles = null,
            linkColorAttrResId = R.attr.colorTextLink,
            onClickUrl = { context, url, mask -> UriHandler.handleOrUntrusted(context, url, mask) },
            onLongPressUrl = parentAdapter.eventHandler::onUrlLongClicked,
            spoilerColorRes = ThemedColor.resolve(itemView.context, R.attr.theme_chat_spoiler_bg),
            spoilerRevealedColorRes = ThemedColor.resolve(itemView.context, R.attr.theme_chat_spoiler_bg_visible),
            spoilerOnClick = null,
            userMentionOnClick = { userId ->
                parentAdapter.eventHandler.onUserMentionClicked(
                    userId,
                    parentAdapter.data.channelId,
                    parentAdapter.data.guildId,
                )
            },
            channelMentionOnClick = { channelId ->
                ChannelSelector.getInstance().findAndSet(itemView.context, channelId)
            },
        )
        val rendered = DiscordParser.parseChannelMessage(
            itemView.context,
            message.content.orEmpty(),
            renderContext,
            MessagePreprocessor(
                myUserId = parentAdapter.data.userId,
                messageState = null,
                embeds = message.embeds,
                shouldJumboify = true,
                maxNodes = null,
            ),
            DiscordParser.ParserOptions.DEFAULT,
            false,
        )
        binding.forwardedMessageContent.visibility = if (rendered.isEmpty()) View.GONE else View.VISIBLE
        binding.forwardedMessageContent.setDraweeSpanStringBuilder(rendered)
    }

    private fun bindRows(entry: ForwardedMessageEntry) {
        mediaViews.forEach(InlineMediaView::onPause)
        mediaViews.clear()
        binding.forwardedMessageRows.removeAllViews()
        if (entry.state != ForwardedMessageEntry.State.CONTENT) return

        entry.rows.forEach { row ->
            when (row) {
                is AttachmentEntry -> bindAttachment(row)
                is EmbedEntry -> bindEmbed(row)
                is StickerEntry -> bindSticker(row)
                else -> Unit
            }
        }
        bindComponents(entry.message?.components.orEmpty())
    }

    private fun bindAttachment(entry: AttachmentEntry) {
        val attachment = entry.attachment
        val width = attachment.width ?: 0
        val height = attachment.height ?: 0
        if (attachment.type != MessageAttachmentType.FILE && width > 0 && height > 0) {
            val (scaledWidth, scaledHeight) = EmbedResourceUtils.calculateScaledSize(
                actualWidth = width,
                actualHeight = height,
                maxWidth = EmbedResourceUtils.computeMaximumImageWidthPx(itemView.context),
                maxHeight = EmbedResourceUtils.MAX_IMAGE_VIEW_HEIGHT_PX,
                resources = itemView.resources,
            )
            addMedia(scaledWidth, scaledHeight) { media ->
                media.contentDescription = attachment.filename
                media.updateUIWithAttachment(attachment, scaledWidth, scaledHeight, entry.autoPlayGifs)
                media.setOnClickListener { WidgetMedia.launch(itemView.context, attachment) }
            }
            return
        }

        binding.forwardedMessageRows.addView(accessoryText().apply {
            val size = Formatter.formatFileSize(context, attachment.size)
            text = "${attachment.filename}  •  $size"
            contentDescription = "${attachment.filename}, $size"
            setCompoundDrawablesWithIntrinsicBounds(
                EmbedResourceUtils.getFileDrawable(attachment.filename),
                0,
                R.drawable.ic_file_download_grey_24dp,
                0,
            )
            setOnClickListener {
                val started = parentAdapter.eventHandler.onQuickDownloadClicked(
                    Uri.parse(attachment.url),
                    attachment.filename,
                )
                if (started) {
                    isEnabled = false
                    alpha = 0.3f
                } else {
                    UriHandler.handleOrUntrusted(context, attachment.url)
                }
            }
        })
    }

    private fun bindEmbed(entry: EmbedEntry) {
        val embed = entry.embed
        val container = LinearLayout(itemView.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWidthParams()
        }
        embed.title?.takeIf(String::isNotBlank)?.let { title ->
            container.addView(accessoryText(semibold = true).apply { text = title })
        }
        embed.description?.takeIf(String::isNotBlank)?.let { description ->
            container.addView(accessoryText().apply { text = description })
        }
        embed.fields.orEmpty().forEach { field ->
            container.addView(accessoryText(semibold = true).apply { text = field.name })
            container.addView(accessoryText().apply { text = field.value })
        }
        val dimensions = embedDimensions(embed)
        if (dimensions != null) {
            val (scaledWidth, scaledHeight) = dimensions
            val media = createMedia(scaledWidth, scaledHeight).apply {
                contentDescription = listOfNotNull(embed.title, embed.description).joinToString(". ")
                updateUIWithEmbed(embed, scaledWidth, scaledHeight, entry.autoPlayGifs)
                embed.url?.let { url -> setOnClickListener { UriHandler.handleOrUntrusted(context, url) } }
            }
            container.addView(media)
        }
        if (container.childCount > 0) binding.forwardedMessageRows.addView(container)
    }

    private fun bindSticker(entry: StickerEntry) {
        binding.forwardedMessageRows.addView(StickerView(itemView.context).apply {
            val size = dp(STICKER_SIZE_DP)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(ACCESSORY_INSET_DP) }
            clearBackgrounds()
            setSticker(entry.sticker)
            setOnClickListener { parentAdapter.eventHandler.onStickerClicked(entry.message, entry.sticker) }
        })
    }

    private fun bindComponents(components: List<Component>) {
        components.flatMap(::componentSummaries).forEach { summary ->
            binding.forwardedMessageRows.addView(accessoryText(semibold = true).apply {
                text = summary
                contentDescription = summary
                isEnabled = false
                alpha = 0.7f
            })
        }
    }

    private fun componentSummaries(component: Component): List<String> = when (component) {
        is ActionRowComponent -> component.components.flatMap(::componentSummaries)
        is ButtonComponent -> listOfNotNull(component.label?.takeIf(String::isNotBlank))
        is SelectComponent -> listOf(
            component.placeholder?.takeIf(String::isNotBlank)
                ?: itemView.context.getString(R.string.message_select_component_default_placeholder),
        )
        is TextComponent -> listOf(component.label)
        else -> emptyList()
    }

    private fun embedDimensions(embed: MessageEmbed): Pair<Int, Int>? {
        val width = embed.image?.width ?: embed.video?.width ?: embed.thumbnail?.width ?: return null
        val height = embed.image?.height ?: embed.video?.height ?: embed.thumbnail?.height ?: return null
        if (width <= 0 || height <= 0) return null
        return EmbedResourceUtils.calculateScaledSize(
            actualWidth = width,
            actualHeight = height,
            maxWidth = EmbedResourceUtils.computeMaximumImageWidthPx(itemView.context),
            maxHeight = EmbedResourceUtils.MAX_IMAGE_VIEW_HEIGHT_PX,
            resources = itemView.resources,
        )
    }

    private fun addMedia(width: Int, height: Int, bind: (InlineMediaView) -> Unit) {
        binding.forwardedMessageRows.addView(createMedia(width, height).also(bind))
    }

    private fun createMedia(width: Int, height: Int) = InlineMediaView(itemView.context).apply {
        layoutParams = LinearLayout.LayoutParams(width, height).apply {
            marginStart = dp(ACCESSORY_INSET_DP)
            topMargin = dp(ACCESSORY_SPACING_DP)
        }
        setCardBackgroundColor(Color.TRANSPARENT)
        cardElevation = 0f
        mediaViews += this
    }

    private fun accessoryText(semibold: Boolean = false) = TextView(itemView.context).apply {
        layoutParams = matchWidthParams()
        setPadding(dp(ACCESSORY_INSET_DP), dp(ACCESSORY_SPACING_DP), dp(ACCESSORY_INSET_DP), 0)
        setTextAppearance(if (semibold) R.style.UiKit_TextView_Semibold else R.style.UiKit_TextView)
        setTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextNormal))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.START
    }

    private fun matchWidthParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int) = (value * itemView.resources.displayMetrics.density).toInt()

    private fun bindSource(entry: ForwardedMessageEntry) {
        val reference = entry.parentMessage.messageReference
        val channelId = reference?.channelId
        val messageId = reference?.messageId
        val channel = channelId?.let(StoreStream.getChannels()::getChannel)
        val permissions = channelId?.let { StoreStream.getPermissions().getPermissionsByChannel()[it] }
        val accessible = channel != null && (channel.isPrivate() || PermissionUtils.hasAccess(channel, permissions))
        val timestamp = entry.message?.timestamp?.dateTimeMillis
            ?: messageId?.let(SnowflakeUtils::toTimestamp)
            ?: 0L
        val time = TimeUtils.toReadableTimeString(itemView.context, timestamp)
        val guild = reference?.guildId?.let(StoreStream.getGuilds()::getGuild)
        val sourceVisibility = ForwardedSourcePrivacy.visibility(
            destinationGuildId = parentAdapter.data.guildId.takeUnless { it == 0L },
            sourceGuildId = reference?.guildId,
            channelKnown = channel != null,
            channelAccessible = accessible,
            guildKnown = guild != null,
        )
        val source = ForwardedSourcePrivacy.label(
            sourceVisibility,
            channel?.getDisplayNameOrDefault(itemView.context),
            guild?.name,
        )
        val navigable = ForwardedSourcePrivacy.isNavigable(
            visibility = sourceVisibility,
            messageKnown = channelId != null && messageId != null && messageId > 0L,
            sourceDeleted = entry.parentMessage.hasFlag(MessageFlags.SOURCE_MESSAGE_DELETED),
        )
        binding.forwardedMessageSource.apply {
            visibility = if (source == null) View.GONE else View.VISIBLE
            text = source?.let { itemView.context.getString(R.string.forwarded_message_source, it, time) }
            contentDescription = text
            isClickable = navigable
            isFocusable = navigable
            setOnClickListener(
                if (navigable) View.OnClickListener {
                    StoreStream.getMessagesLoader().jumpToMessage(channelId!!, messageId!!)
                } else {
                    null
                },
            )
        }
    }

    override fun onPause() = mediaViews.forEach(InlineMediaView::onPause)

    override fun onResume() = mediaViews.forEach(InlineMediaView::onResume)

    private companion object {
        const val ACCESSORY_INSET_DP = 12
        const val ACCESSORY_SPACING_DP = 8
        const val STICKER_SIZE_DP = 160
    }
}
