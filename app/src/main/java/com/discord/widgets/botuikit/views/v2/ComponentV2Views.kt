package com.discord.widgets.botuikit.views.v2

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.discord.R
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.UnfurledMediaItem
import com.discord.api.message.attachment.MessageAttachment
import com.discord.databinding.WidgetBotUiContainerComponentBinding
import com.discord.databinding.WidgetBotUiFileComponentBinding
import com.discord.databinding.WidgetBotUiMediaGalleryComponentBinding
import com.discord.databinding.WidgetBotUiSectionComponentBinding
import com.discord.databinding.WidgetBotUiSeparatorComponentBinding
import com.discord.databinding.WidgetBotUiTextDisplayComponentBinding
import com.discord.databinding.WidgetBotUiThumbnailComponentBinding
import com.discord.models.botuikit.ContainerMessageComponent
import com.discord.models.botuikit.ButtonMessageComponent
import com.discord.models.botuikit.FileMessageComponent
import com.discord.models.botuikit.MediaGalleryMessageComponent
import com.discord.models.botuikit.MessageComponent
import com.discord.models.botuikit.SectionMessageComponent
import com.discord.models.botuikit.SeparatorMessageComponent
import com.discord.models.botuikit.TextDisplayMessageComponent
import com.discord.models.botuikit.ThumbnailMessageComponent
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageTextRenderer
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.botuikit.ComponentProvider
import com.discord.widgets.botuikit.replaceViews
import com.discord.widgets.botuikit.views.ComponentActionListener
import com.discord.widgets.botuikit.views.ComponentView
import com.discord.widgets.media.MediaViewerContext
import com.discord.widgets.media.WidgetMedia
import kotlin.math.ceil

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
        binding.content.setDraweeSpanStringBuilder(rendered.text)
    }

    companion object {
        fun inflate(context: Context, root: ViewGroup) = LayoutInflater.from(context).inflate(
            R.layout.widget_bot_ui_text_display_component,
            root,
            false,
        ) as TextDisplayComponentView
    }
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
            listOfNotNull(component.accessory?.let {
                componentProvider.getConfiguredComponentView(componentActionListener, it, binding.accessory, 0) as? View
            }),
        )
        binding.accessory.visibility = if (component.accessory == null) View.GONE else View.VISIBLE
        if (component.accessory is ButtonMessageComponent) {
            orientation = LinearLayout.VERTICAL
            binding.components.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            binding.accessory.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dpToPixels(4) }
        } else {
            orientation = LinearLayout.HORIZONTAL
            binding.components.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
            binding.accessory.layoutParams = LinearLayout.LayoutParams(
                dpToPixels(80),
                dpToPixels(80),
            ).apply { marginStart = dpToPixels(16) }
        }
    }

    companion object {
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
        binding.image.setImageURI(component.media.previewUrl())
        binding.image.contentDescription = component.description
        val hidden = component.spoiler && !revealed
        binding.mediaSpoiler.visibility = if (hidden) View.VISIBLE else View.GONE
        binding.alt.visibility = if (!hidden && !component.description.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.mediaSpoiler.setOnClickListener {
            binding.mediaSpoiler.visibility = View.GONE
            binding.alt.visibility = if (component.description.isNullOrBlank()) View.GONE else View.VISIBLE
            if (key == null) StoreStream.getMessageState().revealSpoilerEmbed(messageId, id)
            else StoreStream.getMessageState().revealSpoilerEmbedData(messageId, id, key)
        }
        val openMedia = View.OnClickListener {
            val attachment = component.media.toAttachment(component.id?.toLong() ?: component.index.toLong())
            if (viewerContext == null) WidgetMedia.launch(context, attachment)
            else WidgetMedia.launch(context, attachment, viewerContext)
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
    private var boundEntry: com.discord.widgets.chat.list.entries.BotUiComponentEntry? = null
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
        val contentWidth = (availableWidth - dpToPixels(8)).coerceAtLeast(dpToPixels(120))
        val binding = WidgetBotUiMediaGalleryComponentBinding.bind(this)
        val gap = dpToPixels(4)
        val count = component.items.size
        val totalHeight = when (count) {
            0 -> 0
            1 -> {
                val media = component.items[0].media
                val ratio = if ((media.width ?: 0) > 0 && (media.height ?: 0) > 0) {
                    media.height!!.toFloat() / media.width!!
                } else {
                    0.6f
                }
                (contentWidth * ratio).toInt().coerceIn(dpToPixels(80), dpToPixels(300))
            }
            2 -> (contentWidth - gap) / 2
            3 -> contentWidth * 2 / 3
            4 -> contentWidth
            else -> {
                val cell = (contentWidth - gap * 2) / 3
                ceil(count / 3f).toInt() * cell + (ceil(count / 3f).toInt() - 1) * gap
            }
        }
        layoutParams = layoutParams.apply {
            width = availableWidth
            height = totalHeight
        }
        val views = component.items.mapIndexed { index, item ->
            val view = (getChildAt(index) as? ThumbnailComponentView) ?: ThumbnailComponentView.inflate(context, binding.root)
            view.layoutParams = galleryLayoutParams(index, count, contentWidth, totalHeight, gap)
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
                "gallery:$index",
                entry.toMediaViewerContext(),
            )
            view
        }
        replaceViews(views)
    }

    private fun galleryLayoutParams(
        index: Int,
        count: Int,
        width: Int,
        height: Int,
        gap: Int,
    ): FrameLayout.LayoutParams {
        if (count == 1) return FrameLayout.LayoutParams(width, height)
        if (count == 2) {
            val cell = (width - gap) / 2
            return FrameLayout.LayoutParams(cell, height).apply {
                if (index == 1) leftMargin = cell + gap
            }
        }
        if (count == 3) {
            val rightWidth = (width - gap) / 3
            val leftWidth = width - gap - rightWidth
            val rightHeight = (height - gap) / 2
            return if (index == 0) {
                FrameLayout.LayoutParams(leftWidth, height)
            } else {
                FrameLayout.LayoutParams(rightWidth, rightHeight).apply {
                    leftMargin = leftWidth + gap
                    if (index == 2) topMargin = rightHeight + gap
                }
            }
        }
        val columns = if (count == 4) 2 else 3
        val cell = (width - gap * (columns - 1)) / columns
        val row = index / columns
        val column = index % columns
        return FrameLayout.LayoutParams(cell, cell).apply {
            leftMargin = column * (cell + gap)
            topMargin = row * (cell + gap)
        }
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
        binding.name.text = if (hidden) context.getString(R.string.spoiler) else component.name
        binding.size.text = Formatter.formatShortFileSize(context, component.size)
        binding.size.visibility = if (hidden) View.GONE else View.VISIBLE
        binding.download.visibility = if (hidden) View.GONE else View.VISIBLE
        setOnClickListener {
            if (hidden) {
                binding.name.text = component.name
                binding.size.visibility = View.VISIBLE
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
        val vertical = dpToPixels(if (component.spacing == 2) 12 else 6)
        setPadding(0, vertical, 0, vertical)
        binding.divider.visibility = if (component.divider) View.VISIBLE else View.GONE
    }

    companion object {
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
        binding.accent.setBackgroundColor(component.accentColor ?: Color.TRANSPARENT)
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

private fun UnfurledMediaItem.isVideo(): Boolean =
    contentType?.startsWith("video/") == true ||
        url.substringBefore('?').lowercase().endsWithAny(".mp4", ".mov", ".webm", ".mkv")

private fun UnfurledMediaItem.previewUrl(): String {
    val preview = proxyUrl ?: url
    if (!isVideo() || "format=" in preview) return preview
    return Uri.parse(preview).buildUpon().appendQueryParameter("format", "webp").build().toString()
}

private fun UnfurledMediaItem.toAttachment(id: Long): MessageAttachment {
    var filename = Uri.parse(url).lastPathSegment?.substringBefore('?').orEmpty().ifBlank { "media" }
    if (isVideo() && !filename.lowercase().endsWithAny(".mp4", ".mov", ".webm", ".mkv")) {
        filename += ".mp4"
    }
    return MessageAttachment(
        url = url,
        size = 0,
        id = attachmentId ?: id,
        proxyUrl = proxyUrl ?: url,
        filename = filename,
        width = width,
        height = height,
    )
}

private fun String.endsWithAny(vararg suffixes: String): Boolean = suffixes.any(::endsWith)

private fun com.discord.widgets.chat.list.entries.BotUiComponentEntry.toMediaViewerContext() =
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
