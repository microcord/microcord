package com.discord.widgets.changelog

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.media.MediaPlayer
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageButton
import android.widget.VideoView
import androidx.core.widget.NestedScrollView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import com.discord.utilities.view.extensions.getContentView
import com.discord.utilities.view.text.LinkifiedTextView
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.image.ImageInfo
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * The "What's New" screen shown after an update.
 *
 * The body, video, version and revision all arrive as intent extras, and each falls back to the
 * `change_log_md_*` string baked into the build — that is how the changelog ships offline: the
 * strings are the current release notes, and the extras only exist when a newer changelog was
 * fetched.
 *
 * The media slot is either a `.mp4` played inline or an animated image; [configureMedia] picks by
 * file extension. In both cases a play overlay is shown while paused and faded out while playing.
 */
class WidgetChangeLog : AppFragment(R.layout.widget_change_log) {

    /** Highest scroll depth reached, reported with the close event. */
    private var maxScrolledPercent: Int = 0
    private var openedTimestamp: Long = 0

    private val body: LinkifiedTextView
        get() = requireView().findViewById(R.id.change_log_body)
    private val scrollView: NestedScrollView
        get() = requireView().findViewById(R.id.change_log_scrollview)
    private val video: VideoView
        get() = requireView().findViewById(R.id.change_log_video)
    private val videoOverlay: SimpleDraweeView
        get() = requireView().findViewById(R.id.change_log_video_overlay)
    private val thumbnail: SimpleDraweeView
        get() = requireView().findViewById(R.id.change_log_thumbnail)

    private val version: String
        get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_VERSION) ?: getString(R.string.change_log_md_date)

    private val revision: String
        get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_REVISION)
            ?: getString(R.string.change_log_md_revision)

    /**
     * Reveals the play overlay once the animated thumbnail has actually loaded.
     *
     * Fresco hands back an [Animatable] only for animated images, which is also the signal that
     * tapping the overlay has something to start.
     */
    private val thumbnailControllerListener = object : BaseControllerListener<ImageInfo>() {
        override fun onFinalImageSet(id: String?, imageInfo: ImageInfo?, animatable: Animatable?) {
            super.onFinalImageSet(id, imageInfo, animatable)
            try {
                val overlay = videoOverlay
                overlay.visibility = if (animatable != null) View.VISIBLE else View.GONE
                overlay.setOnClickListener {
                    hideVideoOverlay()
                    thumbnail.controller?.animatable?.start()
                }
            } catch (e: Exception) {
                // The view is gone if the fragment was torn down while the image was loading.
                AppLog.e("Failed to set changelog thumbnail image.", e)
            }
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        openedTimestamp = ClockFactory.get().currentTimeMillis()
        track("change_log_opened", includeStats = false)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.whats_new)
        setActionBarSubtitle(getDateString(requireContext()))

        val bodyMarkdown = mostRecentIntent.getStringExtra(INTENT_EXTRA_BODY)
            ?: getString(R.string.change_log_md_body)
        body.setDraweeSpanStringBuilder(
            ChangeLogParser.parse(requireContext(), bodyMarkdown) { context, url, _ ->
                track("change_log_cta_clicked", mapOf("cta_type" to "inline_link"), includeStats = false)
                UriHandler.handle(context, url)
            },
        )

        configureMedia(
            mostRecentIntent.getStringExtra(INTENT_EXTRA_VIDEO) ?: getString(R.string.change_log_md_video),
        )
        configureFooter()

        scrollView.setOnScrollChangeListener { _: NestedScrollView?, _: Int, scrollY: Int, _: Int, _: Int ->
            val scrollable = max(scrollView.getContentView().height - scrollView.height, 1)
            maxScrolledPercent = max(maxScrolledPercent, scrollY * 100 / scrollable)
        }
    }

    override fun onPause() {
        if (video.isPlaying) video.pause()
        super.onPause()
    }

    /** The [VideoView] keeps a `MediaPlayer` alive through its listeners; drop them with the view. */
    override fun onDestroyView() {
        view?.findViewById<VideoView>(R.id.change_log_video)?.let {
            it.setOnPreparedListener(null)
            it.setOnCompletionListener(null)
        }
        super.onDestroyView()
    }

    override fun onDestroy() {
        changeLogSeenHandler?.invoke(version)
        track("change_log_closed")
        super.onDestroy()
    }

    private fun configureFooter() {
        val view = requireView()
        view.findViewById<ImageButton>(R.id.change_log_twitter).setOnClickListener {
            UriHandler.handle(requireContext(), getString(R.string.twitter_page_url))
        }
        view.findViewById<ImageButton>(R.id.change_log_facebook).setOnClickListener {
            UriHandler.handle(requireContext(), FACEBOOK_PAGE_URL)
        }
        view.findViewById<ImageButton>(R.id.change_log_instagram).setOnClickListener {
            UriHandler.handle(requireContext(), INSTAGRAM_PAGE_URL)
        }
    }

    /**
     * An `.mp4` plays in the [VideoView]; anything else is treated as an (animated) image and
     * loaded into the Fresco thumbnail instead.
     */
    private fun configureMedia(videoUrl: String) {
        val isVideo = videoUrl.endsWith(".mp4")
        video.visibility = if (isVideo) View.VISIBLE else View.GONE
        videoOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE

        if (!isVideo) {
            thumbnail.visibility = View.VISIBLE
            thumbnail.controller = Fresco.newDraweeControllerBuilder()
                .setOldController(thumbnail.controller)
                .setUri(videoUrl)
                .setAutoPlayAnimations(false)
                .setControllerListener(thumbnailControllerListener)
                .build()
            thumbnail.requestLayout()
            return
        }

        video.setVideoPath(videoUrl)
        video.setOnCompletionListener { showVideoOverlay() }
        video.setOnClickListener {
            if (video.isPlaying) {
                showVideoOverlay()
                video.pause()
            } else {
                hideVideoOverlay()
                video.start()
            }
            track("change_log_video_interacted", includeStats = false)
        }
        // Start-then-pause forces the first frame to render so the view is not a black box, and
        // gives the VideoView a measured height to wrap to.
        video.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.start()
            mediaPlayer.pause()
            video.layoutParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            video.requestLayout()
        }
    }

    /** The overlay scales up as it fades out, so it reads as "getting out of the way". */
    private fun hideVideoOverlay() {
        videoOverlay.fadeOut(
            fadeMillis = OVERLAY_FADE_MILLIS,
            additionalAnimation = { animator ->
                animator.scaleX(2f)
                animator.scaleY(2f)
            },
        )
    }

    private fun showVideoOverlay() {
        videoOverlay.fadeIn(
            fadeMillis = OVERLAY_FADE_MILLIS,
            setup = { view ->
                view.scaleX = 2f
                view.scaleY = 2f
            },
            additionalAnimation = { animator ->
                animator.scaleX(1f)
                animator.scaleY(1f)
            },
        )
    }

    /**
     * The changelog version doubles as its date. An unparseable value is shown verbatim rather than
     * substituting today, because a wrong date is worse than a raw one.
     */
    private fun getDateString(context: Context): CharSequence {
        val version = this.version
        return try {
            val date = SimpleDateFormat(VERSION_DATE_FORMAT, Locale.US).parse(version) ?: Date()
            DateFormat.getMediumDateFormat(context).format(date)
        } catch (e: ParseException) {
            version
        }
    }

    private fun track(
        event: String,
        properties: Map<String, Any> = emptyMap(),
        includeStats: Boolean = true,
    ) {
        val stats = if (includeStats) {
            mapOf(
                "seconds_open" to (ClockFactory.get().currentTimeMillis() - openedTimestamp) / 1000,
                "max_scrolled_percentage" to maxScrolledPercent,
            )
        } else {
            emptyMap()
        }
        changeLogAnalyticsHandler?.invoke(event, version, revision, properties + stats)
    }

    companion object {
        private const val INTENT_EXTRA_BODY = "INTENT_EXTRA_BODY"
        private const val INTENT_EXTRA_REVISION = "INTENT_EXTRA_REVISION"
        private const val INTENT_EXTRA_VERSION = "INTENT_EXTRA_VERSION"
        private const val INTENT_EXTRA_VIDEO = "INTENT_EXTRA_VIDEO"

        private const val VERSION_DATE_FORMAT = "yyyy-MM-dd"
        private const val OVERLAY_FADE_MILLIS = 200L

        private const val FACEBOOK_PAGE_URL = "https://www.facebook.com/discordapp"
        private const val INSTAGRAM_PAGE_URL = "https://www.instagram.com/discord"

        /**
         * Records that the user has seen this changelog version.
         *
         * The reference calls `StoreStream.getChangeLog().markSeen(version)`; `StoreChangeLog` does
         * not exist in this project, so the store installs this hook when it lands. Until then the
         * changelog is never marked seen and would be re-offered — which is harmless today because
         * nothing enqueues it yet either.
         */
        @JvmStatic
        var changeLogSeenHandler: ((version: String) -> Unit)? = null

        /**
         * Receives `(event, version, revision, properties)`.
         *
         * Stands in for `AnalyticsTracker.changeLogEvent`, which this project has not reconstructed.
         */
        @JvmStatic
        var changeLogAnalyticsHandler:
            ((event: String, version: String, revision: String, properties: Map<String, Any>) -> Unit)? = null

        fun launch(context: Context, version: String, revision: String, video: String, body: String) {
            val extras = Bundle().apply {
                putString(INTENT_EXTRA_VERSION, version)
                putString(INTENT_EXTRA_REVISION, revision)
                putString(INTENT_EXTRA_VIDEO, video)
                putString(INTENT_EXTRA_BODY, body)
            }
            AppScreenRouter.launch(context, WidgetChangeLog::class.java, Intent().putExtras(extras))
        }
    }
}
