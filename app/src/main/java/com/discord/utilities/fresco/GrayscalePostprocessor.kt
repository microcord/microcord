package com.discord.utilities.fresco

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.facebook.cache.common.CacheKey
import com.facebook.cache.common.SimpleCacheKey
import com.facebook.imagepipeline.request.BasePostprocessor

/**
 * Desaturates a decoded image, used for disabled/unavailable artwork.
 *
 * Reference: `com/discord/utilities/fresco/GrayscalePostprocessor.java`.
 */
class GrayscalePostprocessor : BasePostprocessor() {

    override fun getName(): String = javaClass.simpleName

    override fun getPostprocessorCacheKey(): CacheKey = SimpleCacheKey(getName())

    override fun process(destBitmap: Bitmap?, sourceBitmap: Bitmap?) {
        super.process(destBitmap, sourceBitmap)
        if (destBitmap == null || sourceBitmap == null) return
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
        Canvas(destBitmap).drawBitmap(sourceBitmap, 0f, 0f, paint)
    }
}
