package com.discord.widgets.chat.list

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.databinding.ChatAttachmentViewBinding
import com.discord.simpleast.code.CodeRules
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.models.domain.ModelUserRelationship
import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.utilities.media.ContentSurface
import com.discord.utilities.media.Redaction
import com.discord.utilities.media.SensitiveContentSettings
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.media.WidgetMedia
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** Renders inline media, ordinary file cards, and bounded previews for common text files. */
class ChatAttachmentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding = ChatAttachmentViewBinding.inflate(LayoutInflater.from(context), this)
    val inlineMedia: InlineMediaView = binding.chatListItemAttachmentInlineMedia
    private val voiceMessage: VoiceMessageView = binding.chatListItemAttachmentVoiceMessage
    private val audioPlayer: AudioAttachmentPlayerView = binding.chatListItemAttachmentAudioPlayer
    private val userSettings = StoreStream.getUserSettings()
    private val maxAttachmentImageWidth = EmbedResourceUtils.computeMaximumImageWidthPx(context)
    private var previewCall: Call? = null
    private var previewUrl: String? = null
    private var previewContent: String? = null
    private var expandedContent: String? = null
    private var expanded = false
    private var wrapping = false

    init {
        binding.chatListItemAttachmentTextPreviewScroll.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    fun configure(
        entry: AttachmentEntry,
        isSpoilerClickAllowed: Boolean,
        onSpoilerBlocked: () -> Unit,
        onQuickDownload: (Uri, String) -> Boolean,
    ) {
        if (entry.message.isVoiceMessage()) {
            audioPlayer.releasePlayer()
            audioPlayer.visibility = View.GONE
            inlineMedia.visibility = View.GONE
            binding.chatListItemAttachmentCard.visibility = View.GONE
            binding.chatListItemAttachmentSpoiler.visibility = View.GONE
            voiceMessage.visibility = View.VISIBLE
            voiceMessage.configure(entry.attachment)
            return
        }
        voiceMessage.releasePlayer()
        voiceMessage.visibility = View.GONE
        val hiddenSpoiler = configureSpoiler(entry, isSpoilerClickAllowed, onSpoilerBlocked)
        configureMedia(entry.attachment, entry.autoPlayGifs)
        configureFileData(entry.attachment, onQuickDownload)
        if (entry.attachment.isAudio) {
            audioPlayer.visibility = View.VISIBLE
            audioPlayer.configure(entry.attachment.url, entry.attachment.durationSecs)
        } else {
            audioPlayer.releasePlayer()
            audioPlayer.visibility = View.GONE
        }

        val openAttachment = OnClickListener { openAttachment(it.context, entry.attachment) }
        val previewsText = entry.attachment.type == MessageAttachmentType.FILE &&
            isTextPreviewFilename(entry.attachment.filename)
        inlineMedia.setOnClickListener(openAttachment)
        binding.chatListItemAttachmentCard.setOnClickListener(if (previewsText) null else openAttachment)
        configureTextPreview(entry.attachment, previewsText && !hiddenSpoiler)
    }

    fun onPause() {
        inlineMedia.onPause()
        voiceMessage.releasePlayer()
        audioPlayer.releasePlayer()
    }

    fun onResume() = inlineMedia.onResume()

    private fun configureSpoiler(
        entry: AttachmentEntry,
        isSpoilerClickAllowed: Boolean,
        onSpoilerBlocked: () -> Unit,
    ): Boolean {
        val redaction = redactionFor(entry)
        val revealed = entry.messageState?.visibleSpoilerEmbedMap?.containsKey(entry.embedIndex) == true
        val blocked = redaction == Redaction.BLOCK
        val hidden = blocked || ((entry.attachment.isSpoiler || redaction == Redaction.BLUR) && !revealed)
        binding.chatListItemAttachmentSpoilerLabel.setText(
            when {
                blocked -> R.string.sensitive_content_blocked_media
                redaction == Redaction.BLUR && !entry.attachment.isSpoiler -> R.string.sensitive_content_blurred_media
                else -> R.string.spoiler
            },
        )
        if (hidden) {
            binding.chatListItemAttachmentSpoiler.fadeIn(
                SPOILER_FADE_IN_MS,
                additionalAnimation = { it.scaleX(1f).scaleY(1f) },
            )
        } else {
            binding.chatListItemAttachmentSpoiler.fadeOut(
                SPOILER_FADE_OUT_MS,
                additionalAnimation = { it.scaleX(0.9f).scaleY(0.9f) },
            )
        }
        binding.chatListItemAttachmentSpoiler.setOnClickListener {
            if (blocked) {
                return@setOnClickListener
            }
            if (isSpoilerClickAllowed) {
                StoreStream.getMessageState().revealSpoilerEmbed(entry.message.id, entry.embedIndex)
            } else {
                onSpoilerBlocked()
            }
        }
        return hidden
    }

    private fun redactionFor(entry: AttachmentEntry): Redaction = SensitiveContentSettings(
        explicit = UserSettings.explicitContent.get(),
        gore = UserSettings.goreContent.get(),
        selfHarm = UserSettings.selfHarmContent.get(),
    ).redactionFor(entry.attachment, surfaceFor(entry))

    private fun surfaceFor(entry: AttachmentEntry): ContentSurface {
        if (entry.guildId != 0L) return ContentSurface.GUILD
        val authorId = entry.message.author?.id ?: return ContentSurface.NON_FRIEND_DM
        if (authorId == StoreStream.getUsers().getMe().id) return ContentSurface.FRIEND_DM
        val relationship = StoreStream.getUserRelationships().getRelationships()[authorId]
        return if (relationship == ModelUserRelationship.TYPE_FRIEND) {
            ContentSurface.FRIEND_DM
        } else {
            ContentSurface.NON_FRIEND_DM
        }
    }

    private fun configureMedia(attachment: MessageAttachment, autoPlayGifs: Boolean) {
        inlineMedia.contentDescription = attachment.description
            ?.takeIf { UserSettings.viewImageDescriptions.get() }
        val canInline = attachment.type != MessageAttachmentType.FILE && userSettings.getIsAttachmentMediaInline()
        val width = attachment.width ?: 0
        val height = attachment.height ?: 0
        if (!canInline || (width <= 0 && height <= 0)) {
            binding.chatListItemAttachmentCard.visibility = View.VISIBLE
            inlineMedia.visibility = View.GONE
            inlineMedia.updateUIWithAttachment(attachment, autoPlayGifs = autoPlayGifs)
            return
        }

        val (scaledWidth, scaledHeight) = EmbedResourceUtils.calculateScaledSize(
            width,
            height,
            maxAttachmentImageWidth,
            EmbedResourceUtils.MAX_IMAGE_VIEW_HEIGHT_PX,
            resources,
        )
        binding.chatListItemAttachmentCard.visibility = View.GONE
        inlineMedia.visibility = View.VISIBLE
        inlineMedia.updateUIWithAttachment(attachment, scaledWidth, scaledHeight, autoPlayGifs)
    }

    private fun configureFileData(
        attachment: MessageAttachment,
        onQuickDownload: (Uri, String) -> Boolean,
    ) {
        binding.chatListItemAttachmentName.text = attachment.filename
        binding.chatListItemAttachmentName.setOnLongClickListenerConsumeClick { view ->
            (view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText(attachment.filename, attachment.filename))
        }
        binding.chatListItemAttachmentDescription.text =
            Formatter.formatFileSize(context, attachment.size)
        binding.chatListItemAttachmentIcon.setImageResource(
            EmbedResourceUtils.getFileDrawable(attachment.filename),
        )
        binding.chatListItemAttachmentDownload.apply {
            isEnabled = true
            alpha = 1f
            setOnClickListener {
                val started = onQuickDownload(Uri.parse(attachment.url), attachment.filename)
                isEnabled = !started
                alpha = if (started) DOWNLOAD_STARTED_ALPHA else 1f
            }
        }
    }

    private fun configureTextPreview(
        attachment: MessageAttachment,
        show: Boolean,
    ) {
        previewCall?.cancel()
        previewCall = null
        previewUrl = null
        previewContent = null
        expandedContent = null
        expanded = false
        wrapping = false
        binding.chatListItemAttachmentTextPreviewScroll.visibility = View.GONE
        binding.chatListItemAttachmentTextPreviewWrap.visibility = View.GONE
        binding.chatListItemAttachmentTextPreviewMore.visibility = View.GONE
        binding.chatListItemAttachmentCopy.visibility = View.GONE
        if (!show) return

        previewUrl = attachment.url
        binding.chatListItemAttachmentTextPreviewScroll.visibility = View.VISIBLE
        binding.chatListItemAttachmentTextPreview.text = context.getString(R.string.loading)
        requestPreview(attachment, PREVIEW_BYTE_LIMIT, expanding = false)
    }

    private fun requestPreview(
        attachment: MessageAttachment,
        byteLimit: Long,
        expanding: Boolean,
    ) {
        val call = previewClient.newCall(
            Request.Builder()
                .url(attachment.url)
                .header("Range", "bytes=0-${byteLimit - 1}")
                .build(),
        )
        previewCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!call.isCanceled()) postPreview(attachment, null, expanding)
            }

            override fun onResponse(call: Call, response: Response) {
                val bytes = response.use {
                    if (!it.isSuccessful) null else it.body?.source()?.let { source ->
                        readPreviewBytes(source, byteLimit)
                    }
                }
                postPreview(attachment, bytes?.let(::decodeTextPreview), expanding)
            }
        })
    }

    private fun postPreview(
        attachment: MessageAttachment,
        content: String?,
        expanding: Boolean,
    ) = post {
        if (previewUrl != attachment.url) return@post
        previewCall = null
        if (content == null) {
            if (expanding && previewContent != null) {
                binding.chatListItemAttachmentTextPreviewMore.apply {
                    isEnabled = true
                    alpha = 1f
                    contentDescription = context.getString(R.string.text_file_preview_view_more)
                }
                return@post
            }
            binding.chatListItemAttachmentTextPreviewScroll.visibility = View.GONE
            binding.chatListItemAttachmentTextPreviewWrap.visibility = View.GONE
            binding.chatListItemAttachmentTextPreviewMore.visibility = View.GONE
            binding.chatListItemAttachmentCopy.visibility = View.GONE
            binding.chatListItemAttachmentCard.setOnClickListener {
                openAttachment(it.context, attachment)
            }
            return@post
        }
        if (expanding) {
            expandedContent = content
            expanded = true
        } else {
            previewContent = content
        }
        renderPreview(attachment)
        binding.chatListItemAttachmentCopy.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                val value = currentPreviewContent() ?: return@setOnClickListener
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText(attachment.filename, value))
                Toast.makeText(context, R.string.copied_text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderPreview(attachment: MessageAttachment) {
        val content = currentPreviewContent() ?: return
        binding.chatListItemAttachmentTextPreview.apply {
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_MAX_LINES
            text = highlightCode(content, attachment.filename)
        }
        binding.chatListItemAttachmentTextPreviewWrap.apply {
            visibility = View.VISIBLE
            alpha = if (wrapping) 1f else WRAP_DISABLED_ALPHA
            contentDescription = context.getString(
                if (wrapping) R.string.text_file_preview_no_wrap else R.string.text_file_preview_wrap,
            )
            setOnClickListener {
                wrapping = !wrapping
                applyWrapping()
                alpha = if (wrapping) 1f else WRAP_DISABLED_ALPHA
                contentDescription = context.getString(
                    if (wrapping) R.string.text_file_preview_no_wrap else R.string.text_file_preview_wrap,
                )
            }
        }
        binding.chatListItemAttachmentTextPreviewMore.apply {
            visibility = if (attachment.size > PREVIEW_BYTE_LIMIT || expanded) View.VISIBLE else View.GONE
            isEnabled = true
            alpha = 1f
            rotation = if (expanded) 180f else 0f
            contentDescription = context.getString(
                if (expanded) R.string.text_file_preview_show_less else R.string.text_file_preview_view_more,
            )
            setOnClickListener {
                if (expanded) {
                    expanded = false
                    renderPreview(attachment)
                } else if (expandedContent != null) {
                    expanded = true
                    renderPreview(attachment)
                } else {
                    isEnabled = false
                    alpha = DOWNLOAD_STARTED_ALPHA
                    requestPreview(attachment, EXPANDED_BYTE_LIMIT, expanding = true)
                }
            }
        }
        applyWrapping()
    }

    private fun applyWrapping() {
        val scroll = binding.chatListItemAttachmentTextPreviewScroll
        val viewportWidth = scroll.width
        if (viewportWidth <= 0) {
            scroll.post(::applyWrapping)
            return
        }
        scroll.isFillViewport = wrapping
        binding.chatListItemAttachmentTextPreview.apply {
            val rendered = text
            text = null
            setHorizontallyScrolling(!wrapping)
            layoutParams = layoutParams.apply {
                width = if (wrapping) {
                    scroll.measuredWidth
                } else {
                    val longestLine = currentPreviewContent().orEmpty().lineSequence()
                        .maxOfOrNull { ceil(paint.measureText(it)).toInt() }
                        ?: viewportWidth
                    (longestLine + paddingLeft + paddingRight)
                        .coerceIn(viewportWidth, viewportWidth * MAX_HORIZONTAL_VIEWPORTS)
                }
            }
            text = rendered
            maxWidth = if (wrapping) scroll.measuredWidth else Int.MAX_VALUE
            requestLayout()
        }
        scroll.scrollTo(0, 0)
    }

    private fun currentPreviewContent() = if (expanded) expandedContent ?: previewContent else previewContent

    private fun highlightCode(content: String, filename: String): CharSequence {
        val language = syntaxLanguageForFilename(filename) ?: return content
        val rules = codeLanguageRules[language] ?: return content
        val parser = Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>(false)
        parser.addRules(rules)
        val ast = parser.parse(content, MessageParseState.initialState)
        return AstRenderer.render(ast, MessageRenderContext(context, 0L, false))
    }

    override fun onDetachedFromWindow() {
        previewCall?.cancel()
        previewCall = null
        super.onDetachedFromWindow()
    }

    private fun openAttachment(context: Context, attachment: MessageAttachment) {
        when (attachment.type) {
            MessageAttachmentType.IMAGE, MessageAttachmentType.VIDEO -> WidgetMedia.launch(context, attachment)
            MessageAttachmentType.FILE -> UriHandler.handleOrUntrusted(context, attachment.url)
        }
    }

    private companion object {
        const val PREVIEW_BYTE_LIMIT = 300L
        const val EXPANDED_BYTE_LIMIT = 128L * 1_024L
        const val COLLAPSED_MAX_LINES = 12
        const val MAX_HORIZONTAL_VIEWPORTS = 16
        const val SPOILER_FADE_IN_MS = 50L
        const val SPOILER_FADE_OUT_MS = 200L
        const val DOWNLOAD_STARTED_ALPHA = 0.3f
        const val WRAP_DISABLED_ALPHA = 0.6f
        val previewClient = OkHttpClient()
        val codeLanguageRules by lazy {
            CodeRules.createCodeLanguageMap<MessageRenderContext, MessageParseState>(
                Rules.createCodeStyleProviders(),
            )
        }

        fun readPreviewBytes(source: okio.BufferedSource, byteLimit: Long): ByteArray {
            val buffer = Buffer()
            var remaining = byteLimit
            while (remaining > 0) {
                val read = source.read(buffer, remaining)
                if (read == -1L) break
                remaining -= read
            }
            return buffer.readByteArray()
        }
    }
}

internal fun isTextPreviewFilename(filename: String): Boolean {
    val lower = filename.lowercase(Locale.ROOT)
    return lower.substringBefore('.') in TEXT_PREVIEW_NAMES || lower.substringAfterLast('.', "") in TEXT_PREVIEW_EXTENSIONS
}

internal fun decodeTextPreview(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    val text = bytes.toString(Charsets.UTF_8).trimEnd('\uFFFD')
    val controls = text.count { it < ' ' && it !in "\n\r\t" }
    return text.takeIf { controls <= maxOf(1, text.length / 20) }
}

internal fun syntaxLanguageForFilename(filename: String): String? = when (
    filename.lowercase(Locale.ROOT).substringAfterLast('.', "")
) {
    "kt", "kts" -> "kotlin"
    "proto", "protobuf" -> "protobuf"
    "py" -> "python"
    "rs" -> "rust"
    "sql" -> "sql"
    "xml", "html", "htm", "xhtml", "svg" -> "xml"
    "http" -> "http"
    "cr" -> "crystal"
    "js", "jsx", "json", "jsonc" -> "javascript"
    "ts", "tsx" -> "typescript"
    "diff", "patch" -> "diff"
    else -> null
}

private val TEXT_PREVIEW_NAMES = setOf("dockerfile", "makefile", "license", "readme")
private val TEXT_PREVIEW_EXTENSIONS = (
    "txt text log md markdown json jsonc xml yml yaml toml ini cfg conf csv tsv html htm css scss less " +
        "js jsx ts tsx java kt kts gradle groovy py rb php c cc cpp cxx h hpp cs go rs swift sh bash " +
        "zsh fish ps1 bat cmd sql diff patch properties proto graphql gql lua r pl ex exs erl fs fsx vb " +
        "asm s smali dart scala clj vue svelte tex env"
    ).split(' ').toHashSet()
