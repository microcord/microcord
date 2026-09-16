package com.discord.widgets.botuikit.views.v2

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.MediaItemType
import com.discord.api.botuikit.SeparatorComponent
import com.discord.api.botuikit.UnfurledMediaItem
import com.discord.api.message.attachment.MessageAttachment
import com.discord.databinding.WidgetBotUiContainerComponentBinding
import com.discord.databinding.WidgetBotUiFileComponentBinding
import com.discord.databinding.WidgetBotUiSectionComponentBinding
import com.discord.databinding.WidgetBotUiSeparatorComponentBinding
import com.discord.databinding.WidgetBotUiTextDisplayComponentBinding
import com.discord.databinding.WidgetBotUiThumbnailComponentBinding
import com.discord.models.botuikit.ContainerMessageComponent
import com.discord.models.botuikit.ButtonMessageComponent
import com.discord.models.botuikit.FileMessageComponent
import com.discord.models.botuikit.MediaGalleryMessageComponent
import com.discord.models.botuikit.SectionMessageComponent
import com.discord.models.botuikit.SeparatorMessageComponent
import com.discord.models.botuikit.TextDisplayMessageComponent
import com.discord.models.botuikit.ThumbnailMessageComponent
import com.discord.models.botuikit.childError
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MediaProxyUrl
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageTextRenderer
import com.discord.utilities.textprocessing.node.ZeroSpaceWidthNode
import com.discord.utilities.textprocessing.spans.VerticalPaddingSpan
import com.discord.utilities.textprocessing.spans.QuoteSpan
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.botuikit.ComponentProvider
import com.discord.widgets.botuikit.replaceViews
import com.discord.widgets.botuikit.views.ComponentActionListener
import com.discord.widgets.botuikit.views.ComponentView
import com.discord.widgets.chat.list.entries.BotUiComponentEntry
import com.discord.widgets.media.MediaViewerContext
import com.discord.widgets.media.WidgetMedia
import com.discord.widgets.media.mediaVideoPreviewUrl

class TextDisplayComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), ComponentView<TextDisplayMessageComponent> {
    override fun type() = ComponentType.TEXT_DISPLAY

    override fun configure(
        component: TextDisplayMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val entry = componentActionListener.componentEntry ?: return
        val id = component.id ?: component.index
        val visible = entry.messageState?.visibleSpoilerEmbedMap?.get(id).orEmpty()
            .mapNotNull { it.removePrefix(SPOILER_KEY_PREFIX).toIntOrNull() }
            .toSet()
        val binding = WidgetBotUiTextDisplayComponentBinding.bind(this)
        val rendered = MessageTextRenderer.render(
            message = entry.message.copy(content = component.content),
            view = binding.content,
            messageState = StoreMessageState.State(visibleSpoilerNodeIndices = visible),
            parserOptions = DiscordParser.ParserOptions.DEFAULT,
            onSpoilerClicked = { spoiler ->
                StoreStream.getMessageState().revealSpoilerEmbedData(
                    entry.message.id,
                    id,
                    "$SPOILER_KEY_PREFIX${spoiler.id}",
                )
            },
        )
        binding.content.setDraweeSpanStringBuilder(compactComponentText(rendered.text))
        binding.content.movementMethod = LinkMovementMethod.getInstance()
    }

    companion object {
        fun inflate(context: Context, root: ViewGroup) = LayoutInflater.from(context).inflate(
            R.layout.widget_bot_ui_text_display_component,
            root,
            false,
        ) as TextDisplayComponentView
    }
}

/** The component host owns outer spacing; retain paragraph spacing inside the text. */
internal fun compactComponentText(text: DraweeSpanStringBuilder): DraweeSpanStringBuilder = text.apply {
    val hasTouchSentinel = endsWith(ZeroSpaceWidthNode.ZERO_WIDTH_SPACE)
    if (hasTouchSentinel) delete(length - 1, length)
    while (isNotEmpty() && last() == '\n') delete(length - 1, length)
    while (isNotEmpty() && first() == '\n') delete(0, 1)
    val blockStarts = getSpans(0, length, QuoteSpan::class.java).map { getSpanStart(it) }.toSet() + 0
    getSpans(0, length, VerticalPaddingSpan::class.java).forEach { span ->
        val start = getSpanStart(span)
        if (start in blockStarts) {
            val end = getSpanEnd(span)
            val flags = getSpanFlags(span)
            removeSpan(span)
            setSpan(VerticalPaddingSpan(0, span.paddingBottom), start, end, flags)
        }
    }
    if (hasTouchSentinel) append(ZeroSpaceWidthNode.ZERO_WIDTH_SPACE)
}

class SectionComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), ComponentView<SectionMessageComponent> {
    override fun type() = ComponentType.SECTION

    override fun configure(
        component: SectionMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val binding = WidgetBotUiSectionComponentBinding.bind(this)
        binding.components.replaceViews(component.components.mapIndexedNotNull { index, child ->
            componentProvider.getConfiguredComponentView(componentActionListener, child, binding.components, index) as? View
        })
        binding.accessory.replaceViews(
            listOfNotNull(
                componentProvider.getConfiguredComponentView(
                    componentActionListener,
                    component.accessory,
                    binding.accessory,
                    0,
                ) as? View,
            ),
        )
        // Thumbnails align with the first text line; buttons remain vertically centered.
        binding.accessory.layoutParams = if (component.accessory is ButtonMessageComponent) {
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        } else {
            LinearLayout.LayoutParams(dpToPixels(ACCESSORY_SIZE_DP), dpToPixels(ACCESSORY_SIZE_DP))
        }.apply {
            marginStart = dpToPixels(ACCESSORY_GAP_DP)
            gravity = if (component.accessory is ButtonMessageComponent) Gravity.CENTER_VERTICAL else Gravity.TOP
        }

        // A section carries the same failure banner an action row does: its accessory can be a
        // button, and a failed interaction has to be reported under the row it was started from.
        val error = component.childError()
        binding.errorRow.root.visibility = if (error == null) View.GONE else View.VISIBLE
        if (error != null) {
            binding.errorRow.viewInteractionFailedLabelMessage.text =
                error.errorMessage ?: resources.getString(R.string.application_command_failed)
        }
    }

    companion object {
        private const val ACCESSORY_SIZE_DP = 80
        private const val ACCESSORY_GAP_DP = 12

        fun inflate(context: Context, root: ViewGroup) = LayoutInflater.from(context).inflate(
            R.layout.widget_bot_ui_section_component,
            root,
            false,
        ) as SectionComponentView
    }
}

class ThumbnailComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), ComponentView<ThumbnailMessageComponent> {
    override fun type() = ComponentType.THUMBNAIL

    override fun configure(
        component: ThumbnailMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val entry = componentActionListener.componentEntry ?: return
        configureMedia(component, entry.message.id, entry.messageState, null, entry.toMediaViewerContext())
    }

    /**
     * Draws one piece of unfurled media, whether it came from a thumbnail or from a cell of a
     * media gallery.
     *
     * [key] separates gallery cells that share the component's spoiler id; a thumbnail, which owns
     * its id outright, passes null.
     */
    internal fun configureMedia(
        component: ThumbnailMessageComponent,
        messageId: Long,
        state: StoreMessageState.State?,
        key: String?,
        viewerContext: MediaViewerContext?,
    ) {
        val binding = WidgetBotUiThumbnailComponentBinding.bind(this)
        val id = component.id ?: component.index
        val revealed = if (key == null) state?.visibleSpoilerEmbedMap?.containsKey(id) == true
        else key in state?.visibleSpoilerEmbedMap?.get(id).orEmpty()
        val mediaType = component.media.mediaType
        val attachment = component.media.toAttachment(id.toLong())
        val isVideo = mediaType == MediaItemType.VIDEO
        // Media the unfurler has not resolved still holds its cell, so the shape a bot laid out
        // survives the wait. There is nothing to load into it, so it stays an empty tile.
        val isPlaceholder = mediaType == MediaItemType.PLACEHOLDER
        binding.image.visibility = if (isPlaceholder) View.INVISIBLE else View.VISIBLE
        if (!isPlaceholder) {
            binding.image.setImageURI(
                if (isVideo) mediaVideoPreviewUrl(attachment.proxyUrl, attachment.width, attachment.height)
                // An unfurled item is served by the same proxy an attachment is, so it needs the
                // same rewrites: AVIF transcoded to something older devices decode, and an animated
                // WebP asked for explicitly instead of arriving as a single frame.
                else MediaProxyUrl.forAttachment(attachment, attachment.proxyUrl),
            )
        }
        binding.image.contentDescription = if (isVideo) {
            listOfNotNull(context.getString(R.string.play_full_video), component.description).joinToString(". ")
        } else component.description
        var hidden = component.spoiler && !revealed
        binding.mediaSpoiler.visibility = if (hidden) View.VISIBLE else View.GONE
        binding.play.visibility = if (isVideo && !hidden) View.VISIBLE else View.GONE
        binding.image.importantForAccessibility = if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        binding.alt.visibility =
            if (!hidden && !isPlaceholder && !component.description.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.mediaSpoiler.setOnClickListener {
            hidden = false
            binding.mediaSpoiler.visibility = View.GONE
            binding.play.visibility = if (isVideo) View.VISIBLE else View.GONE
            binding.image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            binding.alt.visibility =
                if (isPlaceholder || component.description.isNullOrBlank()) View.GONE else View.VISIBLE
            if (key == null) StoreStream.getMessageState().revealSpoilerEmbed(messageId, id)
            else StoreStream.getMessageState().revealSpoilerEmbedData(messageId, id, key)
        }
        val openMedia = View.OnClickListener {
            when {
                hidden -> binding.mediaSpoiler.performClick()
                // Nothing to open yet: the tile is holding a place for media that has not resolved.
                isPlaceholder -> Unit
                viewerContext == null -> WidgetMedia.launch(context, attachment)
                else -> WidgetMedia.launch(context, attachment, viewerContext)
            }
        }
        binding.image.setOnClickListener(openMedia)
        setOnClickListener(openMedia)
    }

    companion object {
        fun inflate(context: Context, root: ViewGroup) = LayoutInflater.from(context).inflate(
            R.layout.widget_bot_ui_thumbnail_component,
            root,
            false,
        ) as ThumbnailComponentView
    }
}

class MediaGalleryComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), ComponentView<MediaGalleryMessageComponent> {
    override fun type() = ComponentType.MEDIA_GALLERY
    private var boundComponent: MediaGalleryMessageComponent? = null
    private var boundEntry: BotUiComponentEntry? = null
    private var laidOutWidth = 0

    override fun configure(
        component: MediaGalleryMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val entry = componentActionListener.componentEntry ?: return
        boundComponent = component
        boundEntry = entry
        layoutGallery(parentContentWidth() ?: width.takeIf { it > 0 } ?: provisionalWidth())
        post {
            val targetWidth = parentContentWidth() ?: width
            if (targetWidth > 0 && targetWidth != laidOutWidth) layoutGallery(targetWidth)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && width != oldWidth && width != laidOutWidth) layoutGallery(width)
    }

    private fun provisionalWidth(): Int = dpToPixels(160)

    private fun parentContentWidth(): Int? = (parent as? ViewGroup)?.let { parent ->
        (parent.width - parent.paddingLeft - parent.paddingRight).takeIf { it > dpToPixels(160) }
    }

    private fun layoutGallery(availableWidth: Int) {
        val component = boundComponent ?: return
        val entry = boundEntry ?: return
        if (availableWidth <= 0) return
        laidOutWidth = availableWidth
        // A lone item keeps its own size, so its pixel dimensions are read as density-independent
        // ones: an image smaller than the row renders small rather than being blown up to fill it.
        val single = component.items.singleOrNull()?.media
        val grid = MediaGalleryLayout.layout(
            count = component.items.size,
            width = availableWidth,
            gap = dpToPixels(MediaGalleryLayout.GAP_DP),
            singleMaxHeight = dpToPixels(MediaGalleryLayout.SINGLE_MAX_HEIGHT_DP),
            sourceWidth = dpToPixels(single?.width ?: 0),
            sourceHeight = dpToPixels(single?.height ?: 0),
        )
        layoutParams = layoutParams.apply {
            width = availableWidth
            height = grid.height
        }
        val views = grid.cells.mapIndexed { index, cell ->
            val item = component.items[index]
            val view = (getChildAt(index) as? ThumbnailComponentView)
                ?: ThumbnailComponentView.inflate(context, this)
            view.layoutParams = FrameLayout.LayoutParams(cell.width, cell.height).apply {
                leftMargin = cell.left
                topMargin = cell.top
            }
            view.configureMedia(
                ThumbnailMessageComponent(
                    ComponentType.THUMBNAIL,
                    component.index,
                    component.id,
                    item.media,
                    item.description,
                    item.spoiler == true,
                ),
                entry.message.id,
                entry.messageState,
                "$GALLERY_KEY_PREFIX$index",
                entry.toMediaViewerContext(),
            )
            view
        }
        replaceViews(views)
    }

    companion object {
        fun inflate(context: Context, root: ViewGroup) = LayoutInflater.from(context).inflate(
            R.layout.widget_bot_ui_media_gallery_component,
            root,
            false,
        ) as MediaGalleryComponentView
    }
}

class FileComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), ComponentView<FileMessageComponent> {
    override fun type() = ComponentType.FILE

    override fun configure(
        component: FileMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val entry = componentActionListener.componentEntry ?: return
        val binding = WidgetBotUiFileComponentBinding.bind(this)
        val id = component.id ?: component.index
        val revealed = entry.messageState?.visibleSpoilerEmbedMap?.containsKey(id) == true
        val hidden = component.spoiler && !revealed
        // Both are absent until the upload behind the component resolves.
        val name = component.name ?: context.getString(R.string.attachment_filename_unknown)
        binding.name.text = if (hidden) context.getString(R.string.spoiler) else name
        binding.size.text = Formatter.formatShortFileSize(context, component.size ?: 0L)
        binding.size.visibility = if (hidden || component.size == null) View.GONE else View.VISIBLE
        binding.download.visibility = if (hidden) View.GONE else View.VISIBLE
        setOnClickListener {
            if (hidden) {
                binding.name.text = name
                binding.size.visibility = if (component.size == null) View.GONE else View.VISIBLE
                binding.download.visibility = View.VISIBLE
                StoreStream.getMessageState().revealSpoilerEmbed(entry.message.id, id)
                setOnClickListener { UriHandler.handleOrUntrusted(context, component.file.url) }
            } else {
                UriHandler.handleOrUntrusted(context, component.file.url)
            }
        }
    }

    companion object {
        fun inflate(context: Context, root: ViewGroup) = LayoutInflater.from(context).inflate(
            R.layout.widget_bot_ui_file_component,
            root,
            false,
        ) as FileComponentView
    }
}

class SeparatorComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), ComponentView<SeparatorMessageComponent> {
    override fun type() = ComponentType.SEPARATOR

    override fun configure(
        component: SeparatorMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val binding = WidgetBotUiSeparatorComponentBinding.bind(this)
        // `spacing` names the total gap the separator introduces. Stacked components already sit
        // one small step apart, so only the difference is added here — and a separator with no
        // divider becomes exactly that much blank space, which is what `divider: false` is for.
        val gap = if (component.spacing == SeparatorComponent.SPACING_LARGE) LARGE_GAP_DP else SMALL_GAP_DP
        val vertical = dpToPixels(gap - SMALL_GAP_DP) / 2
        setPadding(0, vertical, 0, vertical)
        binding.divider.visibility = if (component.divider) View.VISIBLE else View.GONE
    }

    companion object {
        private const val SMALL_GAP_DP = 8
        private const val LARGE_GAP_DP = 16

        fun inflate(context: Context, root: ViewGroup) = LayoutInflater.from(context).inflate(
            R.layout.widget_bot_ui_separator_component,
            root,
            false,
        ) as SeparatorComponentView
    }
}

class ContainerComponentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), ComponentView<ContainerMessageComponent> {
    override fun type() = ComponentType.CONTAINER

    override fun configure(
        component: ContainerMessageComponent,
        componentProvider: ComponentProvider,
        componentActionListener: ComponentActionListener,
    ) {
        val entry = componentActionListener.componentEntry ?: return
        val binding = WidgetBotUiContainerComponentBinding.bind(this)
        binding.accent.visibility = if (component.accentColor == null) View.GONE else View.VISIBLE
        binding.accent.setBackgroundColor(component.accentColor?.let { ColorUtils.setAlphaComponent(it, 255) } ?: Color.TRANSPARENT)
        binding.components.replaceViews(component.children.mapIndexedNotNull { index, child ->
            componentProvider.getConfiguredComponentView(componentActionListener, child, binding.components, index) as? View
        })
        val id = component.id ?: component.index
        val revealed = entry.messageState?.visibleSpoilerEmbedMap?.containsKey(id) == true
        binding.containerSpoiler.visibility = if (component.spoiler && !revealed) View.VISIBLE else View.GONE
        binding.containerSpoiler.setOnClickListener {
            binding.containerSpoiler.visibility = View.GONE
            StoreStream.getMessageState().revealSpoilerEmbed(entry.message.id, id)
        }
    }

    companion object {
        fun inflate(context: Context, root: ViewGroup) = LayoutInflater.from(context).inflate(
            R.layout.widget_bot_ui_container_component,
            root,
            false,
        ) as ContainerComponentView
    }
}

private const val SPOILER_KEY_PREFIX = "comp:"

/** Distinguishes the cells of one gallery, which all share the gallery component's spoiler id. */
private const val GALLERY_KEY_PREFIX = "gallery:"

/** Adapts an unfurled media item to the attachment the media viewer and image loader speak. */
private fun UnfurledMediaItem.toAttachment(id: Long): MessageAttachment {
    return MessageAttachment(
        url = url,
        size = 0,
        id = attachmentId ?: id,
        proxyUrl = proxyUrl?.takeIf(String::isNotBlank) ?: url,
        filename = Uri.parse(url).lastPathSegment.orEmpty().ifBlank { "media" },
        width = width,
        height = height,
        // The two flag sets do not share bit positions, so the one flag that survives the trip is
        // translated rather than copied.
        flags = if (isAnimated) MessageAttachment.FLAG_IS_ANIMATED else null,
        contentType = contentType,
    )
}

private fun BotUiComponentEntry.toMediaViewerContext() =
    MediaViewerContext(
        authorName = message.author?.globalName ?: message.author?.username,
        avatarUrl = IconUtils.getForUser(
            message.author?.id,
            message.author?.avatar?.value,
            message.author?.discriminator?.toIntOrNull(),
            size = 96,
        ),
        timestampMs = message.timestamp?.dateTimeMillis,
    )
