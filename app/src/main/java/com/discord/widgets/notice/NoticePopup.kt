package com.discord.widgets.notice

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import com.discord.R
import com.discord.api.sticker.Sticker
import com.discord.app.AppComponent
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.images.MGImages
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.sticker.StickerView
import com.discord.widgets.home.WidgetHome
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.discord.views.VitoImageView
import java.lang.ref.WeakReference
import kotlin.reflect.KClass

/**
 * The in-app notification banner — the thing that slides down over the top of the app when a
 * message arrives while you are looking at something else.
 *
 * This is the presenter half of the notice queue. Nothing here shows anything directly: [enqueue]
 * hands `StoreNotices` a [StoreNotices.Notice] whose `show` lambda builds and attaches the banner,
 * and the store decides when — and the navigation layer decides *where* — that lambda actually
 * runs. See [StoreNotices] for the queueing rules and `NoticeAlertView` for the view itself.
 *
 * Only one banner exists at a time: raising a new one sweeps away whatever is already attached to
 * the activity's decor view.
 */
object NoticePopup {

    private const val DEFAULT_AUTO_DISMISS_PERIOD_SECONDS = 5

    /**
     * Priority `1` and a one-second inter-notice delay, so message notifications sit just below
     * blocking modals but well above passive prompts and can follow each other closely.
     */
    private const val NOTICE_DELAY_PERIOD_MS = 1_000L

    /**
     * The decor view the current banner is attached to.
     *
     * Weak because the activity outlives nothing here: an in-app notification raised over an
     * activity that is then destroyed must not keep it alive, and the banner is gone with it.
     */
    private var alertParent: WeakReference<ViewGroup>? = null

    /**
     * Queues an in-app notification.
     *
     * Parameter order matches the reference exactly, because call sites pass most of these
     * positionally.
     *
     * @param noticeName the queue identity — re-enqueuing the same name while it is pending is a
     *   no-op, and it is the name [StoreNotices.markSeen] is given when the banner goes away.
     * @param noticeBody rendered through `SimpleDraweeSpanTextView`, so a [DraweeSpanStringBuilder]
     *   (a message with inline emoji) keeps its images; anything else is set as plain text.
     * @param noticeStickers only the first is drawn; the parameter is a list because the message
     *   payload carries one.
     * @param noticeAutoDismissPeriodSecs `null` keeps the banner up until it is touched or replaced.
     * @param validScreens the screens the banner may appear over — the navigation layer holds it
     *   back everywhere else rather than dropping it.
     * @param onClickTopRightIcon the settings/close affordance; the banner is dismissed first, so
     *   the handler is free to navigate.
     * @param onClick tapping the body. Also dismisses.
     */
    fun enqueue(
        noticeName: String,
        noticeTitle: CharSequence?,
        noticeSubtitle: CharSequence? = null,
        noticeBody: CharSequence?,
        noticeBodyBackgroundDrawable: Drawable? = null,
        noticeBodyImageUrl: String? = null,
        noticeBodyImageDrawable: Drawable? = null,
        noticeStickers: List<Sticker>? = null,
        noticeIconUrl: String? = null,
        noticeIconResId: Int? = null,
        noticeIconTopRight: Drawable? = null,
        noticeAutoDismissPeriodSecs: Int? = DEFAULT_AUTO_DISMISS_PERIOD_SECONDS,
        validScreens: List<KClass<out AppComponent>> = listOf(WidgetHome::class),
        onClickTopRightIcon: (View) -> Unit = {},
        onClick: (View) -> Unit,
    ) {
        StoreStream.getNotices().requestToShow(
            StoreNotices.Notice(
                name = noticeName,
                priority = StoreNotices.PRIORITY_INAPP_NOTIFICATION,
                validScreens = validScreens,
                delayPeriodMs = NOTICE_DELAY_PERIOD_MS,
                sinceLastPeriodMs = 0L,
                show = { activity ->
                    present(
                        activity = activity,
                        noticeName = noticeName,
                        noticeTitle = noticeTitle,
                        noticeSubtitle = noticeSubtitle,
                        noticeBody = noticeBody,
                        noticeBodyBackgroundDrawable = noticeBodyBackgroundDrawable,
                        noticeBodyImageUrl = noticeBodyImageUrl,
                        noticeBodyImageDrawable = noticeBodyImageDrawable,
                        noticeStickers = noticeStickers,
                        noticeIconUrl = noticeIconUrl,
                        noticeIconResId = noticeIconResId,
                        noticeIconTopRight = noticeIconTopRight,
                        noticeAutoDismissPeriodSecs = noticeAutoDismissPeriodSecs,
                        onClickTopRightIcon = onClickTopRightIcon,
                        onClick = onClick,
                    )
                },
            ),
        )
    }

    /**
     * Takes the banner down and marks the notice seen, so the queue moves on.
     *
     * Every exit that the user caused — tapping the body, tapping the top-right icon, or the
     * auto-dismiss timer — goes through here.
     */
    fun dismiss(noticeName: String) {
        clearAttachedAlerts()
        StoreStream.getNotices().markSeen(noticeName)
    }

    /**
     * Builds, binds and attaches the banner.
     *
     * @return always `true`. The reference does the same: `Notice.show` reports "I presented this",
     *   not "the user saw it", and the store has already decided the notice is due.
     */
    private fun present(
        activity: FragmentActivity,
        noticeName: String,
        noticeTitle: CharSequence?,
        noticeSubtitle: CharSequence?,
        noticeBody: CharSequence?,
        noticeBodyBackgroundDrawable: Drawable?,
        noticeBodyImageUrl: String?,
        noticeBodyImageDrawable: Drawable?,
        noticeStickers: List<Sticker>?,
        noticeIconUrl: String?,
        noticeIconResId: Int?,
        noticeIconTopRight: Drawable?,
        noticeAutoDismissPeriodSecs: Int?,
        onClickTopRightIcon: (View) -> Unit,
        onClick: (View) -> Unit,
    ): Boolean {
        clearAttachedAlerts()

        val decorView = activity.window?.decorView as? ViewGroup ?: return false
        alertParent = WeakReference(decorView)

        val alert = NoticeAlertView(decorView.context, R.layout.widget_notice_popup)
        alert.setAlertBackgroundColor(
            ColorCompat.getThemedColor(activity, R.attr.colorBackgroundTertiary),
        )
        alert.setEnterAnimation(AnimationUtils.loadAnimation(alert.context, R.anim.anim_slide_in_down))
        alert.setAlertClickListener { view ->
            onClick(view)
            dismiss(noticeName)
        }
        // A banner that slid away on its own still has to leave the queue.
        alert.onHideListener = { StoreStream.getNotices().markSeen(noticeName) }

        val container = alert.layoutContainer
        val autoDismissAnimator = getAutoDismissAnimator(noticeAutoDismissPeriodSecs) {
            dismiss(noticeName)
        }
        // Touching the banner at all cancels the countdown — the user is reading it.
        container.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelCountdown(autoDismissAnimator)
            false
        }

        bind(
            container = container,
            noticeName = noticeName,
            noticeTitle = noticeTitle,
            noticeSubtitle = noticeSubtitle,
            noticeBody = noticeBody,
            noticeBodyBackgroundDrawable = noticeBodyBackgroundDrawable,
            noticeBodyImageUrl = noticeBodyImageUrl,
            noticeBodyImageDrawable = noticeBodyImageDrawable,
            noticeStickers = noticeStickers,
            noticeIconUrl = noticeIconUrl,
            noticeIconResId = noticeIconResId,
            noticeIconTopRight = noticeIconTopRight,
            onClickTopRightIcon = onClickTopRightIcon,
        )

        // Posted rather than added inline: the store can emit while the decor view is mid-layout.
        Handler(Looper.getMainLooper()).post { decorView.addView(alert) }
        // Elevation goes on the card, not on the transparent shield around it, or the shadow would
        // be drawn across the whole width of the screen.
        alert.findViewById<View>(R.id.llAlertBackground)?.let {
            ViewCompat.setElevation(it, activity.resources.getDimension(R.dimen.app_elevation))
        }
        return true
    }

    private fun bind(
        container: View,
        noticeName: String,
        noticeTitle: CharSequence?,
        noticeSubtitle: CharSequence?,
        noticeBody: CharSequence?,
        noticeBodyBackgroundDrawable: Drawable?,
        noticeBodyImageUrl: String?,
        noticeBodyImageDrawable: Drawable?,
        noticeStickers: List<Sticker>?,
        noticeIconUrl: String?,
        noticeIconResId: Int?,
        noticeIconTopRight: Drawable?,
        onClickTopRightIcon: (View) -> Unit,
    ) {
        val icon = container.findViewById<VitoImageView>(R.id.popup_icon)
        val attachment = container.findViewById<VitoImageView>(R.id.popup_attachment)
        val body = container.findViewById<SimpleDraweeSpanTextView>(R.id.popup_body)
        val closeButton = container.findViewById<ImageView>(R.id.popup_close_btn)
        val sticker = container.findViewById<StickerView>(R.id.popup_sticker)
        val subtitle = container.findViewById<TextView>(R.id.popup_subtitle)
        val title = container.findViewById<TextView>(R.id.popup_title)

        when {
            noticeIconUrl != null -> MGImages.setImage(icon, noticeIconUrl)
            noticeIconResId != null -> MGImages.setImage(icon, noticeIconResId)
        }

        attachment.visibility =
            if (noticeBodyImageUrl != null || noticeBodyImageDrawable != null) View.VISIBLE else View.GONE
        attachment.background = noticeBodyBackgroundDrawable
        when {
            noticeBodyImageUrl != null -> MGImages.setImage(attachment, noticeBodyImageUrl)
            noticeBodyImageDrawable != null -> MGImages.setImage(attachment, noticeBodyImageDrawable)
        }

        title.text = noticeTitle
        subtitle.setTextAndVisibilityBy(noticeSubtitle)

        if (noticeBody is DraweeSpanStringBuilder) {
            body.setDraweeSpanStringBuilder(noticeBody)
        } else {
            body.text = noticeBody
        }
        body.visibility = if (!noticeBody.isNullOrBlank()) View.VISIBLE else View.GONE
        body.post { body.ellipsizeToMaxLines() }

        sticker.visibility = if (!noticeStickers.isNullOrEmpty()) View.VISIBLE else View.GONE
        noticeStickers?.firstOrNull()?.let { sticker.setSticker(it) }

        closeButton.setImageDrawable(
            noticeIconTopRight ?: ContextCompat.getDrawable(
                container.context,
                container.context.getThemedDrawableRes(R.attr.ic_settings),
            ),
        )
        closeButton.setOnClickListener {
            dismiss(noticeName)
            onClickTopRightIcon(container)
        }
    }

    /**
     * Hand-rolled ellipsis for the body text.
     *
     * `android:ellipsize="end"` cannot be trusted here because the text may carry Drawee spans that
     * load after layout and change the line count; re-measuring on `post` and truncating at the
     * last visible line is the reference's own workaround.
     */
    private fun SimpleDraweeSpanTextView.ellipsizeToMaxLines() {
        val lineCount = layout?.lineCount ?: 0
        if (lineCount <= maxLines) return
        val lastVisibleCharacter = layout.getLineEnd(maxLines - 1)
        val truncated = text.subSequence(0, lastVisibleCharacter - 1)
        text = SpannableStringBuilder(truncated).append("…")
    }

    /**
     * A zero-work animator used purely as a cancellable timer.
     *
     * @return `null` when [autoDismissPeriodSecs] is `null`, i.e. the banner should stay up.
     */
    private fun getAutoDismissAnimator(
        autoDismissPeriodSecs: Int?,
        onEnd: () -> Unit,
    ): ValueAnimator? {
        if (autoDismissPeriodSecs == null) return null
        return ObjectAnimator.ofInt(0, 1).apply {
            interpolator = LinearInterpolator()
            duration = autoDismissPeriodSecs * 1000L
            addListener(object : Animator.AnimatorListener {
                // Detaching on cancel is what stops a cancelled countdown from firing onEnd, since
                // ValueAnimator delivers onAnimationEnd after onAnimationCancel.
                override fun onAnimationCancel(animation: Animator) = animation.removeListener(this)
                override fun onAnimationEnd(animation: Animator) = onEnd()
                override fun onAnimationRepeat(animation: Animator) = Unit
                override fun onAnimationStart(animation: Animator) = Unit
            })
            start()
        }
    }

    private fun cancelCountdown(autoDismissAnimator: ValueAnimator?) {
        autoDismissAnimator?.cancel()
        autoDismissAnimator?.end()
    }

    /** Fades out every banner currently attached, without reporting any of them as hidden. */
    private fun clearAttachedAlerts() {
        val parent = alertParent?.get() ?: return
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index) as? NoticeAlertView ?: continue
            if (child.windowToken != null) child.fadeOutAndRemove()
        }
    }
}
