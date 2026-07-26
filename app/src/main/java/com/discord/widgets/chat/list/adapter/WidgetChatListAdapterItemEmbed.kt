package com.discord.widgets.chat.list.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.api.message.embed.EmbedAuthor
import com.discord.api.message.embed.EmbedField
import com.discord.api.message.embed.EmbedFooter
import com.discord.api.message.embed.EmbedImage
import com.discord.api.message.embed.EmbedThumbnail
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.EmbedVideo
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.role.GuildRole
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.embed.RenderableEmbedMedia
import com.discord.models.member.GuildMember
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
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
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.media.WidgetMedia
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.card.MaterialCardView
import rx.Observable
import rx.Subscription
import kotlin.math.min
import kotlin.math.roundToInt

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

    private val highlightedBackground: View = itemView.findViewById(R.id.chat_list_adapter_item_highlighted_bg)
    private val gutterBackground: View = itemView.findViewById(R.id.chat_list_adapter_item_gutter_bg)
    private val containerCard: MaterialCardView = itemView.findViewById(R.id.chat_list_item_embed_container_card)
    private val content: ConstraintLayout = itemView.findViewById(R.id.chat_list_item_embed_content)
    private val divider: View = itemView.findViewById(R.id.chat_list_item_embed_divider)
    private val provider: TextView = itemView.findViewById(R.id.chat_list_item_embed_provider)
    private val authorIcon: SimpleDraweeView = itemView.findViewById(R.id.chat_list_item_embed_author_icon)
    private val authorText: TextView = itemView.findViewById(R.id.chat_list_item_embed_author_text)
    private val title: LinkifiedTextView = itemView.findViewById(R.id.chat_list_item_embed_title)
    private val description: LinkifiedTextView = itemView.findViewById(R.id.chat_list_item_embed_description)
    private val fields: LinearLayout = itemView.findViewById(R.id.chat_list_item_embed_fields)
    private val imageContainer: CardView = itemView.findViewById(R.id.embed_image_container)
    private val image: SimpleDraweeView = itemView.findViewById(R.id.chat_list_item_embed_image)
    private val imageIcons: ImageView = itemView.findViewById(R.id.chat_list_item_embed_image_icons)
    private val thumbnail: SimpleDraweeView = itemView.findViewById(R.id.chat_list_item_embed_image_thumbnail)
    private val footerIcon: SimpleDraweeView = itemView.findViewById(R.id.chat_list_item_embed_footer_icon)
    private val footerText: TextView = itemView.findViewById(R.id.chat_list_item_embed_footer_text)
    private val spoilerCover: View = itemView.findViewById(R.id.chat_list_item_embed_spoiler)
    private val inlineMedia: InlineMediaView = itemView.findViewById(R.id.embed_inline_media)

    private val userSettings = StoreStream.getUserSettings()
    private val embedTinyIconSize = itemView.resources.getDimensionPixelSize(R.dimen.embed_tiny_icon_size)
    private val embedThumbnailMaxSize =
        itemView.resources.getDimensionPixelSize(R.dimen.embed_thumbnail_max_size)
    private val maxEmbedImageWidth = EmbedMedia.computeMaximumImageWidthPx(itemView.context)

    /**
     * One detector for the whole row, so rebinding an unchanged embed does not re-issue four image
     * requests and flicker the card.
     */
    private val imageChangeDetector = MGImages.DistinctChangeDetector()

    private var nameSubscription: Subscription? = null
    override val subscription: Subscription? get() = nameSubscription

    init {
        listOf<TextView>(provider, title, authorText, description, footerText)
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
        configureCellHighlight(entry.message, highlightedBackground, gutterBackground)

        val model = Model(
            embedEntry = entry,
            parsedDescription = entry.embed.description?.let { UI_THREAD_VALUES_PARSER.parseText(it) },
            parsedFields = parseFields(entry.embed.fields),
        )
        configureUI(model)

        // Second pass: only an embed that actually mentions a user, role or channel needs names,
        // and only then is it worth touching the stores.
        nameSubscription = null
        observeResolvedNames(model)?.take(1)?.ui()?.appSubscribe(
            errorClass = WidgetChatListAdapterItemEmbed::class.java,
            subscriptionHandler = { nameSubscription = it },
        ) { resolved -> configureUI(resolved) }
    }

    override fun onPause() = inlineMedia.onPause()

    override fun onResume() = inlineMedia.onResume()

    // region binding

    private fun configureUI(model: Model) {
        val entry = model.embedEntry
        val embed = entry.embed
        val renderContext = model.createRenderContext(itemView.context, adapter.eventHandler)

        if (model.isSpoilerHidden) {
            spoilerCover.fadeIn(SPOILER_FADE_IN_MS, additionalAnimation = { it.scaleX(1f).scaleY(1f) })
        } else {
            spoilerCover.fadeOut(SPOILER_FADE_OUT_MS, additionalAnimation = { it.scaleX(0.9f).scaleY(0.9f) })
        }
        spoilerCover.setOnClickListener {
            if (adapter.data.isSpoilerClickAllowed) {
                StoreStream.getMessageState().revealSpoilerEmbed(entry.message.id, entry.embedIndex)
            } else {
                adapter.eventHandler.onMessageClicked(entry.message, entry.isThreadStarterMessage)
            }
        }

        try {
            if (EmbedMedia.isInlineEmbed(embed)) {
                content.visibility = View.GONE
                divider.visibility = View.GONE
                thumbnail.visibility = View.GONE
                inlineMedia.visibility = View.VISIBLE
                configureInlineEmbed(embed, entry.autoPlayGifs)
                containerCard.setCardBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(itemView.context, R.color.transparent),
                )
                containerCard.strokeWidth = 0
            } else {
                content.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                inlineMedia.visibility = View.GONE
                configureEmbedDivider(embed.color.takeUnless { model.isSpoilerHidden })
                configureEmbedProvider(embed)
                configureEmbedAuthor(embed.author)
                configureEmbedTitle(model, renderContext)
                configureEmbedDescription(model, renderContext)
                configureEmbedThumbnail(embed)
                configureEmbedImage(embed)
                configureEmbedFields(model, renderContext)
                configureFooter(embed.footer, embed.timestamp)
                containerCard.setCardBackgroundColor(
                    ThemedColor.resolve(itemView.context, R.attr.colorBackgroundSecondary),
                )
                containerCard.strokeWidth =
                    containerCard.resources.getDimensionPixelSize(R.dimen.chat_embed_card_stroke_width)
            }
            itemView.visibility = View.VISIBLE
        } catch (failure: Exception) {
            // A malformed embed must cost one row, not the whole list.
            itemView.visibility = View.GONE
            Log.e(LOG_TAG, "Unable to render embed.", failure)
        }
    }

    private fun configureInlineEmbed(embed: MessageEmbed, autoPlayGifs: Boolean) {
        val preview = EmbedMedia.previewImage(embed)
        val width = preview?.width ?: 0
        val height = preview?.height ?: 0
        if (width > 0 && height > 0) {
            val (scaledWidth, scaledHeight) = EmbedMedia.calculateScaledSize(
                actualWidth = width,
                actualHeight = height,
                maxWidth = maxEmbedImageWidth,
                maxHeight = MAX_EMBED_IMAGE_HEIGHT_PX,
                resources = inlineMedia.resources,
            )
            inlineMedia.updateUIWithEmbed(embed, scaledWidth, scaledHeight, autoPlayGifs)
        } else {
            inlineMedia.updateUIWithEmbed(embed, autoPlayGifs = autoPlayGifs)
        }
        // An inline embed is nothing but its media, so tapping it opens the fullscreen viewer —
        // unlike the carded embed's picture below, which follows the embed's own link.
        inlineMedia.setOnClickListener { view -> WidgetMedia.launch(view.context, embed) }
    }

    private fun configureEmbedDivider(color: Int?) {
        divider.setBackgroundColor(
            if (color != null) {
                ColorUtils.setAlphaComponent(color, 255)
            } else {
                ThemedColor.resolve(divider.context, R.attr.colorBackgroundModifierAccent)
            },
        )
    }

    private fun configureEmbedProvider(embed: MessageEmbed) {
        val embedProvider = embed.provider
        if (embedProvider == null) {
            provider.visibility = View.GONE
            return
        }
        provider.text = embedProvider.name
        provider.bindUrlOnClick(embedProvider.url, embedProvider.name)
        provider.visibility = View.VISIBLE
    }

    private fun configureEmbedAuthor(author: EmbedAuthor?) {
        if (author != null) {
            authorText.text = author.name
            authorText.bindUrlOnClick(author.url, author.name)
            authorText.visibility = View.VISIBLE
        } else {
            authorText.visibility = View.GONE
        }

        val iconUrl = author?.proxyIconUrl
        if (iconUrl == null) {
            authorIcon.visibility = View.GONE
            return
        }
        authorIcon.visibility = View.VISIBLE
        setScaledImage(authorIcon, embedTinyIconSize, embedTinyIconSize, embedTinyIconSize, iconUrl)
    }

    private fun configureEmbedTitle(model: Model, renderContext: MessageRenderContext) {
        val embed = model.embedEntry.embed
        val titleText = embed.title
        if (titleText == null) {
            title.visibility = View.GONE
            return
        }
        val embedIndex = model.embedEntry.embedIndex
        val ast = UI_THREAD_TITLES_PARSER.parseText(titleText)
        model.preprocessorFor(embedIndex, PART_TITLE).process(ast)
        title.setDraweeSpanStringBuilder(
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
        title.setTextColor(
            ThemedColor.resolve(
                title.context,
                if (embed.url != null) R.attr.colorTextLink else R.attr.primary_100,
            ),
        )
        title.bindUrlOnClick(embed.url, embed.title)
        title.visibility = View.VISIBLE
    }

    private fun configureEmbedDescription(model: Model, renderContext: MessageRenderContext) {
        val parsed = model.parsedDescription
        if (parsed == null) {
            description.visibility = View.GONE
            return
        }
        val messageId = model.embedEntry.message.id
        val embedIndex = model.embedEntry.embedIndex
        model.preprocessorFor(embedIndex, PART_DESCRIPTION).process(parsed)
        description.setDraweeSpanStringBuilder(
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
        description.visibility = View.VISIBLE
    }

    /**
     * Rebuilds the field stack, reusing the rows already inflated.
     *
     * Field rows are recycled by position rather than cleared and re-inflated, because an embed with
     * a dozen fields is common and every row carries two drawee-span text views.
     */
    private fun configureEmbedFields(model: Model, renderContext: MessageRenderContext) {
        val parsedFields = model.parsedFields
        val existingRows = (0 until fields.childCount).map(fields::getChildAt)
        fields.removeAllViews()
        if (parsedFields == null) {
            fields.visibility = View.GONE
            return
        }
        fields.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(fields.context)
        val messageId = model.embedEntry.message.id
        val embedIndex = model.embedEntry.embedIndex

        parsedFields.forEachIndexed { index, field ->
            val row = existingRows.getOrNull(index)
                ?: inflater.inflate(R.layout.widget_chat_list_adapter_item_embed_field, fields, false)
            val name = row.findViewById<SimpleDraweeSpanTextView>(R.id.chat_list_item_embed_field_name)
            val value = row.findViewById<SimpleDraweeSpanTextView>(R.id.chat_list_item_embed_field_value)

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

            name.setDraweeSpanStringBuilder(render(field.parsedName, "$PART_FIELD_NAME:$index"))
            value.setDraweeSpanStringBuilder(render(field.parsedValue, "$PART_FIELD_VALUE:$index"))
            fields.addView(row)
        }
    }

    private fun configureEmbedThumbnail(embed: MessageEmbed) {
        val hasThumbnailLayout = embed.type == EmbedType.LINK ||
            embed.type == EmbedType.HTML ||
            embed.type == EmbedType.RICH
        val embedThumbnail = embed.thumbnail
        if (!hasThumbnailLayout || embedThumbnail == null) {
            thumbnail.visibility = View.GONE
            return
        }
        thumbnail.visibility = View.VISIBLE
        setScaledImage(
            view = thumbnail,
            maxWidth = embedThumbnailMaxSize,
            width = embedThumbnail.width ?: 0,
            height = embedThumbnail.height ?: 0,
            imageProxyUrl = embedThumbnail.proxyUrl,
        )
    }

    private fun configureEmbedImage(embed: MessageEmbed) {
        val preview = EmbedMedia.previewImage(embed)
        if (preview == null) {
            imageContainer.visibility = View.GONE
            return
        }
        val renderMedia = shouldRenderMedia()
        val hasIntrinsicSize = (preview.width ?: 0) > 0 && (preview.height ?: 0) > 0
        if (renderMedia && hasIntrinsicSize) {
            setScaledImage(
                view = image,
                maxWidth = maxEmbedImageWidth,
                width = preview.width ?: 0,
                height = preview.height ?: 0,
                imageProxyUrl = preview.url,
                minWidth = maxEmbedImageWidth / 2,
            )
            imageContainer.visibility = View.VISIBLE
        } else {
            imageContainer.visibility = View.GONE
        }
        imageIcons.visibility =
            if (EmbedMedia.isPlayable(embed) && renderMedia) View.VISIBLE else View.GONE
        image.setOnClickListener { view ->
            val externalUrl = EmbedMedia.externalOpenUrl(embed)
            if (externalUrl != null) UriHandler.handleOrUntrusted(view.context, externalUrl, embed.title)
        }
    }

    private fun configureFooter(footer: EmbedFooter?, timestamp: UtcDateTime?) {
        val iconUrl = footer?.proxyIconUrl
        if (footer?.text.isNullOrEmpty()) {
            footerIcon.visibility = View.GONE
        } else {
            footerIcon.visibility = if (iconUrl != null) View.VISIBLE else View.GONE
            setScaledImage(footerIcon, embedTinyIconSize, embedTinyIconSize, embedTinyIconSize, iconUrl)
        }

        val readableTime = timestamp?.let {
            TimeUtils.toReadableTimeString(footerText.context, it.dateTimeMillis, adapter.clock)
        }
        footerText.text = when {
            footer != null && readableTime != null -> "${footer.text} | $readableTime"
            footer != null -> footer.text
            else -> readableTime
        }
        footerText.visibility = if (footerText.text.isNotEmpty()) View.VISIBLE else View.GONE
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
        val (scaledWidth, scaledHeight) = EmbedMedia.calculateScaledSize(
            actualWidth = width,
            actualHeight = height,
            maxWidth = maxWidth,
            maxHeight = maxWidth,
            resources = inlineMedia.resources,
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
            EmbedMedia.previewUrls(imageProxyUrl, scaledWidth, scaledHeight),
            changeDetector = imageChangeDetector,
        )
    }

    private fun shouldRenderMedia(): Boolean =
        userSettings.getIsEmbedMediaInlined() && userSettings.getIsRenderEmbedsEnabled()

    private fun parseFields(fields: List<EmbedField>?): List<ParsedField>? = fields?.map {
        ParsedField(
            parsedName = UI_THREAD_TITLES_PARSER.parseText(it.name),
            parsedValue = UI_THREAD_VALUES_PARSER.parseText(it.value),
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
        ) { myId: Long,
            channelNames: Map<Long, String>,
            userNames: Map<Long, String>,
            roles: Map<Long, GuildRole> ->
            model.copy(
                channelNames = channelNames,
                userNames = userNames,
                roles = roles,
                myId = myId,
            )
        }.computationLatest().distinctUntilChanged()
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

private const val LOG_TAG = "WidgetChatListAdapterItemEmbed"

private const val PART_TITLE = "title"
private const val PART_DESCRIPTION = "desc"
private const val PART_FIELD_NAME = "f_name"
private const val PART_FIELD_VALUE = "f_value"

private const val SPOILER_FADE_IN_MS = 50L
private const val SPOILER_FADE_OUT_MS = 200L

/** Embed images are never drawn taller than this, however tall the source is. */
private val MAX_EMBED_IMAGE_HEIGHT_PX = EmbedMedia.dpToPixels(360)

/**
 * Titles, field names and the message body: no masked links (a bot could otherwise disguise a link
 * as arbitrary text) and no entity mentions.
 */
private val UI_THREAD_TITLES_PARSER =
    DiscordParser.createParser(allowMaskedLinks = false, supportEntityMentions = false)

/** Descriptions and field values, which may legitimately carry both. */
private val UI_THREAD_VALUES_PARSER =
    DiscordParser.createParser(allowMaskedLinks = true, supportEntityMentions = true)

private fun Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>.parseText(
    text: String,
): MutableList<Node<MessageRenderContext>> = ArrayList(parse(text, MessageParseState.initialState))

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

/**
 * The embed/attachment media rules shared by the embed and attachment rows: which picture an embed
 * previews with, whether it plays, how large it may be drawn, and which CDN variants to request.
 *
 * The reference keeps these in `com.discord.utilities.embed.EmbedResourceUtils` alongside a
 * `FileType` enum. That package has not been reconstructed yet and is outside this change's
 * ownership, so the rules live here next to their only two callers; they should move when it lands.
 * See `docs/parity-audit/followups.md`.
 */
internal object EmbedMedia {

    /** Widest image the CDN is asked for, regardless of screen size. */
    private const val MAX_IMAGE_SIZE = 1440

    /** Embed and attachment media never grows taller than this, however tall the source is. */
    val MAX_IMAGE_VIEW_HEIGHT_PX: Int by lazy { dpToPixels(320) }

    private val PLAYABLE_EMBED_TYPES = listOf(EmbedType.VIDEO, EmbedType.GIFV)

    /**
     * Which of an embed's three media slots supplies its still preview.
     *
     * Videos and GIFVs preview from their thumbnail and fall back to the video itself; articles,
     * images and tweets only ever preview from the thumbnail; link and HTML embeds have no preview
     * at all; everything else previews from the image slot.
     */
    fun previewImage(embed: MessageEmbed): RenderableEmbedMedia? = when (embed.type) {
        EmbedType.ARTICLE, EmbedType.IMAGE, EmbedType.TWEET -> embed.thumbnail?.toRenderable()
        EmbedType.VIDEO, EmbedType.GIFV ->
            embed.thumbnail?.toRenderable() ?: embed.video?.toRenderable()
        EmbedType.LINK, EmbedType.HTML -> null
        else -> embed.image?.toRenderable()
    }

    fun isPlayable(embed: MessageEmbed): Boolean = embed.type in PLAYABLE_EMBED_TYPES

    /**
     * Whether the embed is nothing but media, and so renders without a card.
     *
     * A GIFV always qualifies. An image or video qualifies only when it carries neither an author
     * nor a title — anything with those has text to show and needs the card.
     */
    fun isInlineEmbed(embed: MessageEmbed): Boolean {
        if (previewImage(embed) == null && embed.video == null) return false
        return when (embed.type) {
            EmbedType.IMAGE, EmbedType.VIDEO -> embed.author == null && embed.title == null
            EmbedType.GIFV -> true
            else -> false
        }
    }

    /**
     * Where tapping the embed's picture should take the user, or null to open the media viewer.
     *
     * Video embeds open the source video; file embeds open the provider that served them.
     */
    fun externalOpenUrl(embed: MessageEmbed): String? = when (embed.type) {
        EmbedType.VIDEO -> embed.video?.url
        EmbedType.FILE -> embed.provider?.url
        else -> null
    }

    /** True for media that moves: a GIFV, or an image whose URL is a `.gif`. */
    fun isAnimated(embedType: EmbedType?, previewImageUri: String?): Boolean = when (embedType) {
        EmbedType.GIFV -> true
        EmbedType.IMAGE -> previewImageUri?.lowercase()?.contains(".gif") == true
        else -> false
    }

    /**
     * The CDN variants to try for a preview, best first.
     *
     * A still request is preferred because it is far smaller, but a URL that is already a local
     * resource or an animating `.gif` is requested as-is; the unsized original is always kept as the
     * fallback in case the media proxy declines the resize.
     */
    fun previewUrls(
        originalUrl: String,
        width: Int,
        height: Int,
        shouldAnimate: Boolean = true,
    ): List<String> {
        val sized = "$originalUrl?width=$width&height=$height"
        val keepAnimated = shouldAnimate &&
            (originalUrl.startsWith(FILE_SCHEME) || originalUrl.endsWith(".gif"))
        return if (keepAnimated) {
            listOf(sized)
        } else {
            listOf("$sized&format=${IconUtils.staticImageExtension}", sized)
        }
    }

    /** How wide media may be drawn: the chat column, capped at the largest size the CDN serves. */
    fun computeMaximumImageWidthPx(context: Context): Int {
        val resources = context.resources
        val gutters = resources.getDimensionPixelSize(R.dimen.uikit_guideline_chat) +
            resources.getDimensionPixelSize(R.dimen.chat_cell_horizontal_spacing_total)
        return min(MAX_IMAGE_SIZE, resources.displayMetrics.widthPixels - gutters)
    }

    /**
     * Fits an image of [actualWidth] x [actualHeight] into the [maxWidth] x [maxHeight] box while
     * preserving its aspect ratio.
     *
     * Three outcomes. An image that would occupy more than half the box in either direction is
     * scaled *down* to fit it exactly, with the longer side pinned to the box and the other side
     * clamped afterwards. A narrow image, when a [minWidth] is given, is scaled *up* to that width
     * and then clamped to the height. Anything else is drawn at its intrinsic size in density pixels
     * — a small image is not stretched to fill the column.
     */
    fun calculateScaledSize(
        actualWidth: Int,
        actualHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        resources: Resources,
        minWidth: Int = 0,
    ): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val densityWidth = density * actualWidth
        val densityHeight = density * actualHeight
        val aspectRatio = if (actualHeight > 0) actualWidth.toFloat() / actualHeight else 0f

        val needsDownscale = densityWidth > maxWidth / 2f || densityHeight > maxHeight / 2f
        val needsUpscale = minWidth > 0 && actualWidth < minWidth

        return when {
            needsDownscale -> {
                val isLandscape = actualWidth > actualHeight
                var width = if (isLandscape) maxWidth.toFloat() else maxHeight * aspectRatio
                var height = if (isLandscape) maxWidth / aspectRatio else maxHeight.toFloat()
                if (isLandscape && height > maxHeight) {
                    width *= maxHeight / height
                    height = maxHeight.toFloat()
                } else if (!isLandscape && width > maxWidth) {
                    height *= maxWidth / width
                    width = maxWidth.toFloat()
                }
                width.roundToInt() to height.roundToInt()
            }
            needsUpscale -> {
                var width = minWidth.toFloat()
                var height = minWidth.toFloat() / actualWidth * actualHeight
                if (height > maxHeight) {
                    width *= maxHeight / height
                    height = maxHeight.toFloat()
                }
                width.roundToInt() to height.roundToInt()
            }
            else -> densityWidth.roundToInt() to densityHeight.roundToInt()
        }
    }

    /** The icon shown for a non-previewable attachment, chosen from its filename extension. */
    fun getFileDrawable(filename: String?): Int {
        val extension = filename?.substringAfterLast('.', "").orEmpty()
        return FileType.fromExtension(extension)?.drawableRes ?: R.drawable.ic_file_unknown
    }

    fun dpToPixels(dp: Int): Int = (dp * Resources.getSystem().displayMetrics.density).roundToInt()

    private const val FILE_SCHEME = "res:///"

    private fun EmbedImage.toRenderable() = RenderableEmbedMedia(proxyUrl ?: url, width, height)
    private fun EmbedThumbnail.toRenderable() = RenderableEmbedMedia(proxyUrl ?: url, width, height)
    private fun EmbedVideo.toRenderable() = RenderableEmbedMedia(proxyUrl ?: url, width, height)

    /**
     * Attachment kinds that get their own icon in the file card.
     *
     * Declaration order is the matching order and it is load-bearing: `js` is code before it is web
     * code, and `xls` is a spreadsheet before it is web code.
     */
    private enum class FileType(pattern: String, val drawableRes: Int) {
        VIDEO("(?:avi|flv|wmv|mov|mp4)$", R.drawable.ic_file_video),
        ACROBAT("pdf$", R.drawable.ic_file_acrobat),
        AE("ae$", R.drawable.ic_file_ae),
        SKETCH("sketch$", R.drawable.ic_file_sketch),
        AI("ai$", R.drawable.ic_file_ai),
        ARCHIVE("(?:rar|zip|7z|tar|tar\\.gz)$", R.drawable.ic_file_archive),
        CODE(
            "(?:c\\+\\+|cpp|cc|c|h|hpp|mm|m|json|js|rb|rake|py|asm|fs|pyc|dtd|cgi|bat|rss|" +
                "java|graphml|idb|lua|o|gml|prl|sls|conf|cmake|make|sln|vbe|cxx|wbf|vbs|" +
                "r|wml|php|bash|applescript|fcgi|yaml|ex|exs|sh|ml|actionscript)$",
            R.drawable.ic_file_code,
        ),
        DOCUMENT("(?:txt|rtf|doc|docx|md|pages|ppt|pptx|pptm|key|log)$", R.drawable.ic_file_document),
        SPREADSHEET("(?:xls|xlsx|numbers|csv|xliff)$", R.drawable.ic_file_spreadsheet),
        WEBCODE("(?:html|xhtml|htm|js|xml|xls|xsd|css|styl)$", R.drawable.ic_file_webcode),
        ;

        private val regex = Regex(pattern)

        companion object {
            fun fromExtension(extension: String): FileType? =
                values().firstOrNull { it.regex.containsMatchIn(extension) }
        }
    }
}
