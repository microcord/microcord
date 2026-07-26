@file:JvmName("ImageViewExtensions")

package com.discord.utilities.view.extensions

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.annotation.FloatRange

/** Applies a saturation colour filter; `0f` is fully grey, `1f` leaves the image untouched. */
fun ImageView.setSaturation(@FloatRange(from = 0.0) saturation: Float) {
    val colorMatrix = ColorMatrix()
    colorMatrix.setSaturation(saturation)
    colorFilter = ColorMatrixColorFilter(colorMatrix)
}

/** Greys out an image, used for unavailable/disabled imagery such as offline avatars. */
fun ImageView.setGrayscale(grayscale: Boolean) {
    setSaturation(if (grayscale) 0f else 1f)
}
