package com.discord.widgets.chat.list.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.stores.StoreStream
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.widgets.chat.list.FragmentLifecycleListener
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.media.WidgetMedia
import com.google.android.material.card.MaterialCardView

/**
 * One file sent with a message.
 *
 * The row is one of two things depending on what was uploaded and on the viewer's settings:
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
class WidgetChatListAdapterItemAttachment(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_attachment, adapter),
    FragmentLifecycleListener {

    private val highlightedBackground: View =
        itemView.findViewById(R.id.chat_list_adapter_item_highlighted_bg)
    private val gutterBackground: View = itemView.findViewById(R.id.chat_list_adapter_item_gutter_bg)
    private val card: MaterialCardView = itemView.findViewById(R.id.chat_list_item_attachment_card)
    private val icon: ImageView = itemView.findViewById(R.id.chat_list_item_attachment_icon)
    private val name: TextView = itemView.findViewById(R.id.chat_list_item_attachment_name)
    private val description: TextView = itemView.findViewById(R.id.chat_list_item_attachment_description)
    private val download: ImageView = itemView.findViewById(R.id.chat_list_item_attachment_download)
    private val inlineMedia: InlineMediaView =
        itemView.findViewById(R.id.chat_list_item_attachment_inline_media)
    private val spoilerCover: View = itemView.findViewById(R.id.chat_list_item_attachment_spoiler)

    private val userSettings = StoreStream.getUserSettings()
    private val maxAttachmentImageWidth = EmbedResourceUtils.computeMaximumImageWidthPx(itemView.context)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as AttachmentEntry
        configureCellHighlight(entry.message, highlightedBackground, gutterBackground, entry.isPendingReply)
        configureUI(entry)
    }

    /**
     * The gap above an attachment separates it from the message text. The first attachment of a
     * message with no text sits directly under the author line, which brings its own spacing.
     */
    private fun configureTopSpacing(entry: AttachmentEntry) {
        val spaced = entry.embedIndex > 0 || !entry.message.content.isNullOrEmpty()
        val top = if (spaced) {
            itemView.resources.getDimensionPixelSize(R.dimen.chat_cell_vertical_spacing_padding)
        } else {
            0
        }
        listOf(inlineMedia, card).forEach { view ->
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            if (params.topMargin != top) {
                params.topMargin = top
                view.layoutParams = params
            }
        }
    }

    override fun onPause() = inlineMedia.onPause()

    override fun onResume() = inlineMedia.onResume()

    private fun configureUI(entry: AttachmentEntry) {
        val attachment = entry.attachment

        configureTopSpacing(entry)
        configureSpoiler(entry)

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

        configureMedia(attachment, entry.autoPlayGifs)
        configureFileData(attachment)

        val openAttachment = View.OnClickListener { view -> openAttachment(view.context, attachment) }
        inlineMedia.setOnClickListener(openAttachment)
        card.setOnClickListener(openAttachment)
    }

    /**
     * Shows or hides the spoiler cover.
     *
     * The reveal is written to `StoreMessageState` keyed on the attachment's position within the
     * message's combined attachment-then-embed sequence, so revealing one of several spoilered
     * uploads leaves the others covered.
     */
    private fun configureSpoiler(entry: AttachmentEntry) {
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
            if (adapter.data.isSpoilerClickAllowed) {
                StoreStream.getMessageState().revealSpoilerEmbed(entry.message.id, entry.embedIndex)
            } else {
                adapter.eventHandler.onMessageClicked(entry.message, entry.isThreadStarterMessage)
            }
        }
    }

    /**
     * Chooses between the inline player and the file card.
     *
     * Inlining needs three things: an attachment kind that can be drawn, the user's
     * "inline attachment media" setting, and intrinsic dimensions to scale against. Missing any of
     * them the row falls back to the card — which is also the only representation an audio file,
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
            // Still handed to the player so a recycled row does not keep the previous
            // attachment's frame buffered behind the card.
            inlineMedia.updateUIWithAttachment(attachment, autoPlayGifs = autoPlayGifs)
            return
        }

        val (scaledWidth, scaledHeight) = EmbedResourceUtils.calculateScaledSize(
            actualWidth = width,
            actualHeight = height,
            maxWidth = maxAttachmentImageWidth,
            maxHeight = EmbedResourceUtils.MAX_IMAGE_VIEW_HEIGHT_PX,
            resources = inlineMedia.resources,
        )
        card.visibility = View.GONE
        inlineMedia.visibility = View.VISIBLE
        inlineMedia.updateUIWithAttachment(attachment, scaledWidth, scaledHeight, autoPlayGifs)
    }

    /**
     * Fills the file card: icon, filename, size and the download affordance.
     *
     * The download button is re-enabled on every bind because the holder is recycled — a row that
     * was disabled by a previous download must not stay disabled for the next attachment.
     */
    private fun configureFileData(attachment: MessageAttachment) {
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
            val started = adapter.eventHandler
                .onQuickDownloadClicked(Uri.parse(attachment.url), attachment.filename)
            download.isEnabled = !started
            download.alpha = DOWNLOAD_STARTED_ALPHA
        }
    }

    /**
     * Opens the attachment.
     *
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
