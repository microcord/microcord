package com.discord.utilities.images

import android.graphics.Bitmap
import com.facebook.imagepipeline.postprocessors.RoundAsCirclePostprocessor as FrescoRoundAsCirclePostprocessor

/**
 * Fresco's native circle filter crashes on degenerate bitmaps, so anything smaller than
 * [MIN_SIZE] in either dimension is passed through untouched.
 *
 * Reference: `com/discord/utilities/images/RoundAsCirclePostprocessor.java`.
 */
private const val MIN_SIZE = 2

class RoundAsCirclePostprocessor(
    private val imageUri: String,
) : FrescoRoundAsCirclePostprocessor() {

    override fun process(bitmap: Bitmap?) {
        val width = bitmap?.width ?: 0
        val height = bitmap?.height ?: 0
        if (width < MIN_SIZE || height < MIN_SIZE) return
        super.process(bitmap)
    }
}
