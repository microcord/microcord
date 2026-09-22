package com.discord.media_picker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.facebook.animated.gif.GifImage
import com.facebook.animated.webp.WebPImage
import com.facebook.common.references.CloseableReference
import com.facebook.imagepipeline.animated.base.AnimatedImageResult
import com.facebook.imagepipeline.animated.impl.AnimatedDrawableBackendImpl
import com.facebook.imagepipeline.animated.impl.AnimatedImageCompositor
import com.facebook.imagepipeline.animated.util.AnimatedDrawableUtil
import com.waynejo.androidndkgif.GifEncoder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Applies the preview transform to GIF or animated WebP frames and writes an animated GIF. */
internal object AnimatedGifCrop {
    fun crop(input: File, output: File, transform: Matrix, width: Int, height: Int) {
        val image = RandomAccessFile(input, "r").use { file ->
            val bytes = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            if (bytes.get(0) == 'G'.code.toByte()) GifImage.createFromByteBuffer(bytes)
            else WebPImage.createFromByteBuffer(bytes, null)
        }
        val result = AnimatedImageResult.forAnimatedImage(image)
        val backend = AnimatedDrawableBackendImpl(AnimatedDrawableUtil(), result, null, false)
        var frame: Bitmap? = null
        var previous: Bitmap? = null
        var previousIndex = -1
        var bitmap: Bitmap? = null
        val encoder = GifEncoder()
        var initialized = false
        var completed = false
        try {
            frame = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            previous = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val compositor = AnimatedImageCompositor(backend, false, object : AnimatedImageCompositor.Callback {
                override fun onIntermediateResult(frameNumber: Int, bitmap: Bitmap) = Unit
                override fun getCachedBitmap(frameNumber: Int): CloseableReference<Bitmap>? =
                    if (frameNumber == previousIndex) CloseableReference.of(checkNotNull(previous)) { } else null
            })
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            encoder.init(width, height, output.path)
            initialized = true
            encoder.setDither(false)
            if (image.frameCount == 0) throw IOException("Image contains no frames")
            val durations = image.frameDurations
            for (index in 0 until image.frameCount) {
                if (Thread.currentThread().isInterrupted) throw IOException("Animation crop cancelled")
                val current = checkNotNull(frame)
                compositor.renderFrame(index, current)
                bitmap.eraseColor(Color.TRANSPARENT)
                canvas.drawBitmap(current, transform, paint)
                if (!encoder.encodeFrame(bitmap, durations[index])) throw IOException("Unable to encode GIF frame")
                // Retain only the preceding composited frame, not an entire animation's bitmaps.
                val reusable = previous
                previous = frame
                frame = reusable
                previousIndex = index
            }
            completed = true
        } finally {
            if (initialized) encoder.close()
            backend.dropCaches()
            result.dispose()
            image.dispose()
            frame?.recycle()
            previous?.recycle()
            bitmap?.recycle()
            if (!completed) output.delete()
        }
    }
}
