package com.discord.widgets.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.databinding.WidgetMediaBinding
import com.discord.databinding.WidgetMediaGalleryPageBinding
import com.discord.i18n.i18nFormat
import com.discord.player.MediaSource
import com.discord.player.MediaType
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.io.UserDownloadManager
import com.discord.utilities.io.UserDownloadResult
import com.discord.utilities.io.UserDownloadStartResult
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.ToastManager
import com.discord.utilities.view.extensions.addOnHeightChangedListener
import com.discord.views.ThemedColor
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.imagepipeline.image.ImageInfo
import com.facebook.samples.zoomable.ZoomableDraweeView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.material.appbar.AppBarLayout
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import java.io.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import rx.Observable
import rx.Subscription

/**
 * Fullscreen viewer for one image, GIF or video.
 *
 * This is where an attachment or an inline embed opens when it is tapped in chat. It is a single
 * surface that covers three cases:
 *
 *  * **Still image** — drawn into a [ZoomableDraweeView] at a size the CDN resizes to the screen,
 *    with pinch and double-tap zoom.
 *  * **Video** — played by ExoPlayer with transport controls; the still image stays on top as a
 *    cover frame until the first decoded frame arrives, so the screen never opens on black.
 *  * **GIFV** — the same video pipeline, but muted and looping.
 *
 * Tapping the media toggles the chrome: the toolbar slides off the top and the transport controls
 * slide off the bottom together, and for a video they hide themselves again after
 * [SHOW_CONTROLS_TIMEOUT_MS].
 *
 * The toolbar's overflow offers open-in-browser, download and share. Download and share act on
 * different URLs on purpose: sharing and browsing hand out the *source* URL, which is the page or
 * original asset the media came from, while downloading fetches the *playable* URL, which for a
 * video is the progressive stream rather than its poster frame.
 *
 * Reference: `com.discord.widgets.media.WidgetMedia`. Two of its collaborators do not exist in this
 * project yet and their behaviour is inlined here rather than approximated away:
 * `com.discord.player.AppMediaPlayer` (ExoPlayer is driven directly, with the reference's event
 * mapping reproduced as [PlayerEvent]) and `com.discord.utilities.display.DisplayUtils`
 * ([resizeToFitScreen]). Both should collapse into the real types when those land.
 */
class WidgetMedia : AppFragment(R.layout.widget_media) {

    private val viewModel: WidgetMediaViewModel by appViewModels { WidgetMediaViewModel() }

    private var binding: WidgetMediaBinding? = null
    private val bound get() = requireNotNull(binding)

    private val toastManager = ToastManager()

    /** Still image to draw; for a video this is the poster frame. */
    private lateinit var imageUri: Uri

    /** Non-null only when this media is playable. */
    private var mediaSource: MediaSource? = null

    /** The progressive stream URL, or null for a still image. */
    private var videoUrl: String? = null

    private var player: ExoPlayer? = null
    private var positionPollingSubscription: Subscription? = null

    private var controlsAnimationAction: ControlsAnimationAction? = null
    private var controlsAnimator: ValueAnimator? = null
    private var controlsVisibilitySubscription: Subscription? = null

    private var toolbarHeight: Int = 0
    private var playerControlsHeight: Int = 0

    /**
     * Distinguishes "the user paused" from "the fragment paused the player".
     *
     * Without it, backgrounding the screen would be recorded as an explicit pause and playback would
     * not resume when the user came back.
     */
    private var playerPausedByFragmentLifecycle: Boolean = false
    private var gallery: List<MediaGalleryItem> = emptyList()
    private var galleryIndex: Int = 0
    private val galleryAdapter = GalleryAdapter()
    private val galleryPageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            galleryIndex = position
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) stopGalleryPlaybackForSwipe()
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                val item = gallery.getOrNull(galleryIndex) ?: return
                bindMedia(item.args, item.viewerContext, galleryPage = true)
            }
        }
    }

    // region lifecycle

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetMediaBinding.bind(view).also { this.binding = it }

        setScreenStatusBarColor(ThemedColor.resolve(view.context, R.attr.primary_900))

        // The video surface swallows touches, so it needs the same tap handler as the container.
        binding.mediaPlayerView.videoSurfaceView?.setOnClickListener { onMediaClick() }
        var videoTouchDownX = 0f
        var videoTouchDownY = 0f
        binding.mediaPlayerView.setOnTouchListener { _, event ->
            if (gallery.isEmpty()) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                videoTouchDownX = event.x
                videoTouchDownY = event.y
            }
            MotionEvent.obtain(event).also { forwarded ->
                binding.mediaGallery.dispatchTouchEvent(forwarded)
                forwarded.recycle()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP &&
                hypot((event.x - videoTouchDownX).toDouble(), (event.y - videoTouchDownY).toDouble()) < 20.0
            ) onMediaClick()
            true
        }
        binding.mediaContainer.canStartGesture = { !isImageZoomed() }
        binding.mediaContainer.onVerticalDrag = ::dragToDismiss
        binding.mediaContainer.onVerticalRelease = ::releaseToDismiss
        binding.mediaGallery.adapter = galleryAdapter
        binding.mediaGallery.registerOnPageChangeCallback(galleryPageCallback)
        binding.mediaContainer.setOnClickListener { onMediaClick() }
        binding.mediaClose.setOnClickListener { activity?.finish() }

        // The two chrome heights are the travel distances of the hide/show animation and are only
        // known once each has been laid out.
        binding.actionBarToolbarLayout.addOnHeightChangedListener { height, _ -> toolbarHeight = height }
        binding.mediaBottomChrome.addOnHeightChangedListener { height, _ -> playerControlsHeight = height }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()

        bound.mediaImage.visibility = if (viewModel.showCoverFrame) View.VISIBLE else View.GONE
        bound.mediaLoadingIndicator.visibility = if (viewModel.showCoverFrame) View.VISIBLE else View.GONE

        val extras = launchExtras
        gallery = galleryExtra(extras)
        galleryIndex = extras.getInt(INTENT_GALLERY_INDEX, 0).coerceIn(0, (gallery.size - 1).coerceAtLeast(0))
        val item = gallery.getOrNull(galleryIndex)
        if (item != null) {
            bound.mediaGallery.visibility = View.VISIBLE
            bound.mediaImage.visibility = View.GONE
            galleryAdapter.notifyDataSetChanged()
            bound.mediaGallery.setCurrentItem(galleryIndex, false)
            bindMedia(item.args, item.viewerContext, galleryPage = true)
        } else {
            bound.mediaGallery.visibility = View.GONE
            bindMedia(mediaArgsExtra(extras), viewerContextExtra(extras))
        }
    }

    private fun bindMedia(args: MediaLaunchArgs, viewerContext: MediaViewerContext?, galleryPage: Boolean = false) {
        if (galleryPage && viewModel.currentMediaUri != args.uri) {
            viewModel.currentMediaUri = args.uri
            viewModel.isPlaying = true
            viewModel.showCoverFrame = true
            viewModel.currentPlayerPositionMs = 0L
        }
        player?.release()
        player = null
        stopPositionPolling()
        bound.mediaPlayerView.player = null
        bound.mediaLoadingIndicator.visibility = if (galleryPage) View.GONE else View.VISIBLE
        if (!galleryPage) {
            bound.mediaImage.visibility = View.VISIBLE
            MGImages.cancelImageRequests(bound.mediaImage)
            bound.mediaImage.getZoomableController().run {
                setScale(getMinimumScale(), bound.mediaImage.width / 2f, bound.mediaImage.height / 2f, false)
            }
        }
        val sourceUri = Uri.parse(args.uri ?: args.previewImageUri)
        imageUri = Uri.parse(args.previewImageUri)
        mediaSource = if (args.progressiveMediaUri != null && args.mediaType != null) {
            MediaSource.create(args.mediaType, args.progressiveMediaUri, FEATURE_TAG)
        } else null
        videoUrl = mediaSource?.progressiveMediaUri?.toString()

        // Downloading a video must fetch the stream, not its poster frame.
        val downloadUri = videoUrl?.let(Uri::parse) ?: imageUri

        val title = args.title
        val titleSubtext = if (title.isNullOrBlank()) sourceUri.toString() else title

        bound.mediaMore.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menuInflater.inflate(R.menu.menu_media, menu)
                setOnMenuItemClickListener { item ->
                    onMenuItemSelected(item, sourceUri, downloadUri, title, titleSubtext)
                    true
                }
                show()
            }
        }
        bound.mediaAuthor.visibility = if (viewerContext == null) View.GONE else View.VISIBLE
        viewerContext?.let { context ->
            bound.mediaAuthorName.text = context.authorName
            bound.mediaTimestamp.text = context.timestampMs?.let {
                TimeUtils.toReadableTimeString(requireContext(), it)
            }
            bound.mediaAuthorAvatar.setImageURI(context.avatarUrl)
        }
        bound.mediaVolume.setOnClickListener { toggleMute(bound.mediaVolume) }
        bound.mediaBottomChrome.visibility = if (isVideo()) View.VISIBLE else View.GONE

        if (!galleryPage) configureMediaImage()
        val isVideo = mediaSource != null
        bound.mediaPlayerView.visibility = if (isVideo) View.VISIBLE else View.GONE
        bound.mediaPlayerView.alpha = if (galleryPage && isVideo) 0f else 1f
        showControls()

        playerPausedByFragmentLifecycle = false
        mediaSource?.let(::startPlayback)
    }

    override fun onPause() {
        super.onPause()
        stopPositionPolling()
        val exoPlayer = player ?: return
        if (exoPlayer.playWhenReady) {
            // Flagged so the resulting pause callback is not mistaken for the user's own.
            playerPausedByFragmentLifecycle = true
            exoPlayer.playWhenReady = false
        }
    }

    override fun onDestroyView() {
        controlsAnimator?.cancel()
        controlsAnimator = null
        controlsVisibilitySubscription?.unsubscribe()
        controlsVisibilitySubscription = null
        stopPositionPolling()
        bound.mediaImage.setTapListener(null)
        bound.mediaContainer.canStartGesture = { true }
        bound.mediaContainer.onVerticalDrag = null
        bound.mediaContainer.onVerticalRelease = null
        bound.mediaPlayerView.setOnTouchListener(null)
        bound.mediaGallery.unregisterOnPageChangeCallback(galleryPageCallback)
        bound.mediaGallery.adapter = null
        MGImages.cancelImageRequests(bound.mediaImage)
        // The player outlives the view, so it must let go of the views the view hierarchy owns.
        bound.mediaPlayerView.player = null
        bound.mediaPlayerControlView.player = null
        binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        toastManager.close()
        super.onDestroy()
    }

    // endregion

    // region image

    /**
     * Binds the still image and its gestures.
     *
     * Long press is disabled because the whole surface is a tap target, and a long press would
     * otherwise fight the double-tap-drag zoom below.
     */
    private fun configureMediaImage() {
        bound.mediaImage.setIsLongpressEnabled(false)
        bound.mediaImage.setTapListener(ZoomGestureListener())
        bound.mediaImage.getZoomableController().setZoomTransitionDuration(ZOOM_ANIMATION_DURATION_MS)
        MGImages.setScaleType(bound.mediaImage, ScalingUtils.ScaleType.FIT_CENTER)
        MGImages.setImage(
            view = bound.mediaImage,
            urls = listOf(bound.mediaImage.context.getFormattedUrl(imageUri)),
            controllerListener = object : BaseControllerListener<ImageInfo>() {
                override fun onFailure(id: String?, throwable: Throwable?) {
                    super.onFailure(id, throwable)
                    handleImageProgressComplete()
                }

                override fun onFinalImageSet(id: String?, imageInfo: ImageInfo?, animatable: Animatable?) {
                    super.onFinalImageSet(id, imageInfo, animatable)
                    handleImageProgressComplete()
                }
            },
        )
    }

    /**
     * Asks the media proxy for a copy no larger than the screen.
     *
     * An animated GIF is requested as-is; everything else is requested as a still, because the
     * viewer draws one frame and a still is far smaller.
     */
    private fun Context.getFormattedUrl(uri: Uri): String {
        val source = uri.toString()
        if ("size" in uri.queryParameterNames) return source
        val extras = launchExtras
        val fitted = resizeToFitScreen(
            this,
            Rect(0, 0, extras.getInt(INTENT_WIDTH, 0), extras.getInt(INTENT_HEIGHT, 0)),
        )
        val format = mediaFormatQuery(uri.lastPathSegment, IconUtils.staticImageExtension)
        val separator = if ('?' in source) '&' else '?'
        return "$source${separator}width=${fitted.width()}&height=${fitted.height()}$format"
    }

    /** A video keeps its spinner up until the player says otherwise; a still image is done here. */
    private fun handleImageProgressComplete() {
        if (videoUrl == null) hideLoadingIndicator()
    }

    // endregion

    // region playback

    /**
     * The subset of ExoPlayer's callbacks this screen reacts to.
     *
     * The reference receives these from `AppMediaPlayer` as an Rx stream; the mapping from raw
     * player callbacks is reproduced in [attachPlayerListener] so the handling below stays
     * recognisable against it.
     */
    private sealed interface PlayerEvent {
        /** The player is waiting on data. */
        data object Buffering : PlayerEvent

        /** The player has decoded enough to show a frame. */
        data object Ready : PlayerEvent

        /** Playback reached the end of the media. */
        data object Ended : PlayerEvent

        data object Playing : PlayerEvent

        data object Paused : PlayerEvent

        data class CurrentPositionUpdate(val positionMs: Long) : PlayerEvent
    }

    private fun handlePlayerEvent(event: PlayerEvent) {
        when (event) {
            PlayerEvent.Buffering -> if (gallery.isEmpty()) showLoadingIndicator()
            PlayerEvent.Ready -> {
                // The first real frame is on screen, so the cover frame can come down for good.
                bound.mediaImage.visibility = View.GONE
                bound.mediaPlayerView.alpha = 1f
                viewModel.showCoverFrame = false
                hideLoadingIndicator()
            }
            PlayerEvent.Ended -> hideLoadingIndicator()
            PlayerEvent.Playing -> viewModel.isPlaying = true
            // A pause this screen caused is not the user's decision and must not be remembered.
            PlayerEvent.Paused -> if (!playerPausedByFragmentLifecycle) viewModel.isPlaying = false
            is PlayerEvent.CurrentPositionUpdate -> viewModel.currentPlayerPositionMs = event.positionMs
        }
    }

    private fun startPlayback(source: MediaSource) {
        val loopMedia = source.mediaType == MediaType.GIFV
        val exoPlayer = player ?: ExoPlayer.Builder(requireContext()).build().also {
            player = it
            attachPlayerListener(it)
        }

        bound.mediaPlayerView.player = exoPlayer
        bound.mediaPlayerControlView.player = exoPlayer
        // A GIF is decoration: it repeats and it is never allowed to make noise.
        exoPlayer.repeatMode = if (loopMedia) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exoPlayer.volume = if (loopMedia) 0f else 1f
        updateVolumeButton(bound.mediaVolume)
        exoPlayer.setMediaItem(MediaItem.fromUri(source.progressiveMediaUri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = viewModel.isPlaying
        if (viewModel.currentPlayerPositionMs > 0L) exoPlayer.seekTo(viewModel.currentPlayerPositionMs)
    }

    private fun attachPlayerListener(exoPlayer: ExoPlayer) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> handlePlayerEvent(PlayerEvent.Buffering)
                    Player.STATE_READY -> {
                        startPositionPolling()
                    }
                    Player.STATE_ENDED -> {
                        handlePlayerEvent(PlayerEvent.Ended)
                        stopPositionPolling()
                        handlePlayerEvent(PlayerEvent.CurrentPositionUpdate(exoPlayer.currentPosition))
                    }
                }
            }

            override fun onRenderedFirstFrame() = handlePlayerEvent(PlayerEvent.Ready)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handlePlayerEvent(if (isPlaying) PlayerEvent.Playing else PlayerEvent.Paused)
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.e(LOG_TAG, "Playback error for feature: ${mediaSource?.featureTag.orEmpty()}", error)
            }
        })
    }

    /**
     * Records where playback has reached, twice a second.
     *
     * ExoPlayer does not push position updates, and the position is the one piece of playback state
     * that has to survive a rotation, so it is sampled instead.
     */
    private fun startPositionPolling() {
        stopPositionPolling()
        Observable.interval(POSITION_POLL_INTERVAL_MS, POSITION_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .ui(this)
            .appSubscribe(
                errorClass = WidgetMedia::class.java,
                subscriptionHandler = { positionPollingSubscription = it },
            ) {
                val exoPlayer = player ?: return@appSubscribe
                handlePlayerEvent(PlayerEvent.CurrentPositionUpdate(exoPlayer.currentPosition))
            }
    }

    private fun stopPositionPolling() {
        positionPollingSubscription?.unsubscribe()
        positionPollingSubscription = null
    }

    private fun isVideo(): Boolean = mediaSource?.mediaType == MediaType.VIDEO

    private fun toggleMute(button: ImageButton) {
        val player = player ?: return
        player.volume = if (player.volume > 0f) 0f else 1f
        updateVolumeButton(button)
    }

    private fun updateVolumeButton(button: ImageButton) {
        val muted = player?.volume == 0f
        button.setImageResource(
            if (muted) R.drawable.ic_volume_off_24dp else R.drawable.ic_volume_up_white_24dp,
        )
        button.setContentDescription(getString(if (muted) R.string.unmute else R.string.mute))
    }

    // endregion

    // region chrome

    private fun onMediaClick() {
        if (getToolbarTranslationY() < 0f) showControls() else hideControls()
    }

    private fun getToolbarTranslationY(): Float = bound.actionBarToolbarLayout.translationY

    private fun showControls() {
        if (isVideo()) {
            bound.mediaPlayerControlView.show()
            controlsVisibilitySubscription?.unsubscribe()
            Observable.timer(SHOW_CONTROLS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetMedia::class.java,
                    subscriptionHandler = { controlsVisibilitySubscription = it },
                ) { hideControls() }
        } else {
            // A still image has no transport, so the control bar is never part of its chrome.
            bound.mediaPlayerControlView.hide()
        }
        animateControlsTo(ControlsAnimationAction.SHOW, 0f)
    }

    private fun hideControls() {
        animateControlsTo(ControlsAnimationAction.HIDE, -toolbarHeight.toFloat())
    }

    private fun animateControlsTo(action: ControlsAnimationAction, targetTranslationY: Float) {
        if (controlsAnimationAction == action) return
        controlsAnimationAction = action
        controlsAnimator?.cancel()
        controlsAnimator = ValueAnimator.ofFloat(getToolbarTranslationY(), targetTranslationY)
            .also(::configureAndStartControlsAnimation)
    }

    /**
     * Drives the toolbar off the top and the transport controls off the bottom as one movement.
     *
     * The two travel different distances, so the control bar's offset is the toolbar's scaled by the
     * ratio of their heights and inverted — they meet the edges of the screen at the same instant.
     */
    private fun configureAndStartControlsAnimation(animator: ValueAnimator) {
        animator.interpolator = FastOutSlowInInterpolator()
        animator.duration = VERTICAL_CONTROLS_ANIMATION_DURATION_MS
        animator.addUpdateListener { running ->
            val translationY = running.animatedValue as Float
            bound.actionBarToolbarLayout.translationY = translationY
            // toolbarHeight is guarded as well as the reference's playerControlsHeight: it is only
            // known after layout, and a zero would make the ratio below a division by zero.
            if (isVideo() && playerControlsHeight > 0 && toolbarHeight > 0) {
                bound.mediaBottomChrome.translationY =
                    -translationY / (toolbarHeight.toFloat() / playerControlsHeight)
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                controlsAnimationAction = null
            }
        })
        animator.start()
    }

    private fun showLoadingIndicator() {
        bound.mediaLoadingIndicator.visibility = View.VISIBLE
    }

    private fun hideLoadingIndicator() {
        bound.mediaLoadingIndicator.visibility = View.GONE
    }

    /** Which direction the chrome is currently animating, or null when it is at rest. */
    private enum class ControlsAnimationAction { SHOW, HIDE }

    // endregion

    // region toolbar actions

    /**
     * Open-in-browser and share act on [sourceUri]; download acts on [downloadUri].
     *
     * They differ for a video: the source is the page or original asset the media came from, while
     * the download target is the progressive stream the player is actually showing.
     */
    private fun onMenuItemSelected(
        menuItem: MenuItem,
        sourceUri: Uri,
        downloadUri: Uri,
        title: String?,
        titleSubtext: String,
    ) {
        val context = context ?: return
        when (menuItem.itemId) {
            R.id.menu_media_browser -> UriHandler.handleOrUntrusted(context, sourceUri.toString())
            R.id.menu_media_download -> requestMediaDownload {
                startDownload(context, downloadUri, title, titleSubtext)
            }
            R.id.menu_media_share -> performChooserSendIntent(context, sourceUri.toString())
        }
    }

    private fun startDownload(context: Context, downloadUri: Uri, title: String?, description: String?) {
        val started = UserDownloadManager.enqueue(
            context = context,
            url = downloadUri.toString(),
            fileName = title,
            title = title,
            description = description,
        ) { result ->
            if (!isAdded) return@enqueue
            when (result) {
                is UserDownloadResult.Success ->
                    toastManager.show(context, context.i18nFormat(R.string.download_file_complete, "fileName" to result.title))
                is UserDownloadResult.Failure -> toastManager.show(context, getString(R.string.download_failed))
            }
        }
        if (started !is UserDownloadStartResult.Enqueued) {
            toastManager.show(context, getString(R.string.download_failed))
        }
    }

    /**
     * Hands the media's source URL to the system share sheet.
     *
     * The reference calls `IntentUtils.performChooserSendIntent`; that utility has not been
     * reconstructed, so its two-line body lives here until it exists.
     */
    private fun performChooserSendIntent(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
    }

    // endregion

    @Suppress("DEPRECATION")
    private fun mediaSourceExtra(extras: Bundle): MediaSource? = extras.getParcelable(INTENT_MEDIA_SOURCE)

    @Suppress("DEPRECATION")
    private fun galleryExtra(extras: Bundle): List<MediaGalleryItem> =
        (extras.getSerializable(INTENT_GALLERY) as? ArrayList<*>)?.filterIsInstance<MediaGalleryItem>().orEmpty()

    private fun mediaArgsExtra(extras: Bundle) = MediaLaunchArgs(
        extras.getString(INTENT_TITLE),
        extras.getString(INTENT_URL),
        mediaSourceExtra(extras)?.progressiveMediaUri?.toString(),
        extras.getString(INTENT_IMAGE_URL),
        extras.getInt(INTENT_WIDTH).takeIf { it > 0 },
        extras.getInt(INTENT_HEIGHT).takeIf { it > 0 },
        mediaSourceExtra(extras)?.mediaType,
    )

    @Suppress("DEPRECATION")
    private fun viewerContextExtra(extras: Bundle): MediaViewerContext? =
        extras.getSerializable(INTENT_VIEWER_CONTEXT) as? MediaViewerContext

    /**
     * The screen's arguments.
     *
     * The reference reads these off `mostRecentIntent`, because `AppActivity` hosts whichever screen
     * the launch intent names. Fragment arguments remain a fallback for restored instances.
     */
    private val launchExtras: Bundle
        get() = mostRecentIntent.extras?.takeIf { it.containsKey(INTENT_IMAGE_URL) }
            ?: arguments
            ?: Bundle.EMPTY

    /**
     * Double-tap zoom, plus the "double tap then drag" scrub that scales continuously.
     *
     * A double tap toggles between fitted and 2x, animated. Holding the second tap and dragging
     * instead scales live: dragging down grows the image, dragging up shrinks it, anchored on the
     * point that was double-tapped so the content under the finger stays put.
     */
    private inner class ZoomGestureListener : GestureDetector.SimpleOnGestureListener() {
        private val doubleTapViewPoint = PointF()
        private var doubleTapScale = 1f
        private var doubleTapScroll = false

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onMediaClick()
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            val controller = bound.mediaImage.getZoomableController()
            val viewPoint = PointF(e.x, e.y)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    doubleTapViewPoint.set(viewPoint)
                    doubleTapScale = controller.getScale()
                }
                MotionEvent.ACTION_MOVE -> {
                    doubleTapScroll = doubleTapScroll || shouldStartDoubleTapScroll(viewPoint)
                    if (doubleTapScroll) {
                        controller.setScale(clampScale(calcScale(viewPoint)), doubleTapViewPoint.x, doubleTapViewPoint.y, false)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (doubleTapScroll) {
                        controller.setScale(clampScale(calcScale(viewPoint)), doubleTapViewPoint.x, doubleTapViewPoint.y, false)
                    } else {
                        val target =
                            if (controller.getScale() < DOUBLE_TAP_ZOOM_THRESHOLD_SCALE) DOUBLE_TAP_ZOOM_SCALE else 1f
                        controller.setScale(clampScale(target), viewPoint.x, viewPoint.y, true)
                    }
                    doubleTapScroll = false
                }
            }
            return true
        }

        /** Scale tracks vertical travel from the anchor: down magnifies, up reduces. */
        private fun calcScale(currentViewPoint: PointF): Float {
            val travel = currentViewPoint.y - doubleTapViewPoint.y
            val factor = 1f + abs(travel) * DOUBLE_TAP_SCROLL_SCALE_PER_PX
            return if (travel < 0) doubleTapScale / factor else doubleTapScale * factor
        }

        /** A double tap only becomes a scrub once the finger has actually moved. */
        private fun shouldStartDoubleTapScroll(viewPoint: PointF): Boolean = hypot(
            (viewPoint.x - doubleTapViewPoint.x).toDouble(),
            (viewPoint.y - doubleTapViewPoint.y).toDouble(),
        ) > DOUBLE_TAP_SCROLL_THRESHOLD_PX

        private fun clampScale(scale: Float): Float {
            val controller = bound.mediaImage.getZoomableController()
            // The controller silently ignores an out-of-range scale, which would freeze the drag.
            return scale.coerceIn(controller.getMinimumScale(), controller.getMaximumScale())
        }
    }

    private fun dragToDismiss(distance: Float) {
        val progress = (abs(distance) / bound.mediaContainer.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        bound.mediaContainer.translationY = distance
        bound.mediaContainer.alpha = 1f - progress * 0.85f
        val scale = 1f - progress * 0.08f
        bound.mediaContainer.scaleX = scale
        bound.mediaContainer.scaleY = scale
    }

    private fun stopGalleryPlaybackForSwipe() {
        stopPositionPolling()
        player?.release()
        player = null
        bound.mediaPlayerView.player = null
        bound.mediaPlayerControlView.player = null
        bound.mediaPlayerView.visibility = View.GONE
    }

    private fun releaseToDismiss(distance: Float) {
        if (abs(distance) > bound.mediaContainer.height * 0.15f) {
            bound.mediaContainer.animate()
                .translationY(if (distance < 0f) -bound.mediaContainer.height.toFloat() else bound.mediaContainer.height.toFloat())
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(180L)
                .withEndAction { activity?.finish() }
                .start()
        } else {
            bound.mediaContainer.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        }
    }

    private fun isImageZoomed(): Boolean = if (gallery.isNotEmpty()) galleryAdapter.isZoomed(galleryIndex)
    else bound.mediaImage.visibility == View.VISIBLE && bound.mediaImage.getZoomableController().getScale() > 1.01f

    private inner class GalleryAdapter : RecyclerView.Adapter<GalleryAdapter.Holder>() {
        override fun getItemCount() = gallery.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            WidgetMediaGalleryPageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(gallery[position])

        override fun onViewRecycled(holder: Holder) {
            holder.binding.mediaGalleryImage.getZoomableController().reset()
            bound.mediaGallery.isUserInputEnabled = true
            MGImages.cancelImageRequests(holder.binding.mediaGalleryImage)
            super.onViewRecycled(holder)
        }

        fun isZoomed(position: Int): Boolean {
            val recycler = bound.mediaGallery.getChildAt(0) as? RecyclerView
            val holder = recycler?.findViewHolderForAdapterPosition(position) as? Holder
            return holder?.binding?.mediaGalleryImage?.getZoomableController()?.getScale()?.let { it > 1.01f } == true
        }

        inner class Holder(val binding: WidgetMediaGalleryPageBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: MediaGalleryItem) {
                val image = binding.mediaGalleryImage
                image.setIsLongpressEnabled(false)
                image.getZoomableController().setZoomTransitionDuration(ZOOM_ANIMATION_DURATION_MS)
                image.onZoomChanged = { zoomed -> bound.mediaGallery.isUserInputEnabled = !zoomed }
                image.setTapListener(object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onMediaClick(); return true }
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        val controller = image.getZoomableController()
                        val target = if (controller.getScale() < DOUBLE_TAP_ZOOM_THRESHOLD_SCALE) DOUBLE_TAP_ZOOM_SCALE else 1f
                        controller.setScale(target, e.x, e.y, true)
                        return true
                    }
                })
                MGImages.setScaleType(image, ScalingUtils.ScaleType.FIT_CENTER)
                MGImages.setImage(image, item.args.previewImageUri)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "WidgetMedia"

        private const val INTENT_TITLE = "INTENT_TITLE"
        private const val INTENT_URL = "INTENT_MEDIA_URL"
        private const val INTENT_IMAGE_URL = "INTENT_IMAGE_URL"
        private const val INTENT_WIDTH = "INTENT_MEDIA_WIDTH"
        private const val INTENT_HEIGHT = "INTENT_MEDIA_HEIGHT"
        private const val INTENT_MEDIA_SOURCE = "INTENT_MEDIA_SOURCE"
        private const val INTENT_VIEWER_CONTEXT = "INTENT_VIEWER_CONTEXT"
        private const val INTENT_GALLERY = "INTENT_GALLERY"
        private const val INTENT_GALLERY_INDEX = "INTENT_GALLERY_INDEX"

        private const val SHOW_CONTROLS_TIMEOUT_MS = 3000L
        private const val VERTICAL_CONTROLS_ANIMATION_DURATION_MS = 200L
        private const val ZOOM_ANIMATION_DURATION_MS = 300L
        private const val POSITION_POLL_INTERVAL_MS = 500L

        private const val DOUBLE_TAP_ZOOM_SCALE = 2f

        /** Below this the next double tap zooms in, at or above it the next one zooms back out. */
        private const val DOUBLE_TAP_ZOOM_THRESHOLD_SCALE = 1.5f

        private const val DOUBLE_TAP_SCROLL_THRESHOLD_PX = 20.0
        private const val DOUBLE_TAP_SCROLL_SCALE_PER_PX = 0.001f

        /**
         * Attributes a playback failure to the surface that asked for it. The reference passes this
         * exact literal, which is presumably a leftover from `javaClass.simpleName`; it is kept
         * verbatim so log lines match the shipping build.
         */
        private const val FEATURE_TAG = "javaClass"

        /** Opens [attachment] fullscreen. Only images and videos belong here. */
        @JvmStatic
        fun launch(context: Context, attachment: MessageAttachment) = launch(context, launchArgs(attachment), null)

        @JvmStatic
        fun launch(context: Context, attachment: MessageAttachment, viewerContext: MediaViewerContext) =
            launch(context, launchArgs(attachment), viewerContext)

        @JvmStatic
        fun launchImage(context: Context, url: String, title: String?) =
            launch(context, launchArgs(url, title), null)

        /** Opens [embed]'s media fullscreen. */
        @JvmStatic
        fun launch(context: Context, embed: MessageEmbed) = launch(context, launchArgs(embed), null)

        @JvmStatic
        @JvmOverloads
        fun launchGallery(
            context: Context,
            attachments: List<MessageAttachment>,
            selectedIndex: Int,
            viewerContexts: List<MediaViewerContext?> = emptyList(),
        ) {
            val items = attachments.mapIndexed { index, attachment ->
                MediaGalleryItem(launchArgs(attachment), viewerContexts.getOrNull(index))
            }
            val selected = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            items.getOrNull(selected)?.let { item ->
                presentScreen(
                    context,
                    WidgetMedia::class.java,
                    intentFor(item.args)
                        .putExtra(INTENT_VIEWER_CONTEXT, item.viewerContext)
                        .putExtra(INTENT_GALLERY, ArrayList(items))
                        .putExtra(INTENT_GALLERY_INDEX, selected),
                )
            }
        }

        /** Builds the launch intent. Its extras are the screen's whole argument contract. */
        private fun intentFor(args: MediaLaunchArgs): Intent {
            // Playback needs both a stream and a kind; without either this is a still image.
            val mediaSource = if (args.progressiveMediaUri != null && args.mediaType != null) {
                MediaSource.create(args.mediaType, args.progressiveMediaUri, FEATURE_TAG)
            } else {
                null
            }
            return Intent()
                .putExtra(INTENT_TITLE, args.title)
                // The source URL is what share and open-in-browser hand out, so it prefers the page
                // or original asset over the preview image.
                .putExtra(INTENT_URL, args.uri ?: args.previewImageUri)
                .putExtra(INTENT_IMAGE_URL, args.previewImageUri)
                .putExtra(INTENT_WIDTH, args.width)
                .putExtra(INTENT_HEIGHT, args.height)
                .putExtra(INTENT_MEDIA_SOURCE, mediaSource)
        }

        private fun launch(context: Context, args: MediaLaunchArgs, viewerContext: MediaViewerContext?) =
            presentScreen(
                context,
                WidgetMedia::class.java,
                intentFor(args).putExtra(INTENT_VIEWER_CONTEXT, viewerContext),
            )
    }
}

data class MediaViewerContext(
    val authorName: String?,
    val avatarUrl: String?,
    val timestampMs: Long?,
) : Serializable

internal data class MediaGalleryItem(
    val args: MediaLaunchArgs,
    val viewerContext: MediaViewerContext?,
) : Serializable

/** Shows [screen] through the reference-shaped single-activity screen router. */
internal fun presentScreen(context: Context, screen: Class<out AppFragment>, launchIntent: Intent) =
    AppScreenRouter.launch(context, screen, launchIntent)

/**
 * Everything [WidgetMedia] needs, decided before any Android type is involved.
 *
 * @property uri what share and open-in-browser hand out — the page or original asset.
 * @property progressiveMediaUri the stream to play, or null when the media is a still image.
 * @property previewImageUri the still to draw; for a video this is its poster frame.
 * @property width intrinsic width of [previewImageUri], used to size the CDN request.
 * @property height intrinsic height of [previewImageUri].
 * @property mediaType null when there is nothing to play.
 */
internal data class MediaLaunchArgs(
    val title: String?,
    val uri: String?,
    val progressiveMediaUri: String?,
    val previewImageUri: String?,
    val width: Int?,
    val height: Int?,
    val mediaType: MediaType?,
) : Serializable

internal fun launchArgs(imageUrl: String, title: String?): MediaLaunchArgs {
    val source = profileImageUrl(imageUrl)
    return MediaLaunchArgs(title, source, null, source, 3072, 3072, null)
}

internal fun launchArgs(attachment: MessageAttachment): MediaLaunchArgs {
    val isVideo = attachment.type == MessageAttachmentType.VIDEO
    val preview = if (isVideo) mediaVideoPreviewUrl(attachment.proxyUrl, attachment.width, attachment.height) else attachment.proxyUrl
    return MediaLaunchArgs(
        title = attachment.filename,
        uri = attachment.proxyUrl,
        // Only a video has a stream to play; an image's proxy URL is the image itself.
        progressiveMediaUri = attachment.proxyUrl.takeIf { isVideo },
        previewImageUri = preview,
        width = attachment.width,
        height = attachment.height,
        mediaType = MediaType.VIDEO.takeIf { isVideo },
    )
}

internal fun mediaVideoPreviewUrl(url: String, width: Int? = null, height: Int? = null): String {
    val proxied = url.replace("https://cdn.discordapp.com/", "https://media.discordapp.net/")
    val dimensions = if (width != null && height != null && width > 0 && height > 0) {
        fitWithin(width, height, 1280, 720)
    } else {
        1280 to 720
    }
    return "$proxied${if ('?' in proxied) '&' else '?'}format=webp&width=${dimensions.first}&height=${dimensions.second}"
}

internal fun launchArgs(embed: MessageEmbed): MediaLaunchArgs {
    val preview = with(EmbedResourceUtils) { embed.getPreviewImage() }
    return MediaLaunchArgs(
        title = embed.title,
        uri = embed.url,
        // Only the two playable embed kinds carry a stream; every other kind is a still.
        progressiveMediaUri = when (embed.type) {
            EmbedType.VIDEO, EmbedType.GIFV -> embed.video?.let { it.proxyUrl ?: it.url }
            else -> null
        },
        previewImageUri = preview?.url,
        width = preview?.width,
        height = preview?.height,
        // An embed only reaches the viewer from a playable row, so a non-GIFV is a video.
        mediaType = if (embed.type == EmbedType.GIFV) MediaType.GIFV else MediaType.VIDEO,
    )
}

/**
 * Paints the status bar behind a fullscreen media surface.
 *
 * The reference calls `ColorCompat.setStatusBarColor`, which is not reconstructed in this project;
 * its body is exactly the three window calls below. Both screens in this package need it.
 */
@Suppress("DEPRECATION")
internal fun androidx.fragment.app.Fragment.setScreenStatusBarColor(color: Int) {
    val window = activity?.window ?: return
    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    window.statusBarColor = color
}

/**
 * The media proxy's `format` parameter for a preview request.
 *
 * An animated GIF is fetched untouched — asking the proxy to re-encode it would flatten it to one
 * frame. Anything else is fetched as a still, which is what the viewer draws and is far smaller.
 */
internal fun mediaFormatQuery(lastPathSegment: String?, staticImageExtension: String): String = when {
    // AVIF only decodes natively from API 31, so it is transcoded regardless of what it is for.
    lastPathSegment?.endsWith(".avif", ignoreCase = true) == true -> "&format=$staticImageExtension"
    lastPathSegment?.endsWith(".gif", ignoreCase = true) == true -> ""
    else -> "&format=$staticImageExtension"
}

internal fun profileImageUrl(url: String): String =
    url.toHttpUrl().newBuilder()
        .setQueryParameter("quality", "lossless")
        .setQueryParameter("size", IconUtils.proxySizePx(IconUtils.AVATAR_MAX_SIZE_DP).toString())
        .build()
        .toString()

/**
 * Scales [input] down until it fits inside [bounds], preserving its aspect ratio. Never enlarges.
 *
 * Reference: `com.discord.utilities.display.DisplayUtils.resizeToFit`.
 */
internal fun resizeToFit(input: Rect, bounds: Rect): Rect {
    val (width, height) = fitWithin(input.width(), input.height(), bounds.width(), bounds.height())
    return Rect(0, 0, width, height)
}

/**
 * The arithmetic behind [resizeToFit], with no Android types.
 *
 * Width is constrained first, then height against the already-narrowed width, so an image that is
 * over budget in both directions ends up bounded by whichever limit binds harder.
 */
internal fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    var fittedWidth = width
    var fittedHeight = height
    if (fittedWidth > maxWidth) {
        fittedHeight = (maxWidth.toFloat() / fittedWidth * fittedHeight).toInt()
        fittedWidth = maxWidth
    }
    if (fittedHeight > maxHeight) {
        fittedWidth = (maxHeight.toFloat() / fittedHeight * fittedWidth).toInt()
        fittedHeight = maxHeight
    }
    return fittedWidth to fittedHeight
}

/** [resizeToFit] against the device's screen. */
internal fun resizeToFitScreen(context: Context, input: Rect): Rect {
    val metrics = context.resources.displayMetrics
    val bounds = Rect(
        0,
        0,
        minOf(metrics.widthPixels, MEDIA_PROXY_MAX_TARGET_RESOLUTION),
        minOf(metrics.heightPixels, MEDIA_PROXY_MAX_TARGET_RESOLUTION),
    )
    return resizeToFit(input, bounds)
}

/** The proxy will not render past this on either axis. */
internal const val MEDIA_PROXY_MAX_TARGET_RESOLUTION = 4096
