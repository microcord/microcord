package com.discord.utilities.images

import android.content.Context
import android.graphics.drawable.Drawable
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.RoundingOptions
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequest

fun DraweeSpanStringBuilder.setVitoSpan(
    context: Context,
    url: String,
    start: Int,
    end: Int,
    width: Int,
    height: Int,
    alignment: Int,
    autoPlay: Boolean = false,
    placeholder: Drawable? = null,
    overlay: Drawable? = null,
    scaleType: ScalingUtils.ScaleType = ScalingUtils.ScaleType.CENTER_CROP,
    circular: Boolean = false,
    background: Drawable? = null,
    resizeWidth: Int = width,
    resizeHeight: Int = height,
) {
    val options = ImageOptions.create()
        .cacheChoice(ImageRequest.CacheChoice.SMALL)
        .resize(ResizeOptions(resizeWidth, resizeHeight))
        .scale(scaleType)
        .autoPlay(autoPlay)
        .fadeDurationMs(0)
        .releaseOnWindowDetached(false)
        .placeholder(placeholder)
        .placeholderApplyRoundingOptions(circular)
        .overlay(overlay)
        .background(background)
        .round(RoundingOptions.asCircle().takeIf { circular })
        .build()
    setImageVitoSpan(
        context,
        ImageSourceProvider.forUri(url),
        options,
        null,
        start,
        end,
        width,
        height,
        false,
        alignment,
    )
}
