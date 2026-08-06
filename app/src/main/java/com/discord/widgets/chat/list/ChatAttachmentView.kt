package com.discord.widgets.chat.list

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.stores.StoreStream
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.media.WidgetMedia
import com.google.android.material.card.MaterialCardView

/**
 * One file sent with a message.
 *
 * Renders as one of two things depending on what was uploaded and on the viewer's settings:
 *
 *  * **Inline media** — an image or video that carries usable intrinsic dimensions, drawn through
 *    [InlineMediaView] at a size scaled to fit the chat column. Videos get a play button, GIFs
 *    auto-play or show a GIF badge according to the user's setting, and the player is torn down with
 *    the screen through [FragmentLifecycleListener].
 *  * **A file card** — everything else, including images the user has chosen not to inline: a
 *    type-specific icon, the filename, a localized human-readable size, and a download button.
 *
 * A `SPOILER_`-prefixed upload is covered until tapped, and the cover is inert on read-only surfaces
 * (pins, search) where revealing would edit state the surface does not own.
 */
class ChatAttachmentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val card: MaterialCardView
    private val icon: ImageView
    private val name: TextView
    private val description: TextView
    private val download: ImageView
    private val spoilerCover: View
    val inlineMedia: InlineMediaView

    private val userSettings = StoreStream.getUserSettings()
    private val maxAttachmentImageWidth = EmbedResourceUtils.computeMaximumImageWidthPx(context)

    init {
        LayoutInflater.from(context).inflate(R.layout.chat_attachment_view, this)
        card = findViewById(R.id.chat_list_item_attachment_card)
        icon = findViewById(R.id.chat_list_item_attachment_icon)
        name = findViewById(R.id.chat_list_item_attachment_name)
        description = findViewById(R.id.chat_list_item_attachment_description)
        download = findViewById(R.id.chat_list_item_attachment_download)
        spoilerCover = findViewById(R.id.chat_list_item_attachment_spoiler)
        inlineMedia = findViewById(R.id.chat_list_item_attachment_inline_media)
    }

    fun configure(
        entry: AttachmentEntry,
        isSpoilerClickAllowed: Boolean,
        onSpoilerBlocked: () -> Unit,
        onQuickDownload: (Uri, String) -> Boolean,
    ) {
        configureSpoiler(entry, isSpoilerClickAllowed, onSpoilerBlocked)
        configureMedia(entry.attachment, entry.autoPlayGifs)
        configureFileData(entry.attachment, onQuickDownload)

        val openAttachment = OnClickListener { openAttachment(it.context, entry.attachment) }
        inlineMedia.setOnClickListener(openAttachment)
        card.setOnClickListener(openAttachment)
    }

    /**
     * The reveal is written to `StoreMessageState` keyed on the attachment's position within the
     * message's combined attachment-then-embed sequence, so revealing one of several spoilered
     * uploads leaves the others covered.
     */
    private fun configureSpoiler(
        entry: AttachmentEntry,
        isSpoilerClickAllowed: Boolean,
        onSpoilerBlocked: () -> Unit,
    ) {
        val revealed = entry.messageState
            ?.visibleSpoilerEmbedMap
            ?.containsKey(entry.embedIndex) == true
        val hidden = entry.attachment.isSpoiler && !revealed

        if (hidden) {
            spoilerCover.fadeIn(SPOILER_FADE_IN_MS, additionalAnimation = { it.scaleX(1f).scaleY(1f) })
        } else {
            spoilerCover.fadeOut(SPOILER_FADE_OUT_MS, additionalAnimation = { it.scaleX(0.9f).scaleY(0.9f) })
        }
        spoilerCover.setOnClickListener {
            if (isSpoilerClickAllowed) {
                StoreStream.getMessageState().revealSpoilerEmbed(entry.message.id, entry.embedIndex)
            } else {
                onSpoilerBlocked()
            }
        }
    }

    /**
     * Inlining needs three things: an attachment kind that can be drawn, the user's
     * "inline attachment media" setting, and intrinsic dimensions to scale against. Missing any of
     * them it falls back to the card — which is also the only representation an audio file,
     * archive or document ever gets.
     */
    private fun configureMedia(attachment: MessageAttachment, autoPlayGifs: Boolean) {
        val isPreviewable = attachment.type != MessageAttachmentType.FILE
        val canInline = isPreviewable && userSettings.getIsAttachmentMediaInline()
        val width = attachment.width ?: 0
        val height = attachment.height ?: 0

        if (!canInline || (width <= 0 && height <= 0)) {
            card.visibility = View.VISIBLE
            inlineMedia.visibility = View.GONE
            // Still handed to the player so a recycled view does not keep the previous
            // attachment's frame buffered behind the card.
            inlineMedia.updateUIWithAttachment(attachment, autoPlayGifs = autoPlayGifs)
            return
        }

        val (scaledWidth, scaledHeight) = EmbedResourceUtils.calculateScaledSize(
            actualWidth = width,
            actualHeight = height,
            maxWidth = maxAttachmentImageWidth,
            maxHeight = EmbedResourceUtils.MAX_IMAGE_VIEW_HEIGHT_PX,
            resources = resources,
        )
        card.visibility = View.GONE
        inlineMedia.visibility = View.VISIBLE
        inlineMedia.updateUIWithAttachment(attachment, scaledWidth, scaledHeight, autoPlayGifs)
    }

    /**
     * The download button is re-enabled on every bind because the view is recycled — one that
     * was disabled by a previous download must not stay disabled for the next attachment.
     */
    private fun configureFileData(attachment: MessageAttachment, onQuickDownload: (Uri, String) -> Boolean) {
        name.text = attachment.filename
        name.setOnLongClickListenerConsumeClick { view ->
            val clipboard = view.context
                .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(attachment.filename, attachment.filename))
        }
        description.text = Formatter.formatFileSize(description.context, attachment.size)
        icon.setImageResource(EmbedResourceUtils.getFileDrawable(attachment.filename))

        download.isEnabled = true
        download.alpha = 1f
        download.setOnClickListener {
            val started = onQuickDownload(Uri.parse(attachment.url), attachment.filename)
            download.isEnabled = !started
            download.alpha = DOWNLOAD_STARTED_ALPHA
        }
    }

    /**
     * Images and videos go to the fullscreen viewer, which can zoom and play them in place.
     * Everything else is a file the app cannot render, so it is handed to [UriHandler].
     */
    private fun openAttachment(context: Context, attachment: MessageAttachment) {
        when (attachment.type) {
            MessageAttachmentType.IMAGE, MessageAttachmentType.VIDEO -> WidgetMedia.launch(context, attachment)
            MessageAttachmentType.FILE -> UriHandler.handleOrUntrusted(context, attachment.url)
        }
    }

    private companion object {
        private const val SPOILER_FADE_IN_MS = 50L
        private const val SPOILER_FADE_OUT_MS = 200L

        /** Dims the download button once a download has been handed to the system downloader. */
        private const val DOWNLOAD_STARTED_ALPHA = 0.3f
    }
}
