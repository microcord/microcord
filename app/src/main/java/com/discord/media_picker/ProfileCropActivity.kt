package com.discord.media_picker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import com.yalantis.ucrop.view.UCropView
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import java.io.File
import kotlin.math.roundToInt

/** uCrop host with edge-to-edge chrome and animation-preserving GIF output. */
class ProfileCropActivity : UCropActivity() {
    // uCrop is a dependency layout and does not publish a generated view binding.
    private lateinit var crop: UCropView
    private var cropBitmap: Bitmap? = null
    // Animatable rather than AnimatedImageDrawable (API 28) so stop() is callable on older phones.
    private var animatedPreview: Animatable? = null
    private var previewSubscription: Subscription? = null
    private var saveSubscription: Subscription? = null
    private var saving = false
    private val installPreview = object : Runnable {
        override fun run() {
            val view = crop.cropImageView
            val bitmap = view.viewBitmap
            if (bitmap == null) {
                view.postDelayed(this, PREVIEW_RETRY_DELAY_MS)
                return
            }
            cropBitmap = bitmap
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) loadAnimatedPreview(bitmap.width, bitmap.height)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crop = findViewById(com.yalantis.ucrop.R.id.ucrop)
        val content = findViewById<android.view.View>(android.R.id.content)
        val chrome = intent.getIntExtra(UCrop.Options.EXTRA_STATUS_BAR_COLOR, Color.DKGRAY)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        content.setBackgroundColor(chrome)
        window.statusBarColor = chrome
        window.navigationBarColor = chrome
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(chrome) > 0.5
            isAppearanceLightNavigationBars = isAppearanceLightStatusBars
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) crop.cropImageView.post(installPreview)
    }

    override fun cropAndSaveImage() {
        if (saving) return
        previewSubscription?.unsubscribe()
        crop.cropImageView.removeCallbacks(installPreview)
        val view = crop.cropImageView
        cropBitmap?.let { bitmap ->
            val matrix = Matrix(view.imageMatrix)
            animatedPreview?.stop()
            view.setImageBitmap(bitmap)
            view.imageMatrix = matrix
        }
        // uCrop has already copied content URIs into a local input file while loading the preview.
        val input = view.imageInputPath?.let(::File)
        val header = try {
            input?.inputStream()?.use { stream -> ByteArray(21).also { stream.read(it) } }
        } catch (error: java.io.IOException) {
            setResultError(error)
            finish()
            return
        }
        val signature = header?.toString(Charsets.ISO_8859_1).orEmpty()
        val isGif = signature.startsWith("GIF87a") || signature.startsWith("GIF89a")
        val isAnimatedWebP = signature.startsWith("RIFF") && signature.substring(8, 16) == "WEBPVP8X" &&
            (header!![20].toInt() and 2) != 0
        if (!isGif && !isAnimatedWebP) {
            super.cropAndSaveImage()
            return
        }
        view.cancelAllAnimations()
        view.setImageToWrapCropBounds(false)
        val bitmap = cropBitmap ?: view.viewBitmap ?: return
        val bounds = RectF(crop.overlayView.cropViewRect).apply {
            offset(-view.paddingLeft.toFloat(), -view.paddingTop.toFloat())
        }
        val matrix = Matrix(view.imageMatrix)
        val previewWidth = bitmap.width
        val previewHeight = bitmap.height
        val scale = view.currentScale
        val maxWidth = intent.getIntExtra(UCrop.EXTRA_MAX_SIZE_X, 1024)
        val maxHeight = intent.getIntExtra(UCrop.EXTRA_MAX_SIZE_Y, 1024)
        saving = true
        supportInvalidateOptionsMenu()
        saveSubscription = Observable.fromCallable {
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input!!.path, dimensions)
            check(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "Invalid GIF dimensions" }
            val sourceScale = dimensions.outWidth.toFloat() / previewWidth
            val nativeWidth = bounds.width() / scale * sourceScale
            val nativeHeight = bounds.height() / scale * sourceScale
            val reduction = minOf(1f, maxWidth / nativeWidth, maxHeight / nativeHeight)
            val width = (nativeWidth * reduction).roundToInt().coerceAtLeast(1)
            val height = (nativeHeight * reduction).roundToInt().coerceAtLeast(1)
            matrix.preScale(previewWidth.toFloat() / dimensions.outWidth, previewHeight.toFloat() / dimensions.outHeight)
            matrix.postTranslate(-bounds.left, -bounds.top)
            matrix.postScale(width / bounds.width(), height / bounds.height())
            val directory = File(filesDir, "image_provider").apply {
                check(isDirectory || mkdirs()) { "Unable to create crop output directory" }
            }
            val output = File.createTempFile("profile_crop_", ".gif", directory)
            AnimatedGifCrop.crop(input, output, matrix, width, height)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", output)
            Triple(uri, width, height)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (uri, width, height) ->
                setResultUri(uri, width.toFloat() / height, 0, 0, width, height)
                finish()
            }, { error ->
                setResultError(error)
                finish()
            })
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val result = super.onPrepareOptionsMenu(menu)
        if (saving) {
            menu.findItem(com.yalantis.ucrop.R.id.menu_crop)?.isVisible = false
            menu.findItem(com.yalantis.ucrop.R.id.menu_loader)?.isVisible = true
        }
        return result
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = saving || super.dispatchTouchEvent(event)

    override fun onDestroy() {
        crop.cropImageView.removeCallbacks(installPreview)
        previewSubscription?.unsubscribe()
        saveSubscription?.unsubscribe()
        animatedPreview?.stop()
        animatedPreview = null
        cropBitmap = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun loadAnimatedPreview(width: Int, height: Int) {
        val input: Uri = intent.getParcelableExtra(UCrop.EXTRA_INPUT_URI) ?: return
        previewSubscription = Observable.fromCallable {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, input)) { decoder, _, _ ->
                decoder.setTargetSize(width, height)
            } as? AnimatedImageDrawable
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ drawable ->
                if (drawable == null || isFinishing || isDestroyed || saving) return@subscribe
                animatedPreview = drawable
                crop.cropImageView.setImageDrawable(drawable)
                drawable.start()
            }, { /* A malformed or static image keeps uCrop's existing bitmap preview. */ })
    }

    private companion object {
        const val PREVIEW_RETRY_DELAY_MS = 16L
    }
}
