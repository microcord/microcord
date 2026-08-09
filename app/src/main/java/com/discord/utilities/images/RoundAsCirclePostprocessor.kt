package com.discord.utilities.images

import android.graphics.Bitmap
import com.facebook.cache.common.CacheKey
import com.facebook.imagepipeline.postprocessors.RoundAsCirclePostprocessor as FrescoRoundAsCirclePostprocessor
import com.facebook.imagepipeline.request.BasePostprocessor

/**
 * Fresco's native circle filter crashes on degenerate bitmaps, so anything smaller than
 * [MIN_SIZE] in either dimension is passed through untouched.
 *
 * Reference: `com/discord/utilities/images/RoundAsCirclePostprocessor.java`.
 */
private const val MIN_SIZE = 2

class RoundAsCirclePostprocessor(
    private val imageUri: String,
) : BasePostprocessor() {
    private val delegate = FrescoRoundAsCirclePostprocessor()

    override fun process(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        if (width < MIN_SIZE || height < MIN_SIZE) return
        delegate.process(bitmap)
    }

    override fun getName(): String = delegate.name
    override fun getPostprocessorCacheKey(): CacheKey? = delegate.postprocessorCacheKey
}
