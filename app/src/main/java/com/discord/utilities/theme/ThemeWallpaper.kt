package com.discord.utilities.theme

import android.app.Activity
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.utilities.theme.ThemeDefinition.Transparency
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A theme's background image, shown where its [Transparency] mode says.
 *
 * Like Aliucord's Themer, the black scrim (`overlay_alpha`) and the blur are baked into the image
 * once, when the theme's table is built ([prepare]); startup then only decodes the result.
 *
 * Where it shows:
 * - [Transparency.CHAT]: behind the chat.
 * - [Transparency.CHAT_SETTINGS]: also behind settings pages.
 * - [Transparency.FULL]: behind the whole app.
 *
 * Surfaces only let it through where the theme's own colors are see-through.
 */
object ThemeWallpaper {
    private const val MAX_SIDE = 1920
    private const val QUALITY = 90

    @Volatile
    private var file: File? = null

    @Volatile
    var mode: Transparency = Transparency.NONE
        private set
    private var bitmap: Bitmap? = null

    internal fun set(file: File?, mode: Transparency) {
        synchronized(this) {
            if (this.file != file) bitmap = null
            this.file = file
            this.mode = if (file == null) Transparency.NONE else mode
        }
    }

    /**
     * Decodes [source], bakes in the scrim and blur, and writes the result to [target] as a JPEG.
     * Animated images keep their first frame. Returns `false` when [source] is not an image.
     */
    internal fun prepare(source: File, background: ThemeDefinition.Background, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val image = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.takeIf { it !== image }?.recycle()
        if (background.blurRadius > 0f) blur(image, background.blurRadius)
        if (background.overlayAlpha > 0) Canvas(image).drawColor(Color.argb(background.overlayAlpha, 0, 0, 0))
        target.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        image.recycle()
        return true
    }

    /** Puts the image behind the whole app, in [Transparency.FULL]. Called once the activity has its content. */
    fun decorate(activity: Activity, root: View) {
        if (mode != Transparency.FULL) return
        val drawable = drawable(activity.resources) ?: return
        root.background = drawable
        activity.window.setBackgroundDrawable(null)
    }

    /** Puts the image behind the chat, or behind a settings page, as the mode asks. */
    fun decorate(fragment: Fragment, view: View) {
        when (mode) {
            Transparency.CHAT, Transparency.CHAT_SETTINGS -> Unit
            else -> return
        }
        val name = fragment.javaClass.simpleName
        if (name == CHAT_FRAGMENT) {
            val panel = generateSequence(view.parent as? View) { it.parent as? View }.firstOrNull { it.id == R.id.panel_center }
                ?: return
            panel.background = drawable(view.resources) ?: return
            panel.findViewById<View>(R.id.widget_home_panel_center_chat)?.background = null
        } else if (mode == Transparency.CHAT_SETTINGS && name.startsWith(SETTINGS_PREFIX)) {
            view.background = drawable(view.resources) ?: return
        }
    }

    private fun drawable(resources: Resources): Drawable? {
        val image = synchronized(this) {
            bitmap ?: file?.let { BitmapFactory.decodeFile(it.path) }?.also { bitmap = it }
        } ?: return null
        return CenterCrop(image)
    }

    /** Three box blurs, which approximate a Gaussian of [radius] at a 1080-pixel width. */
    private fun blur(image: Bitmap, radius: Float) {
        val width = image.width
        val height = image.height
        val scaled = (radius * width / 1080f).roundToInt().coerceAtLeast(1)
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = IntArray(pixels.size)
        repeat(3) {
            boxBlur(pixels, scratch, width, height, scaled, horizontal = true)
            boxBlur(scratch, pixels, width, height, scaled, horizontal = false)
        }
        image.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun boxBlur(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            fun at(i: Int) = if (horizontal) line * width + i.coerceIn(0, length - 1) else i.coerceIn(0, length - 1) * width + line
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val pixel = source[at(i)]
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
            }
            for (i in 0 until length) {
                target[at(i)] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = source[at(i - radius)]
                val entering = source[at(i + radius + 1)]
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** Fills its bounds with the image, cropping the overflow, as a wallpaper does. */
    private class CenterCrop(private val image: Bitmap) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()

        override fun onBoundsChange(bounds: Rect) {
            val scale = max(bounds.width() / image.width.toFloat(), bounds.height() / image.height.toFloat())
            matrix.setScale(scale, scale)
            matrix.postTranslate(
                bounds.left + (bounds.width() - image.width * scale) / 2,
                bounds.top + (bounds.height() - image.height * scale) / 2,
            )
        }

        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.clipRect(bounds)
            canvas.drawBitmap(image, matrix, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    private const val CHAT_FRAGMENT = "WidgetChatList"
    private const val SETTINGS_PREFIX = "WidgetSettings"
}
