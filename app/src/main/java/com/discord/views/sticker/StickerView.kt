package com.discord.views.sticker

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.discord.R
import com.discord.api.sticker.BaseSticker
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerPartial
import com.discord.i18n.i18nFormat
import com.discord.rlottie.RLottieDrawable
import com.discord.rlottie.RLottieImageView
import com.discord.stores.StoreStream
import com.discord.utilities.file.DownloadUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.stickers.StickerUtils
import com.facebook.drawee.drawable.ScalingUtils
import com.discord.views.VitoImageView
import com.linecorp.apng.ApngDrawable
import rx.Subscription
import rx.schedulers.Schedulers

class StickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val imageView: VitoImageView
    private val lottieView: RLottieImageView
    private val placeholder: ImageView
    private var sticker: BaseSticker? = null
    private var subscription: Subscription? = null
    private var apngDrawable: ApngDrawable? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.sticker_view, this)
        imageView = requireNotNull(findViewById(R.id.sticker_view_imageview))
        lottieView = requireNotNull(findViewById(R.id.sticker_view_lottie))
        placeholder = requireNotNull(findViewById(R.id.sticker_view_placeholder))
    }

    fun getSubscription(): Subscription? = subscription

    @JvmOverloads
    fun d(sticker: BaseSticker, stickerAnimationSettings: Int? = null) {
        if (this.sticker?.getStickerId() == sticker.getStickerId() && subscription?.isUnsubscribed == false) return
        clearWork()
        this.sticker = sticker
        resetViews()
        when (sticker.formatType) {
            StickerFormatType.PNG -> showStatic(sticker)
            StickerFormatType.APNG -> loadApng(sticker, stickerAnimationSettings)
            StickerFormatType.LOTTIE, StickerFormatType.GIF -> loadAnimated(sticker, stickerAnimationSettings)
            StickerFormatType.UNKNOWN ->
                Log.e(TAG, "Invalid sticker format passed to StickerView: ${sticker.formatType}")
        }
    }

    fun setSticker(sticker: BaseSticker, stickerAnimationSettings: Int? = null) =
        d(sticker, stickerAnimationSettings)

    fun b() {
        imageView.background = null
        lottieView.background = null
    }

    fun clearBackgrounds() = b()

    private fun showStatic(sticker: BaseSticker) {
        imageView.visibility = View.VISIBLE
        placeholder.visibility = View.GONE
        imageView.contentDescription = contentDescription(sticker)
        imageView.hierarchy.setActualImageScaleType(ScalingUtils.ScaleType.FIT_CENTER)
        MGImages.setImage(imageView, StickerUtils.getCDNAssetUrl(sticker), useSmallCache = true)
    }

    private fun loadAnimated(sticker: BaseSticker, localSetting: Int?) {
        val target = if (sticker.formatType == StickerFormatType.LOTTIE) lottieView else imageView
        target.visibility = View.VISIBLE
        if (sticker.formatType != StickerFormatType.LOTTIE) {
            subscription = StoreStream.getUserSettings().observeStickerAnimationSettings()
                .subscribe { setting -> post { showAnimatedRaster(sticker, localSetting ?: setting) } }
            return
        }
        subscription = rx.Observable.combineLatest(
            StickerUtils.fetchSticker(context, sticker),
            // The account setting, reduced-motion aware, straight from the store that owns it.
            StoreStream.getUserSettings().observeStickerAnimationSettings(),
        ) { download, setting -> download to setting }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { (download, globalSetting) ->
                    if (download is DownloadUtils.DownloadState.Completed) {
                        post {
                            if (this.sticker?.getStickerId() == sticker.getStickerId()) {
                                showDownloaded(sticker, download.file, localSetting ?: globalSetting)
                            }
                        }
                    }
                },
                { error ->
                    // A sticker that cannot be downloaded must leave the placeholder
                    // visible rather than crash the surrounding list.
                    Log.w(TAG, "Failed to load sticker ${sticker.getStickerId()}", error)
                },
            )
    }

    private fun showDownloaded(sticker: BaseSticker, file: java.io.File, setting: Int) {
        placeholder.visibility = View.GONE
        if (sticker.formatType == StickerFormatType.LOTTIE) {
            lottieView.contentDescription = contentDescription(sticker)
            val drawable = RLottieDrawable(
                file,
                StickerUtils.DEFAULT_STICKER_SIZE_PX,
                StickerUtils.DEFAULT_STICKER_SIZE_PX,
                precache = false,
                limitFps = true,
                displayRefreshRate = refreshRate(),
            ).apply {
                setPlaybackMode(
                    if (setting == StickerUtils.ALWAYS_ANIMATE) RLottieDrawable.PlaybackMode.LOOP
                    else RLottieDrawable.PlaybackMode.FREEZE,
                )
                setAllowDecodeSingleFrame(true)
            }
            lottieView.setRLottieDrawable(drawable)
            when (setting) {
                StickerUtils.ALWAYS_ANIMATE -> lottieView.playAnimation()
                StickerUtils.ANIMATE_ON_INTERACTION -> lottieView.setOnLongClickListener {
                    lottieView.setPlaybackMode(RLottieDrawable.PlaybackMode.LOOP)
                    lottieView.playAnimation()
                    true
                }
                StickerUtils.NEVER_ANIMATE -> lottieView.setOnLongClickListener(null)
            }
        } else {
            imageView.contentDescription = contentDescription(sticker)
            imageView.hierarchy.setActualImageScaleType(ScalingUtils.ScaleType.FIT_CENTER)
            showAnimatedRaster(sticker, setting)
        }
    }

    private fun loadApng(sticker: BaseSticker, localSetting: Int?) {
        imageView.visibility = View.VISIBLE
        subscription = rx.Observable.combineLatest(
            StickerUtils.fetchSticker(context, sticker),
            StoreStream.getUserSettings().observeStickerAnimationSettings(),
        ) { download, setting -> download to (localSetting ?: setting) }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { (download, setting) ->
                    if (download is DownloadUtils.DownloadState.Completed) {
                        val drawable = ApngDrawable.decode(download.file)
                        post {
                            if (this.sticker?.getStickerId() != sticker.getStickerId()) {
                                drawable.recycle()
                                return@post
                            }
                            placeholder.visibility = View.GONE
                            imageView.contentDescription = contentDescription(sticker)
                            imageView.hierarchy.setActualImageScaleType(ScalingUtils.ScaleType.FIT_CENTER)
                            apngDrawable?.recycle()
                            apngDrawable = drawable
                            imageView.setImageDrawable(drawable)
                            if (setting == StickerUtils.ALWAYS_ANIMATE) drawable.start()
                            imageView.setOnLongClickListener(
                                if (setting == StickerUtils.ANIMATE_ON_INTERACTION) {
                                    View.OnLongClickListener { drawable.start(); true }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                },
                { error -> Log.w(TAG, "Failed to load APNG sticker ${sticker.getStickerId()}", error) },
            )
    }

    private fun showAnimatedRaster(sticker: BaseSticker, setting: Int) {
        placeholder.visibility = View.GONE
        imageView.contentDescription = contentDescription(sticker)
        imageView.hierarchy.setActualImageScaleType(ScalingUtils.ScaleType.FIT_CENTER)
        val animatedUrl = StickerUtils.getCDNAssetUrl(sticker)
        when (setting) {
            StickerUtils.ALWAYS_ANIMATE ->
                MGImages.setImage(imageView, animatedUrl, useSmallCache = false, autoPlayAnimations = true)
            StickerUtils.ANIMATE_ON_INTERACTION -> {
                MGImages.setImage(imageView, stillImageUrl(sticker), useSmallCache = true)
                imageView.setOnLongClickListener {
                    MGImages.setImage(imageView, animatedUrl, useSmallCache = false, autoPlayAnimations = true)
                    true
                }
            }
            StickerUtils.NEVER_ANIMATE -> {
                MGImages.setImage(imageView, stillImageUrl(sticker), useSmallCache = true)
                imageView.setOnLongClickListener(null)
            }
        }
    }

    private fun stillImageUrl(sticker: BaseSticker) =
        StickerUtils.getCDNAssetUrl(sticker, passthrough = false)

    private fun contentDescription(sticker: BaseSticker): String? {
        val value = when (sticker) {
            is Sticker -> "${sticker.name}, ${sticker.description}"
            is StickerPartial -> sticker.name
            else -> return null
        }
        return context.i18nFormat(R.string.sticker_a11y_label, "stickerName" to value)
    }

    private fun resetViews() {
        MGImages.cancelImageRequests(imageView)
        apngDrawable?.stop()
        apngDrawable?.recycle()
        apngDrawable = null
        imageView.setImageDrawable(null)
        imageView.setOnLongClickListener(null)
        lottieView.rlottieDrawable?.stop()
        lottieView.setImageDrawable(null)
        lottieView.clearAnimation()
        lottieView.setOnLongClickListener(null)
        imageView.visibility = View.GONE
        lottieView.visibility = View.GONE
        placeholder.visibility = View.VISIBLE
    }

    private fun clearWork() {
        subscription?.unsubscribe()
        subscription = null
    }

    override fun setOnClickListener(listener: OnClickListener?) {
        when (sticker?.formatType) {
            StickerFormatType.PNG, StickerFormatType.APNG, StickerFormatType.GIF -> imageView.setOnClickListener(listener)
            StickerFormatType.LOTTIE -> lottieView.setOnClickListener(listener)
            else -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearWork()
    }

    @Suppress("DEPRECATION")
    private fun refreshRate(): Float = if (Build.VERSION.SDK_INT >= 30) {
        context.display?.refreshRate ?: 60f
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate
    }

    private companion object {
        const val TAG = "StickerView"
    }
}
