package com.discord.widgets.chat.list

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.cardview.widget.CardView
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.embed.RenderableEmbedMedia
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import java.lang.reflect.Proxy
import java.util.Locale

// A proxy implements every callback that Android's desugared Player.Listener exposes as abstract.
internal fun inlineMediaPlayerListener(
    onPlaybackStateChanged: (Int) -> Unit,
    onPlayerError: (PlaybackException) -> Unit,
): Player.Listener = Proxy.newProxyInstance(
    Player.Listener::class.java.classLoader,
    arrayOf(Player.Listener::class.java),
) { proxy, method, args ->
    when (method.name) {
        "onPlaybackStateChanged" -> onPlaybackStateChanged(args?.get(0) as Int)
        "onPlayerError" -> onPlayerError(args?.get(0) as PlaybackException)
        "equals" -> proxy === args?.get(0)
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "InlineMediaPlayerListener"
        else -> null
    }
} as Player.Listener

class InlineMediaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CardView(context, attrs, defStyleAttr), View.OnAttachStateChangeListener {
    private val playerView: PlayerView
    private val imagePreview: SimpleDraweeView
    private val gifIndicator: ImageView
    private val playButton: ImageView
    private val loadingIndicator: ProgressBar
    private val volumeToggle: ImageView
    private var player: ExoPlayer? = null
    private var viewParams: ViewParams? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.inline_media_view, this, true)
        playerView = requireNotNull(findViewById(R.id.inline_media_player_view))
        imagePreview = requireNotNull(findViewById(R.id.inline_media_image_preview))
        gifIndicator = requireNotNull(findViewById(R.id.inline_media_gif_indicator))
        playButton = requireNotNull(findViewById(R.id.inline_media_play_button))
        loadingIndicator = requireNotNull(findViewById(R.id.inline_media_loading_indicator))
        volumeToggle = requireNotNull(findViewById(R.id.inline_media_volume_toggle))
        addOnAttachStateChangeListener(this)
    }

    @JvmOverloads
    fun updateUIWithAttachment(
        attachment: MessageAttachment,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        autoPlayGifs: Boolean,
    ) {
        if (attachment.type !in setOf(MessageAttachmentType.IMAGE, MessageAttachmentType.VIDEO)) return
        val type = if (attachment.type == MessageAttachmentType.VIDEO) EmbedType.VIDEO else EmbedType.IMAGE
        update(
            RenderableEmbedMedia(attachment.proxyUrl, attachment.width, attachment.height),
            attachment.url.takeIf { attachment.type == MessageAttachmentType.VIDEO },
            type,
            targetWidth,
            targetHeight,
            autoPlayGifs,
        )
    }

    @JvmOverloads
    fun updateUIWithEmbed(
        embed: MessageEmbed,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        autoPlayGifs: Boolean,
    ) {
        val preview = previewImage(embed) ?: return
        update(
            preview,
            embed.video?.let { it.proxyUrl ?: it.url },
            embed.type,
            targetWidth,
            targetHeight,
            autoPlayGifs,
        )
    }

    fun onPause() = clearPlayer()

    fun onResume() = resetCurrentEmbed()

    override fun onViewAttachedToWindow(view: View) = resetCurrentEmbed()

    override fun onViewDetachedFromWindow(view: View) = clearPlayer()

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) resetCurrentEmbed() else clearPlayer()
    }

    private fun update(
        preview: RenderableEmbedMedia?,
        mediaUri: String?,
        embedType: EmbedType?,
        targetWidth: Int?,
        targetHeight: Int?,
        autoPlayGifs: Boolean,
    ) {
        val params = ViewParams(
            preview,
            mediaUri,
            embedType,
            targetWidth ?: viewParams?.targetWidth,
            targetHeight ?: viewParams?.targetHeight,
            autoPlayGifs,
        )
        if (params == viewParams) return
        viewParams = params
        render(params)
    }

    private fun resetCurrentEmbed() {
        if (isAttachedToWindow && visibility == View.VISIBLE) viewParams?.let(::render)
    }

    private fun render(params: ViewParams) {
        clearPlayer()
        params.targetWidth?.let { width ->
            params.targetHeight?.let { height ->
                layoutParams = layoutParams.apply {
                    this.width = width
                    this.height = height
                }
            }
        }
        imagePreview.visibility = View.VISIBLE
        imagePreview.setImageURI(params.previewImage?.url?.let { previewUrl(it, params) })
        playerView.visibility = View.GONE
        volumeToggle.visibility = View.GONE
        loadingIndicator.visibility = View.GONE
        playButton.visibility = if (params.embedType == EmbedType.VIDEO) View.VISIBLE else View.GONE
        gifIndicator.visibility = if (isAnimated(params) && !shouldAutoPlay(params)) View.VISIBLE else View.GONE

        val uri = params.progressiveMediaUri ?: return
        if (params.embedType == EmbedType.GIFV && shouldAutoPlay(params)) {
            startPlayer(uri, muted = true)
        } else if (params.embedType == EmbedType.VIDEO) {
            playButton.setOnClickListener { startPlayer(uri, muted = false) }
        }
    }

    private fun startPlayer(uri: String, muted: Boolean) {
        clearPlayer()
        loadingIndicator.visibility = View.VISIBLE
        playButton.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        player = ExoPlayer.Builder(context).build().also { exoPlayer ->
            playerView.player = exoPlayer
            exoPlayer.volume = if (muted) 0f else 1f
            configureVolume(exoPlayer)
            exoPlayer.addListener(
                inlineMediaPlayerListener(
                    onPlaybackStateChanged = { state ->
                        loadingIndicator.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        if (state == Player.STATE_READY) {
                            imagePreview.visibility = if (shouldAutoPlay(viewParams)) View.GONE else View.VISIBLE
                            volumeToggle.visibility = if (viewParams?.embedType in setOf(null, EmbedType.VIDEO)) View.VISIBLE else View.GONE
                        } else if (state == Player.STATE_ENDED) {
                            resetCurrentEmbed()
                        }
                    },
                    onPlayerError = { resetCurrentEmbed() },
                ),
            )
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun configureVolume(exoPlayer: ExoPlayer) {
        fun updateIcon() {
            val audible = exoPlayer.volume > 0f
            volumeToggle.setImageResource(
                if (audible) R.drawable.ic_volume_up_white_24dp else R.drawable.ic_volume_off_24dp,
            )
            volumeToggle.contentDescription = context.getString(
                if (audible) R.string.mute else R.string.unmute,
            )
            volumeToggle.setOnClickListener {
                exoPlayer.volume = if (audible) 0f else 1f
                updateIcon()
            }
        }
        updateIcon()
    }

    private fun clearPlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun previewUrl(url: String, params: ViewParams): String {
        val width = params.targetWidth ?: params.previewImage?.width ?: measuredWidth
        val height = params.targetHeight ?: params.previewImage?.height ?: measuredHeight
        return "$url?width=$width&height=$height" + if (shouldAutoPlay(params) && url.endsWith(".gif")) "" else "&format=webp"
    }

    private fun shouldAutoPlay(params: ViewParams?) = params != null && (!isAnimated(params) || params.autoPlayGifs)

    private fun isAnimated(params: ViewParams) = params.embedType == EmbedType.GIFV ||
        (params.embedType == EmbedType.IMAGE && params.previewImage?.url?.lowercase(Locale.ROOT)?.contains(".gif") == true)

    private fun previewImage(embed: MessageEmbed): RenderableEmbedMedia? = when (embed.type) {
        EmbedType.VIDEO, EmbedType.GIFV -> embed.thumbnail?.toRenderable() ?: embed.video?.toRenderable()
        EmbedType.ARTICLE, EmbedType.IMAGE, EmbedType.TWEET -> embed.thumbnail?.toRenderable()
        else -> embed.image?.toRenderable()
    }

    private fun com.discord.api.message.embed.EmbedImage.toRenderable() =
        RenderableEmbedMedia(proxyUrl ?: url, width, height)

    private fun com.discord.api.message.embed.EmbedThumbnail.toRenderable() =
        RenderableEmbedMedia(proxyUrl ?: url, width, height)

    private fun com.discord.api.message.embed.EmbedVideo.toRenderable() =
        RenderableEmbedMedia(proxyUrl ?: url, width, height)

    data class ViewParams(
        val previewImage: RenderableEmbedMedia?,
        val progressiveMediaUri: String?,
        val embedType: EmbedType?,
        val targetWidth: Int?,
        val targetHeight: Int?,
        val autoPlayGifs: Boolean,
    )
}
