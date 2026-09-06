package com.discord.widgets.chat.list.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.api.message.embed.EmbedAuthor
import com.discord.api.message.embed.EmbedField
import com.discord.api.message.embed.EmbedFooter
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.role.GuildRole
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.databinding.WidgetChatListAdapterItemEmbedBinding
import com.discord.databinding.WidgetChatListAdapterItemEmbedFieldBinding
import com.discord.models.member.GuildMember
import com.discord.models.gifpicker.dto.ModelGif
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.node.TextNode
import com.discord.simpleast.core.parser.Parser
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MarkupEligibility
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.ChannelMentionNode
import com.discord.utilities.textprocessing.node.RoleMentionNode
import com.discord.utilities.textprocessing.node.UrlNode
import com.discord.utilities.textprocessing.node.UserMentionNode
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.media.WidgetMedia
import rx.Observable
import rx.Subscription

/**
 * A rich embed: link preview, article card, bot embed, or a bare image/video that renders as inline
 * media with no card around it.
 *
 * Three things make this row more than "set some text on some views":
 *
 *  * **Two passes.** The embed's own text is parsed and rendered synchronously so the row never
 *    flashes empty, then a second pass re-renders it once the mention names it references have been
 *    resolved from the stores. An embed with no mentions never makes the second pass at all.
 *  * **Spoilers at two levels.** The whole embed is hidden behind a spoiler cover when the message
 *    body wrapped its URL in `||…||`; separately, the title, description and each field may contain
 *    their own spoilers, which are revealed individually and keyed by
 *    `<part>:<index>` inside `StoreMessageState`.
 *  * **Inline vs. card.** An embed that carries nothing but media is rendered as bare
 *    [InlineMediaView] with a transparent card and no stroke; anything with a title, author or
 *    description gets the full card treatment.
 */
class WidgetChatListAdapterItemEmbed(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_embed, adapter),
    FragmentLifecycleListener {

    private val binding = WidgetChatListAdapterItemEmbedBinding.bind(itemView)

    private val userSettings = StoreStream.getUserSettings()
    private val embedTinyIconSize = itemView.resources.getDimensionPixelSize(R.dimen.embed_tiny_icon_size)
    private val embedThumbnailMaxSize =
        itemView.resources.getDimensionPixelSize(R.dimen.embed_thumbnail_max_size)
    private val maxEmbedImageWidth = EmbedResourceUtils.computeMaximumImageWidthPx(itemView.context)

    /**
     * One detector for the whole row, so rebinding an unchanged embed does not re-issue four image
     * requests and flicker the card.
     */
    private val imageChangeDetector = MGImages.DistinctChangeDetector()

    private var nameSubscription: Subscription? = null
    override val subscription: Subscription? get() = nameSubscription

    init {
        listOf<TextView>(
            binding.chatListItemEmbedProvider,
            binding.chatListItemEmbedTitle,
            binding.chatListItemEmbedAuthorText,
            binding.chatListItemEmbedDescription,
            binding.chatListItemEmbedFooterText,
        )
            .forEach { it.copyTextOnLongPress() }
    }

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as EmbedEntry

        itemView.setOnLongClickListenerConsumeClick {
            adapter.eventHandler.onMessageLongClicked(
                entry.message,
                entry.message.content.orEmpty(),
                entry.isThreadStarterMessage,
            )
        }
        itemView.setOnClickListener {
            adapter.eventHandler.onMessageClicked(entry.message, entry.isThreadStarterMessage)
        }
        configureCellHighlight(
            entry.message,
            binding.chatListAdapterItemHighlightedBg,
            binding.chatListAdapterItemGutterBg,
            entry.isPendingReply,
        )

        val improvedMarkdown = MarkupEligibility.allowsImprovedMarkdown(entry.message)
        val model = Model(
            embedEntry = entry,
            parsedDescription = entry.embed.description
                ?.takeIf { entry.embed.showsDescription() }
                ?.let { entry.embed.parseDescription(it, improvedMarkdown) },
            parsedFields = parseFields(entry.embed.fields, improvedMarkdown),
        )
        configureUI(model)

        // Second pass: only an embed that actually mentions a user, role or channel needs names,
        // and only then is it worth touching the stores.
        nameSubscription?.unsubscribe()
        nameSubscription = null
        observeResolvedNames(model)?.ui()?.appSubscribe(
            errorClass = WidgetChatListAdapterItemEmbed::class.java,
            subscriptionHandler = { nameSubscription = it },
        ) { resolved -> configureUI(resolved) }
    }

    override fun onPause() = binding.embedInlineMedia.onPause()

    override fun onResume() = binding.embedInlineMedia.onResume()

    // region binding

    private fun configureUI(model: Model) {
        val entry = model.embedEntry
        val embed = entry.embed
        val renderContext = model.createRenderContext(itemView.context, adapter.eventHandler)

        if (model.isSpoilerHidden) {
            binding.chatListItemEmbedSpoiler.fadeIn(SPOILER_FADE_IN_MS, additionalAnimation = { it.scaleX(1f).scaleY(1f) })
        } else {
            binding.chatListItemEmbedSpoiler.fadeOut(SPOILER_FADE_OUT_MS, additionalAnimation = { it.scaleX(0.9f).scaleY(0.9f) })
        }
        binding.chatListItemEmbedSpoiler.setOnClickListener {
            if (adapter.data.isSpoilerClickAllowed) {
                StoreStream.getMessageState().revealSpoilerEmbed(entry.message.id, entry.embedIndex)
            } else {
                adapter.eventHandler.onMessageClicked(entry.message, entry.isThreadStarterMessage)
            }
        }

        try {
            if (with(EmbedResourceUtils) { embed.isInlineEmbed() }) {
                binding.chatListItemEmbedContent.visibility = View.GONE
                binding.chatListItemEmbedDivider.visibility = View.GONE
                binding.chatListItemEmbedImageThumbnail.visibility = View.GONE
                binding.embedInlineMedia.visibility = View.VISIBLE
                configureInlineEmbed(embed, entry.autoPlayGifs)
                binding.chatListItemEmbedContainerCard.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(itemView.context, R.color.transparent),
                )
                binding.chatListItemEmbedContainerCard.strokeWidth = 0
                configureGifFavorite(embed, binding.chatListItemEmbedInlineFavorite)
                binding.chatListItemEmbedImageFavorite.visibility = View.GONE
            } else {
                binding.chatListItemEmbedContent.visibility = View.VISIBLE
                binding.chatListItemEmbedDivider.visibility = View.VISIBLE
                binding.embedInlineMedia.visibility = View.GONE
                configureEmbedDivider(embed.color.takeUnless { model.isSpoilerHidden })
                configureEmbedProvider(embed)
                configureEmbedAuthor(embed.author)
                configureEmbedTitle(model, renderContext)
                configureEmbedDescription(model, renderContext)
                configureEmbedThumbnail(embed)
                configureEmbedImage(embed)
                configureEmbedFields(model, renderContext)
                configureFooter(embed.footer, embed.timestamp)
                binding.chatListItemEmbedContainerCard.setCardBackgroundColor(
                    ThemedColor.resolve(itemView.context, R.attr.colorBackgroundSecondary),
                )
                binding.chatListItemEmbedContainerCard.strokeWidth =
                    itemView.resources.getDimensionPixelSize(R.dimen.chat_embed_card_stroke_width)
                configureGifFavorite(embed, binding.chatListItemEmbedImageFavorite)
                binding.chatListItemEmbedInlineFavorite.visibility = View.GONE
            }
            itemView.visibility = View.VISIBLE
        } catch (failure: Exception) {
            // A malformed embed must cost one row, not the whole list.
            itemView.visibility = View.GONE
            Log.e(LOG_TAG, "Unable to render embed.", failure)
        }
    }

    private fun configureInlineEmbed(embed: MessageEmbed, autoPlayGifs: Boolean) {
        val preview = with(EmbedResourceUtils) { embed.getPreviewImage() }
        val width = preview?.width ?: 0
        val height = preview?.height ?: 0
        if (width > 0 && height > 0) {
            val (scaledWidth, scaledHeight) = EmbedResourceUtils.calculateScaledSize(
                actualWidth = width,
                actualHeight = height,
                maxWidth = maxEmbedImageWidth,
                maxHeight = MAX_EMBED_IMAGE_HEIGHT_PX,
                resources = itemView.resources,
            )
            binding.embedInlineMedia.updateUIWithEmbed(embed, scaledWidth, scaledHeight, autoPlayGifs)
        } else {
            binding.embedInlineMedia.updateUIWithEmbed(embed, autoPlayGifs = autoPlayGifs)
        }
        // An inline embed is nothing but its media, so tapping it opens the fullscreen viewer —
        // unlike the carded embed's picture below, which follows the embed's own link.
        binding.embedInlineMedia.setOnClickListener { view -> WidgetMedia.launch(view.context, embed) }
    }

    private fun configureGifFavorite(embed: MessageEmbed, button: ImageView) {
        val gif = embed.favoriteGif()
        if (gif == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            return
        }
        val favorites = StoreStream.getMediaFavorites()
        var isFavorite = favorites.isFavoriteGif(gif)

        fun render() {
            button.alpha = if (isFavorite) 1f else 0.7f
            button.setColorFilter(
                ContextCompat.getColor(button.context, if (isFavorite) R.color.status_yellow else R.color.white),
            )
            button.contentDescription = button.context.getString(
                if (isFavorite) R.string.gif_tooltip_remove_from_favorites
                else R.string.gif_tooltip_favorited_picker_button,
            )
        }

        button.visibility = View.VISIBLE
        render()
        button.setOnClickListener {
            favorites.setFavoriteGif(gif, !isFavorite)
            isFavorite = !isFavorite
            render()
            Toast.makeText(button.context, button.contentDescription, Toast.LENGTH_SHORT).show()
        }
    }

    private fun MessageEmbed.favoriteGif(): ModelGif? {
        if (!with(EmbedResourceUtils) { isAnimated() }) return null
        val preview = with(EmbedResourceUtils) { getPreviewImage() }
        val mediaUrl = preview?.url ?: video?.proxyUrl ?: video?.url ?: return null
        return ModelGif(
            gifImageUrl = mediaUrl,
            tenorGifUrl = url ?: mediaUrl,
            width = video?.width ?: preview?.width ?: 0,
            height = video?.height ?: preview?.height ?: 0,
        )
    }

    private fun configureEmbedDivider(color: Int?) {
        binding.chatListItemEmbedDivider.setBackgroundColor(
            if (color != null) {
                ColorUtils.setAlphaComponent(color, 255)
            } else {
                ThemedColor.resolve(itemView.context, R.attr.colorBackgroundModifierAccent)
            },
        )
    }

    private fun configureEmbedProvider(embed: MessageEmbed) {
        val embedProvider = embed.provider
        if (embedProvider == null) {
            binding.chatListItemEmbedProvider.visibility = View.GONE
            return
        }
        binding.chatListItemEmbedProvider.text = embedProvider.name
        binding.chatListItemEmbedProvider.bindUrlOnClick(embedProvider.url, embedProvider.name)
        binding.chatListItemEmbedProvider.visibility = View.VISIBLE
    }

    private fun configureEmbedAuthor(author: EmbedAuthor?) {
        if (author != null) {
            binding.chatListItemEmbedAuthorText.text = author.name
            binding.chatListItemEmbedAuthorText.bindUrlOnClick(author.url, author.name)
            binding.chatListItemEmbedAuthorText.visibility = View.VISIBLE
        } else {
            binding.chatListItemEmbedAuthorText.visibility = View.GONE
        }

        val iconUrl = author?.proxyIconUrl
        if (iconUrl == null) {
            binding.chatListItemEmbedAuthorIcon.visibility = View.GONE
            return
        }
        binding.chatListItemEmbedAuthorIcon.visibility = View.VISIBLE
        setScaledImage(binding.chatListItemEmbedAuthorIcon, embedTinyIconSize, embedTinyIconSize, embedTinyIconSize, iconUrl)
    }

    private fun configureEmbedTitle(model: Model, renderContext: MessageRenderContext) {
        val embed = model.embedEntry.embed
        val titleText = embed.title
        if (titleText == null) {
            binding.chatListItemEmbedTitle.visibility = View.GONE
            return
        }
        val embedIndex = model.embedEntry.embedIndex
        val ast = embed.parseTitle(titleText)
        model.preprocessorFor(embedIndex, PART_TITLE).process(ast)
        binding.chatListItemEmbedTitle.setDraweeSpanStringBuilder(
            AstRenderer.render(
                ast,
                renderContext.copy(
                    spoilerOnClick = { node ->
                        StoreStream.getMessageState().revealSpoilerEmbedData(
                            model.embedEntry.message.id,
                            embedIndex,
                            "$PART_TITLE:${node.id}",
                        )
                    },
                ),
            ),
        )
        binding.chatListItemEmbedTitle.movementMethod = LinkMovementMethod.getInstance()
        binding.chatListItemEmbedTitle.setTextColor(
            ThemedColor.resolve(
                itemView.context,
                if (embed.url != null) R.attr.colorTextLink else R.attr.primary_100,
            ),
        )
        binding.chatListItemEmbedTitle.bindUrlOnClick(embed.url, embed.title)
        binding.chatListItemEmbedTitle.visibility = View.VISIBLE
    }

    private fun configureEmbedDescription(model: Model, renderContext: MessageRenderContext) {
        val parsed = model.parsedDescription
        if (parsed == null) {
            binding.chatListItemEmbedDescription.visibility = View.GONE
            return
        }
        val messageId = model.embedEntry.message.id
        val embedIndex = model.embedEntry.embedIndex
        model.preprocessorFor(embedIndex, PART_DESCRIPTION).process(parsed)
        binding.chatListItemEmbedDescription.setDraweeSpanStringBuilder(
            AstRenderer.render(
                parsed,
                renderContext.copy(
                    spoilerOnClick = { node ->
                        StoreStream.getMessageState()
                            .revealSpoilerEmbedData(messageId, embedIndex, "$PART_DESCRIPTION:${node.id}")
                    },
                ),
            ),
        )
        binding.chatListItemEmbedDescription.movementMethod = LinkMovementMethod.getInstance()
        binding.chatListItemEmbedDescription.visibility = View.VISIBLE
    }

    /**
     * Rebuilds the field stack, reusing the rows already inflated.
     *
     * Field rows are recycled by position rather than cleared and re-inflated, because an embed with
     * a dozen fields is common and every row carries two drawee-span text views.
     */
    private fun configureEmbedFields(model: Model, renderContext: MessageRenderContext) {
        val parsedFields = model.parsedFields
        val existingRows = (0 until binding.chatListItemEmbedFields.childCount).map(binding.chatListItemEmbedFields::getChildAt)
        binding.chatListItemEmbedFields.removeAllViews()
        if (parsedFields == null) {
            binding.chatListItemEmbedFields.visibility = View.GONE
            return
        }
        binding.chatListItemEmbedFields.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(binding.chatListItemEmbedFields.context)
        val messageId = model.embedEntry.message.id
        val embedIndex = model.embedEntry.embedIndex

        parsedFields.forEachIndexed { index, field ->
            val row = existingRows.getOrNull(index)?.let(WidgetChatListAdapterItemEmbedFieldBinding::bind)
                ?: WidgetChatListAdapterItemEmbedFieldBinding.inflate(inflater, binding.chatListItemEmbedFields, false)

            fun render(ast: MutableList<Node<MessageRenderContext>>, prefix: String) =
                AstRenderer.render(
                    ast.also { model.preprocessorFor(embedIndex, prefix).process(it) },
                    renderContext.copy(
                        spoilerOnClick = { node ->
                            StoreStream.getMessageState()
                                .revealSpoilerEmbedData(messageId, embedIndex, "$prefix:${node.id}")
                        },
                    ),
                )

            row.chatListItemEmbedFieldName.setDraweeSpanStringBuilder(render(field.parsedName, "$PART_FIELD_NAME:$index"))
            row.chatListItemEmbedFieldValue.setDraweeSpanStringBuilder(render(field.parsedValue, "$PART_FIELD_VALUE:$index"))
            row.chatListItemEmbedFieldName.movementMethod = LinkMovementMethod.getInstance()
            row.chatListItemEmbedFieldValue.movementMethod = LinkMovementMethod.getInstance()
            binding.chatListItemEmbedFields.addView(row.root)
        }
    }

    private fun configureEmbedThumbnail(embed: MessageEmbed) {
        val hasThumbnailLayout = embed.type == EmbedType.LINK ||
            embed.type == EmbedType.HTML ||
            embed.type == EmbedType.RICH
        val embedThumbnail = embed.thumbnail
        // A playable rich embed uses its thumbnail as the large video poster, not a second image.
        if (!hasThumbnailLayout || embedThumbnail == null || with(EmbedResourceUtils) { embed.isPlayable() }) {
            binding.chatListItemEmbedImageThumbnail.visibility = View.GONE
            return
        }
        binding.chatListItemEmbedImageThumbnail.visibility = View.VISIBLE
        setScaledImage(
            view = binding.chatListItemEmbedImageThumbnail,
            maxWidth = embedThumbnailMaxSize,
            width = embedThumbnail.width ?: 0,
            height = embedThumbnail.height ?: 0,
            imageProxyUrl = embedThumbnail.proxyUrl,
        )
    }

    private fun configureEmbedImage(embed: MessageEmbed) {
        val preview = with(EmbedResourceUtils) { embed.getPreviewImage() }
        if (preview == null) {
            binding.embedImageContainer.visibility = View.GONE
            return
        }
        val renderMedia = shouldRenderMedia()
        val hasIntrinsicSize = (preview.width ?: 0) > 0 && (preview.height ?: 0) > 0
        if (renderMedia && hasIntrinsicSize) {
            setScaledImage(
                view = binding.chatListItemEmbedImage,
                maxWidth = maxEmbedImageWidth,
                width = preview.width ?: 0,
                height = preview.height ?: 0,
                imageProxyUrl = preview.url,
                minWidth = maxEmbedImageWidth / 2,
            )
            binding.embedImageContainer.visibility = View.VISIBLE
        } else {
            binding.embedImageContainer.visibility = View.GONE
        }
        binding.chatListItemEmbedImageIcons.visibility =
            if (with(EmbedResourceUtils) { embed.isPlayable() } && renderMedia) View.VISIBLE else View.GONE
        binding.chatListItemEmbedImage.setOnClickListener { view ->
            val externalUrl = with(EmbedResourceUtils) { embed.getExternalOpenUrl() }
            if (externalUrl != null) {
                UriHandler.handleOrUntrusted(view.context, externalUrl, embed.title)
            } else {
                // getExternalOpenUrl is non-null only for VIDEO and FILE embeds, so without this
                // branch every actual image — a pasted image link, GIFV, RICH, LINK, ARTICLE —
                // fell through to an empty lambda and tapping it did nothing at all.
                WidgetMedia.launch(view.context, embed)
            }
        }
    }

    private fun configureFooter(footer: EmbedFooter?, timestamp: UtcDateTime?) {
        val iconUrl = footer?.proxyIconUrl
        if (footer?.text.isNullOrEmpty()) {
            binding.chatListItemEmbedFooterIcon.visibility = View.GONE
        } else {
            binding.chatListItemEmbedFooterIcon.visibility = if (iconUrl != null) View.VISIBLE else View.GONE
            setScaledImage(binding.chatListItemEmbedFooterIcon, embedTinyIconSize, embedTinyIconSize, embedTinyIconSize, iconUrl)
        }

        val readableTime = timestamp?.let {
            TimeUtils.toReadableTimeString(itemView.context, it.dateTimeMillis, adapter.clock)
        }
        binding.chatListItemEmbedFooterText.text = when {
            footer != null && readableTime != null -> "${footer.text} | $readableTime"
            footer != null -> footer.text
            else -> readableTime
        }
        binding.chatListItemEmbedFooterText.visibility =
            if (binding.chatListItemEmbedFooterText.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Sizes [view] to the scaled dimensions of the image it is about to show and binds the image.
     *
     * The layout pass is only requested when the size actually changed — an embed rebinding with the
     * same picture would otherwise trigger a relayout of the whole row on every store emission.
     */
    private fun setScaledImage(
        view: ImageView,
        maxWidth: Int,
        width: Int,
        height: Int,
        imageProxyUrl: String?,
        minWidth: Int = 0,
    ) {
        if (imageProxyUrl == null) return
        view.visibility = View.VISIBLE
        val (scaledWidth, scaledHeight) = EmbedResourceUtils.calculateScaledSize(
            actualWidth = width,
            actualHeight = height,
            maxWidth = maxWidth,
            maxHeight = maxWidth,
            resources = itemView.resources,
            minWidth = minWidth,
        )
        val layoutParams = view.layoutParams
        if (layoutParams.width != scaledWidth || layoutParams.height != scaledHeight) {
            layoutParams.width = scaledWidth
            layoutParams.height = scaledHeight
            view.requestLayout()
        }
        MGImages.setImage(
            view,
            EmbedResourceUtils.getPreviewUrls(imageProxyUrl, scaledWidth, scaledHeight),
            changeDetector = imageChangeDetector,
        )
    }

    private fun shouldRenderMedia(): Boolean =
        userSettings.getIsEmbedMediaInlined() && userSettings.getIsRenderEmbedsEnabled()

    private fun parseFields(fields: List<EmbedField>?, improvedMarkdown: Boolean): List<ParsedField>? =
        fields?.map { field ->
            val valueParser =
                if (improvedMarkdown) UI_THREAD_FIELD_VALUE_IMPROVED_PARSER else UI_THREAD_VALUES_PARSER
            ParsedField(
                parsedName = UI_THREAD_TITLES_PARSER.parseText(field.name),
                // A tab would indent the value into looking like a list item's continuation.
                parsedValue = valueParser.parseText(field.value.replace("\t", "")),
            )
        }

    // endregion

    /**
     * Resolves the display names for every entity the embed's text mentions.
     *
     * Returns null when the embed mentions nothing, which is the common case — an embed with no
     * mentions must not open four store subscriptions per bind.
     */
    private fun observeResolvedNames(model: Model): Observable<Model>? {
        val mentions = Mentions()
        model.parsedDescription?.let(mentions::collect)
        model.parsedFields?.forEach {
            mentions.collect(it.parsedName)
            mentions.collect(it.parsedValue)
        }
        if (mentions.isEmpty()) return null

        val guildId = model.embedEntry.guildId
        val guilds = StoreStream.getGuilds()
        return Observable.combineLatest(
            StoreStream.getUsers().observeMeId(),
            StoreStream.getChannels().observeNames(mentions.channels),
            Observable.combineLatest(
                guilds.observeComputed(guildId, mentions.users),
                StoreStream.getUsers().observeUsernames(mentions.users),
            ) { members: Map<Long, GuildMember>, usernames: Map<Long, String> ->
                // A guild nickname wins over the account username wherever one exists.
                usernames + members.mapNotNull { (id, member) -> member.nick?.let { id to it } }
            },
            guilds.observeRoles(guildId, mentions.roles),
            StoreStream.getUsers().observeFetchingUserIds(),
        ) { myId: Long,
            channelNames: Map<Long, String>,
            userNames: Map<Long, String>,
            roles: Map<Long, GuildRole>,
            _: Set<Long> ->
            model.copy(
                channelNames = channelNames,
                userNames = userNames,
                roles = roles,
                myId = myId,
            )
        }.computationLatest()
    }

    /** The ids an embed's parsed text refers to, gathered in one walk of the trees. */
    private class Mentions {
        val users = HashSet<Long>()
        val channels = HashSet<Long>()
        val roles = HashSet<Long>()

        fun isEmpty() = users.isEmpty() && channels.isEmpty() && roles.isEmpty()

        fun collect(nodes: Collection<Node<MessageRenderContext>>) {
            nodes.forEach { node ->
                when (node) {
                    is UserMentionNode<*> -> users.add(node.userId)
                    is ChannelMentionNode<*> -> channels.add(node.channelId)
                    is RoleMentionNode<*> -> roles.add(node.roleId)
                    else -> Unit
                }
                @Suppress("UNCHECKED_CAST")
                (node.getChildren() as? Collection<Node<MessageRenderContext>>)?.let(::collect)
            }
        }
    }

    /** A field's name and value, parsed once and re-rendered on every bind. */
    data class ParsedField(
        val parsedName: MutableList<Node<MessageRenderContext>>,
        val parsedValue: MutableList<Node<MessageRenderContext>>,
    )

    /**
     * The embed plus everything needed to render its text.
     *
     * The name maps start empty and are filled by the second pass; [isSpoilerHidden] is computed
     * from the *message* body, because it is the message that decides whether this embed's link was
     * spoilered, not the embed itself.
     */
    data class Model(
        val embedEntry: EmbedEntry,
        val parsedDescription: MutableList<Node<MessageRenderContext>>?,
        val parsedFields: List<ParsedField>?,
        val channelNames: Map<Long, String>? = null,
        val userNames: Map<Long, String>? = null,
        val roles: Map<Long, GuildRole>? = null,
        val myId: Long = 0L,
    ) {
        /**
         * True while the embed must stay behind its cover: the message body hid a link in a spoiler
         * and neither that spoiler nor this embed has been revealed yet.
         */
        val isSpoilerHidden: Boolean = !isSpoilerEmbedRevealed() && isSpoilerEmbed()

        fun createRenderContext(
            androidContext: Context,
            eventHandler: WidgetChatListAdapter.EventHandler,
        ): MessageRenderContext = MessageRenderContext(
            context = androidContext,
            myId = myId,
            isAnimationEnabled = embedEntry.allowAnimatedEmojis,
            userNames = userNames,
            channelNames = channelNames,
            roles = roles,
            onLongPressUrl = eventHandler::onUrlLongClicked,
            userMentionOnClick = { userId ->
                eventHandler.onUserMentionClicked(userId, embedEntry.message.channelId, embedEntry.guildId)
            },
        )

        fun preprocessorFor(embedIndex: Int, part: String): MessagePreprocessor = MessagePreprocessor(
            myUserId = myId,
            visibleSpoilerNodeIndices = embedEntry.messageState
                ?.visibleSpoilerEmbedMap
                ?.let { revealedIndices(it, embedIndex, part) },
        )

        /**
         * The spoiler indices the user has revealed inside one part of this embed.
         *
         * `StoreMessageState` keys revealed embed spoilers as `"<part>:<index>"` under the embed's
         * position in the message, so the prefix selects the part and the tail is the node index.
         */
        private fun revealedIndices(
            visibleSpoilerEmbedMap: Map<Int, Set<String>>,
            embedIndex: Int,
            keyPrefix: String,
        ): List<Int>? = visibleSpoilerEmbedMap[embedIndex]
            ?.filter { it.startsWith(keyPrefix) }
            ?.mapNotNull { it.drop(keyPrefix.length + 1).toIntOrNull() }

        private fun isSpoilerEmbedRevealed(): Boolean =
            embedEntry.messageState?.visibleSpoilerEmbedMap?.containsKey(embedEntry.embedIndex) == true

        /**
         * Whether this embed came from a link the author put inside a spoiler.
         *
         * A spoiler that the user already opened stops hiding its embed, which is why an embed whose
         * URL appears in a revealed spoiler is not treated as spoilered.
         */
        private fun isSpoilerEmbed(): Boolean {
            val preprocessor = MessagePreprocessor(myId, embedEntry.messageState)
            val ast = UI_THREAD_TITLES_PARSER.parseText(embedEntry.message.content.orEmpty())
            preprocessor.process(ast)
            val spoilers = preprocessor.spoilers ?: return false
            if (spoilers.none { it.content.contains("http") }) return false

            val revealed = embedEntry.messageState?.visibleSpoilerNodeIndices.orEmpty()
                .mapNotNull(spoilers::getOrNull)
            return !revealed.any { containsUrl(listOf(it), embedEntry.embed.url) }
        }

        private fun containsUrl(nodes: Collection<Node<*>>, url: String?): Boolean {
            if (url == null) return false
            return nodes.any { node ->
                (node is UrlNode<*> && node.url.equals(url, ignoreCase = true)) ||
                    containsUrl(node.getChildren().orEmpty(), url)
            }
        }
    }

}

private const val LOG_TAG = "ChatListAdapterEmbed"

private const val PART_TITLE = "title"
private const val PART_DESCRIPTION = "desc"
private const val PART_FIELD_NAME = "f_name"
private const val PART_FIELD_VALUE = "f_value"

private const val SPOILER_FADE_IN_MS = 50L
private const val SPOILER_FADE_OUT_MS = 200L

/** Embed images are never drawn taller than this, however tall the source is. */
private val MAX_EMBED_IMAGE_HEIGHT_PX = dpToPixels(360)

/**
 * Titles, field names and the message body: no masked links (a bot could otherwise disguise a link
 * as arbitrary text), no entity mentions, and none of the block constructs, which a single-line
 * heading has no room for.
 */
private val UI_THREAD_TITLES_PARSER = DiscordParser.createParser(
    allowMaskedLinks = false,
    supportEntityMentions = false,
    renderCodeBlocks = false,
    renderSubtext = false,
)

/**
 * A title of an embed that carries a URL.
 *
 * The whole title is already a link to that URL, so a link inside it would nest one tappable
 * region in another.
 */
private val UI_THREAD_TITLES_NO_LINKS_PARSER = DiscordParser.createParser(
    allowMaskedLinks = false,
    supportEntityMentions = false,
    renderCodeBlocks = false,
    renderSubtext = false,
    renderLinks = false,
)

/**
 * Descriptions and field values, which may legitimately carry masked links and mentions whatever
 * the message's age — only headers and lists are held back before the improved-markdown epoch.
 *
 * A field is laid out in a narrow column beside its neighbours, so it takes lists but not headers.
 */
private val UI_THREAD_VALUES_PARSER =
    DiscordParser.createParser(allowMaskedLinks = true, supportEntityMentions = true)

private val UI_THREAD_DESCRIPTION_IMPROVED_PARSER = DiscordParser.createParser(
    allowMaskedLinks = true,
    supportEntityMentions = true,
    renderHeaders = true,
    renderLists = true,
)

private val UI_THREAD_FIELD_VALUE_IMPROVED_PARSER = DiscordParser.createParser(
    allowMaskedLinks = true,
    supportEntityMentions = true,
    renderLists = true,
)

private fun Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>.parseText(
    text: String,
): MutableList<Node<MessageRenderContext>> = parseEmbedTextSafely(text) {
    ArrayList(parse(text, MessageParseState.initialState))
}

/** A media embed shows its image where a description would go. */
private fun MessageEmbed.showsDescription(): Boolean = when (type) {
    EmbedType.IMAGE, EmbedType.VIDEO, EmbedType.GIFV -> false
    else -> true
}

/**
 * Only a rich embed carries text anyone wrote markdown into. Everything else was scraped from the
 * page being previewed, so it renders exactly as it arrived — a `*` in an article's summary is a
 * bullet character, not emphasis.
 */
private fun MessageEmbed.parseDescription(
    text: String,
    improvedMarkdown: Boolean,
): MutableList<Node<MessageRenderContext>> = when {
    type != EmbedType.RICH -> arrayListOf(TextNode(text))
    improvedMarkdown -> UI_THREAD_DESCRIPTION_IMPROVED_PARSER.parseText(text)
    else -> UI_THREAD_VALUES_PARSER.parseText(text)
}

private fun MessageEmbed.parseTitle(text: String): MutableList<Node<MessageRenderContext>> = when {
    type != EmbedType.RICH -> arrayListOf(TextNode(text))
    url.isNullOrEmpty() -> UI_THREAD_TITLES_PARSER.parseText(text)
    else -> UI_THREAD_TITLES_NO_LINKS_PARSER.parseText(text)
}

/**
 * Long-pressing any embed text copies it, matching the reference's embed rows.
 *
 * The listener returns false so the row's own long-press (the message action sheet) still runs —
 * copying is additive, not a replacement.
 */
private fun TextView.copyTextOnLongPress() {
    setOnLongClickListener { view ->
        val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(text, text))
        false
    }
}

private fun View.bindUrlOnClick(url: String?, mask: String?) {
    if (url == null) {
        setOnClickListener(null)
        return
    }
    setOnClickListener { UriHandler.handleOrUntrusted(it.context, url, mask) }
}
