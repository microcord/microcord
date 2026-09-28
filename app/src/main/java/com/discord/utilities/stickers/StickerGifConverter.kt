package com.discord.utilities.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.linecorp.apng.ApngDrawable
import com.waynejo.androidndkgif.GifEncoder
import java.io.File
import java.io.IOException

/**
 * Re-encodes an APNG sticker as an animated GIF.
 *
 * Fake-Nitro needs this because Discord renders a linked `.png` as a still image, and the media
 * proxy offers no animated alternative for APNG stickers (`.webp?animated=true` comes back as a
 * single frame, `.gif` is rejected with 415). A GIF uploaded as an attachment is what animates.
 */
internal object StickerGifConverter {
    fun convert(apng: File, output: File, size: Int) {
        val drawable = ApngDrawable.decode(apng, size, size)
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val encoder = GifEncoder()
        encoder.init(width, height, output.path)
        var completed = false
        try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            // seekToFrame rejects a loop index at or past loopCount, and an endless APNG reports 0.
            drawable.loopCount = 1
            encoder.setDither(false)
            val durations = drawable.frameDurations
            for (index in 0 until drawable.frameCount) {
                drawable.seekToFrame(0, index)
                bitmap.eraseColor(Color.TRANSPARENT)
                drawable.draw(canvas)
                if (!encoder.encodeFrame(bitmap, durations[index])) throw IOException("Unable to encode GIF frame")
            }
            completed = true
        } finally {
            encoder.close()
            drawable.recycle()
            bitmap.recycle()
            if (!completed) output.delete()
        }
    }
}
