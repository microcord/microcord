package com.discord.widgets.user.profile

import android.content.Context
import android.net.Uri
import androidx.appcompat.content.res.AppCompatResources
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder
import com.facebook.drawee.generic.RoundingParams
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequestBuilder

@JvmOverloads
fun DraweeSpanStringBuilder.setAvatar(
    context: Context,
    avatarUrl: String? = null,
    animateAvatar: Boolean = false,
    avatarSizePx: Int? = null,
    roundingOverlayColor: Int? = null,
    spaceCharacter: Char? = ' ',
): DraweeSpanStringBuilder = apply {
    if (avatarUrl == null) return@apply
    val start = length
    append('#')
    append(spaceCharacter.toString())
    val size = avatarSizePx ?: (16 * context.resources.displayMetrics.density).toInt()
    val proxySize = IconUtils.getMediaProxySize(size)
    val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(avatarUrl))
        .setResizeOptions(ResizeOptions(proxySize, proxySize))
        .build()
    val controller = Fresco.newDraweeControllerBuilder()
        .setImageRequest(request)
        .setAutoPlayAnimations(animateAvatar)
        .build()
    val hierarchy = GenericDraweeHierarchyBuilder(context.resources)
        .setPlaceholderImage(AppCompatResources.getDrawable(context, R.drawable.asset_default_avatar_32dp))
        .setRoundingParams(RoundingParams.asCircle().apply { roundingOverlayColor?.let(::setOverlayColor) })
        .setActualImageScaleType(ScalingUtils.ScaleType.CENTER_CROP)
        .build()
    setImageSpan(context, hierarchy, controller, start, start, size, size, false, 2)
}
