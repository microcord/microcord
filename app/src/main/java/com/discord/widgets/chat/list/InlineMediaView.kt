package com.discord.widgets.chat.list

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.cardview.widget.CardView
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.app.AppComponent
import com.discord.databinding.InlineMediaViewBinding
import com.discord.embed.RenderableEmbedMedia
import com.discord.player.AppMediaPlayer
import com.discord.player.MediaSource
import com.discord.player.MediaType
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import java.lang.reflect.Proxy
import rx.subjects.PublishSubject
import rx.subjects.Subject
import rx.subscriptions.CompositeSubscription

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

internal fun inlineMediaRepeatMode(embedType: EmbedType?): Int =
    if (embedType == EmbedType.GIFV) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

class InlineMediaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CardView(context, attrs, defStyleAttr), View.OnAttachStateChangeListener, AppComponent {
    private val binding = InlineMediaViewBinding.inflate(LayoutInflater.from(context), this)
    private var playerView: PlayerView? = null
    override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()
    private var appMediaPlayer: AppMediaPlayer? = null
    private var subscriptions = CompositeSubscription()
    private var featureTag = ""
    private var viewParams: ViewParams? = null

    init {
        addOnAttachStateChangeListener(this)
    }

    /** Read at the tap, before the fullscreen activity pauses/releases this surface. */
    val currentPositionMs: Long
        get() = playerView?.player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    @JvmOverloads
    fun updateUIWithAttachment(
        attachment: MessageAttachment,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        autoPlayGifs: Boolean,
    ) {
        if (attachment.type !in setOf(MessageAttachmentType.IMAGE, MessageAttachmentType.VIDEO)) return
        val type = if (attachment.type == MessageAttachmentType.VIDEO) EmbedType.VIDEO else EmbedType.IMAGE
        featureTag = "${InlineMediaView::class.java.simpleName}: attachment"
        update(
            EmbedResourceUtils.createRenderableEmbedMediaFromAttachment(attachment),
            attachment.proxyUrl.takeIf { attachment.type == MessageAttachmentType.VIDEO },
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
        val preview = with(EmbedResourceUtils) { embed.getPreviewImage() } ?: return
        featureTag = "${InlineMediaView::class.java.simpleName}: embed"
        update(
            preview,
            embed.video?.let { it.proxyUrl ?: it.url },
            embed.type,
            targetWidth,
            targetHeight,
            autoPlayGifs,
        )
    }

    fun onPause() = clearPlayerAndSubscriptions()

    fun onResume() = resetCurrentEmbed()

    override fun onViewAttachedToWindow(view: View) = resetCurrentEmbed()

    override fun onViewDetachedFromWindow(view: View) = clearPlayerAndSubscriptions()

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) resetCurrentEmbed() else clearPlayerAndSubscriptions()
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
        resetViews(params)
        subscriptions.unsubscribe()
        clearPlayer()
        params.targetWidth?.let { width ->
            params.targetHeight?.let { height ->
                if (layoutParams.width != width || layoutParams.height != height) {
                    layoutParams.width = width
                    layoutParams.height = height
                    requestLayout()
                }
                params.previewImage?.url?.let { url ->
                    MGImages.setImage(
                        binding.inlineMediaImagePreview,
                        EmbedResourceUtils.getPreviewUrls(url, width, height, shouldAutoPlay(params)),
                    )
                }
            }
        }

        val uri = params.progressiveMediaUri ?: return
        val mediaSource = MediaSource.create(
            if (params.embedType == EmbedType.GIFV) MediaType.GIFV else MediaType.VIDEO,
            uri,
            featureTag,
        )
        if (params.embedType == EmbedType.GIFV) {
            val player = createPlayer(params)
            player.play(mediaSource, getPlayerView(), autoPlayWhenReady = shouldAutoPlay(params), loopMedia = true)
            player.setVolume(0f)
        }
        binding.inlineMediaPlayButton.setOnClickListener {
            val player = createPlayer(params)
            val view = getPlayerView()
            view.visibility = View.VISIBLE
            player.setVolume(1f)
            player.play(mediaSource, view)
            binding.inlineMediaPlayButton.visibility = View.GONE
            binding.inlineMediaLoadingIndicator.visibility = View.VISIBLE
        }
    }

    private fun createPlayer(params: ViewParams): AppMediaPlayer {
        appMediaPlayer?.let { return it }
        subscriptions = CompositeSubscription()
        // Inline surfaces cap their buffer window: a high-bitrate video filling the default 30 s
        // window is tens of MB of allocations and was dragging the app into ANRs mid-playback.
        return AppMediaPlayer.create(context, lowMemoryBuffers = true).also { player ->
            appMediaPlayer = player
            player.volume.ui(this).appSubscribe(
                InlineMediaView::class.java,
                subscriptionHandler = subscriptions::add,
            ) { configureVolume(player, it > 0f) }
            player.events.ui(this).appSubscribe(
                InlineMediaView::class.java,
                subscriptionHandler = subscriptions::add,
                errorHandler = { resetViews(params) },
            ) { handlePlayerEvent(it, params) }
        }
    }

    private fun getPlayerView(): PlayerView = playerView ?: (binding.inlineMediaPlayerStub.inflate() as PlayerView).also {
        playerView = it
    }

    private fun handlePlayerEvent(event: AppMediaPlayer.Event, params: ViewParams) {
        when (event) {
            AppMediaPlayer.Event.Ready -> {
                binding.inlineMediaImagePreview.visibility = if (shouldAutoPlay(params)) View.GONE else View.VISIBLE
                binding.inlineMediaLoadingIndicator.visibility = View.GONE
            }
            AppMediaPlayer.Event.Buffering -> binding.inlineMediaLoadingIndicator.visibility = View.VISIBLE
            AppMediaPlayer.Event.Playing -> {
                binding.inlineMediaVolumeToggle.visibility =
                    if (params.embedType == null || params.embedType == EmbedType.VIDEO) View.VISIBLE else View.GONE
            }
            AppMediaPlayer.Event.Ended -> resetCurrentEmbed()
            is AppMediaPlayer.Event.CurrentPositionUpdate,
            AppMediaPlayer.Event.Paused -> Unit
        }
    }

    private fun configureVolume(player: AppMediaPlayer, audible: Boolean) {
        binding.inlineMediaVolumeToggle.setImageResource(
            if (audible) R.drawable.ic_volume_up_white_24dp else R.drawable.ic_volume_off_24dp,
        )
        binding.inlineMediaVolumeToggle.contentDescription = context.getString(if (audible) R.string.mute else R.string.unmute)
        binding.inlineMediaVolumeToggle.setOnClickListener { player.setVolume(if (audible) 0f else 1f) }
    }

    private fun resetViews(params: ViewParams) {
        binding.inlineMediaImagePreview.visibility = View.VISIBLE
        playerView?.visibility = if (params.progressiveMediaUri != null && shouldAutoPlay(params)) View.VISIBLE else View.GONE
        binding.inlineMediaVolumeToggle.visibility = View.GONE
        binding.inlineMediaLoadingIndicator.visibility = View.GONE
        binding.inlineMediaPlayButton.visibility = if (params.embedType == EmbedType.VIDEO) View.VISIBLE else View.GONE
        binding.inlineMediaGifIndicator.visibility = if (isAnimated(params) && !shouldAutoPlay(params)) View.VISIBLE else View.GONE
    }

    private fun clearPlayer() {
        playerView?.player = null
        appMediaPlayer?.release()
        appMediaPlayer = null
    }

    private fun clearPlayerAndSubscriptions() {
        clearPlayer()
        subscriptions.unsubscribe()
        unsubscribeSignal.onNext(null)
    }

    private fun shouldAutoPlay(params: ViewParams?) = params != null && (!isAnimated(params) || params.autoPlayGifs)

    private fun isAnimated(params: ViewParams) =
        EmbedResourceUtils.isAnimated(params.embedType, params.previewImage?.url)

    data class ViewParams(
        val previewImage: RenderableEmbedMedia?,
        val progressiveMediaUri: String?,
        val embedType: EmbedType?,
        val targetWidth: Int?,
        val targetHeight: Int?,
        val autoPlayGifs: Boolean,
    )
}
