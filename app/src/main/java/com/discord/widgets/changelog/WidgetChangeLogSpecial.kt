package com.discord.widgets.changelog

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.text.format.DateFormat
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.i18n.setI18nText
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import com.discord.utilities.view.extensions.getContentView
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.VitoImageView
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.imagepipeline.image.ImageInfo
import com.google.android.material.button.MaterialButton
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * The branded, full-bleed variant of the changelog, used for a headline feature launch.
 *
 * It shows the same markdown body as [WidgetChangeLog] but with the "special" header styling, a
 * hero image, a call-to-action card and no action bar — the screen owns its own close/back button
 * because it is presented as a takeover.
 */
class WidgetChangeLogSpecial : AppFragment(R.layout.widget_change_log_special) {

    /** Which chrome the takeover offers for leaving it. */
    enum class ExitStyle {
        BACK,
        CLOSE,
    }

    private var maxScrolledPercent: Int = 0
    private var openedTimestamp: Long = 0
    private var thumbnailAnimatable: Animatable? = null

    private val body: LinkifiedTextView
        get() = requireView().findViewById(R.id.change_log_body)
    private val ctaBody: TextView
        get() = requireView().findViewById(R.id.change_log_special_cta_body)
    private val scrollView: NestedScrollView
        get() = requireView().findViewById(R.id.change_log_special_scrollview)
    private val video: VideoView
        get() = requireView().findViewById(R.id.change_log_video)
    private val videoOverlay: VitoImageView
        get() = requireView().findViewById(R.id.change_log_video_overlay)
    private val thumbnail: VitoImageView
        get() = requireView().findViewById(R.id.change_log_thumbnail)

    private val version: String
        get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_VERSION) ?: getString(R.string.change_log_md_date)

    private val revision: String
        get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_REVISION)
            ?: getString(R.string.change_log_md_revision)

    private val thumbnailControllerListener = object : BaseControllerListener<ImageInfo>() {
        override fun onFinalImageSet(id: String?, imageInfo: ImageInfo?, animatable: Animatable?) {
            super.onFinalImageSet(id, imageInfo, animatable)
            try {
                val overlay = videoOverlay
                thumbnailAnimatable = animatable
                overlay.visibility = if (animatable != null) View.VISIBLE else View.GONE
                overlay.setOnClickListener {
                    hideVideoOverlay()
                    thumbnailAnimatable?.start()
                }
            } catch (e: Exception) {
                AppLog.e("Failed to set changelog thumbnail image.", e)
            }
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        openedTimestamp = ClockFactory.get().currentTimeMillis()
        track("change_log_opened", includeStats = false)
        setActionBarSubtitle(getDateString(requireContext()))

        val closeButton = view.findViewById<ImageButton>(R.id.change_log_special_close)
        val backButton = view.findViewById<ImageButton>(R.id.change_log_special_back)
        if (mostRecentIntent.getSerializableExtra(INTENT_EXTRA_EXIT_STYLE) == ExitStyle.BACK) {
            closeButton.visibility = View.GONE
            backButton.visibility = View.VISIBLE
        }
        closeButton.setOnClickListener { requireActivity().finish() }
        backButton.setOnClickListener { requireActivity().finish() }

        view.findViewById<TextView>(R.id.change_log_special_date).text = getDateString(requireContext())

        val bodyMarkdown = mostRecentIntent.getStringExtra(INTENT_EXTRA_BODY)
            ?: getString(R.string.change_log_md_body)
        body.setDraweeSpanStringBuilder(
            ChangeLogParser.parse(requireContext(), bodyMarkdown, changeLogRules = true) { context, url, _ ->
                track("change_log_cta_clicked", mapOf("cta_type" to "inline_link"), includeStats = false)
                UriHandler.handle(context, url)
            },
        )

        // DIVERGENCE FROM THE REFERENCE, deliberate.
        // The reference applies `changelog_stickers_cta_body` to `change_log_body` — the view it has
        // just filled with the parsed changelog — which wipes the entire release-notes body and
        // leaves only the CTA sentence. `change_log_special_cta_body` is the view the layout already
        // points at that string, has `clickable`/`focusable` set for its embedded link, and is the
        // only view the line makes sense on. Applying it there instead.
        ctaBody.setI18nText(R.string.changelog_stickers_cta_body)
        ctaBody.movementMethod = LinkMovementMethod.getInstance()

        configureMedia(
            mostRecentIntent.getStringExtra(INTENT_EXTRA_VIDEO) ?: getString(R.string.change_log_md_video),
        )

        view.findViewById<MaterialButton>(R.id.change_log_special_button).setOnClickListener {
            UriHandler.handle(requireContext(), STICKERS_HELP_ARTICLE_URL)
        }

        if (mostRecentIntent.getBooleanExtra(INTENT_EXTRA_HIDE_VIDEO, false)) {
            video.visibility = View.GONE
            // Without the video card below it, the header's negative bottom overlap would pull the
            // body up into the hero art.
            val header = view.findViewById<CardView>(R.id.changelog_special_header_container)
            (header.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.setMargins(
                    params.leftMargin,
                    params.topMargin,
                    params.rightMargin,
                    dpToPixels(HEADER_BOTTOM_MARGIN_DP),
                )
            }
        }

        scrollView.setOnScrollChangeListener { _: NestedScrollView?, _: Int, scrollY: Int, _: Int, _: Int ->
            val scrollable = max(scrollView.getContentView().height - scrollView.height, 1)
            maxScrolledPercent = max(maxScrolledPercent, scrollY * 100 / scrollable)
        }
    }

    override fun onPause() {
        if (video.isPlaying) video.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        view?.findViewById<VideoView>(R.id.change_log_video)?.let {
            it.setOnPreparedListener(null)
            it.setOnCompletionListener(null)
        }
        super.onDestroyView()
    }

    override fun onDestroy() {
        WidgetChangeLog.changeLogSeenHandler?.invoke(version)
        track("change_log_closed")
        super.onDestroy()
    }

    private fun configureMedia(videoUrl: String) {
        val isVideo = videoUrl.endsWith(".mp4")
        video.visibility = if (isVideo) View.VISIBLE else View.GONE
        videoOverlay.visibility = if (isVideo) View.VISIBLE else View.GONE

        if (!isVideo) {
            thumbnail.visibility = View.VISIBLE
            thumbnail.show(listOf(videoUrl), autoPlay = false, controllerListener = thumbnailControllerListener)
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
        video.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.start()
            mediaPlayer.pause()
            video.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            video.requestLayout()
        }
    }

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

    /** Padded with spaces because the takeover renders the date inline against the hero art. */
    private fun getDateString(context: Context): CharSequence {
        val version = this.version
        return try {
            val date = SimpleDateFormat(VERSION_DATE_FORMAT, Locale.US).parse(version) ?: Date()
            " ${DateFormat.getMediumDateFormat(context).format(date)} "
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
        WidgetChangeLog.changeLogAnalyticsHandler?.invoke(event, version, revision, properties + stats)
    }

    companion object {
        private const val INTENT_EXTRA_BODY = "INTENT_EXTRA_BODY"
        private const val INTENT_EXTRA_EXIT_STYLE = "INTENT_EXTRA_EXIT_STYLE"
        private const val INTENT_EXTRA_HIDE_VIDEO = "INTENT_EXTRA_HIDE_VIDEO"
        private const val INTENT_EXTRA_REVISION = "INTENT_EXTRA_REVISION"
        private const val INTENT_EXTRA_VERSION = "INTENT_EXTRA_VERSION"
        private const val INTENT_EXTRA_VIDEO = "INTENT_EXTRA_VIDEO"

        private const val VERSION_DATE_FORMAT = "yyyy-MM-dd"
        private const val OVERLAY_FADE_MILLIS = 200L
        private const val HEADER_BOTTOM_MARGIN_DP = 16

        /**
         * Article 360056891113, "Stickers".
         *
         * The reference builds this through `AppHelpDesk`, which localises the `/hc/<locale>/`
         * segment from the account's system locale; that helper does not exist in this project, so
         * the URL is pinned to `en-us` the same way the other help-desk links here are.
         */
        private const val STICKERS_HELP_ARTICLE_URL =
            "https://support.discord.com/hc/en-us/articles/360056891113"

        @JvmStatic
        @JvmOverloads
        fun launch(
            context: Context,
            version: String,
            revision: String,
            video: String,
            body: String,
            exitStyle: ExitStyle = ExitStyle.CLOSE,
            hideVideo: Boolean = false,
        ) {
            val extras = Bundle().apply {
                putSerializable(INTENT_EXTRA_EXIT_STYLE, exitStyle)
                putString(INTENT_EXTRA_VERSION, version)
                putString(INTENT_EXTRA_REVISION, revision)
                putString(INTENT_EXTRA_VIDEO, video)
                putString(INTENT_EXTRA_BODY, body)
                putBoolean(INTENT_EXTRA_HIDE_VIDEO, hideVideo)
            }
            AppScreenRouter.launch(context, WidgetChangeLogSpecial::class.java, Intent().putExtras(extras))
        }
    }
}
