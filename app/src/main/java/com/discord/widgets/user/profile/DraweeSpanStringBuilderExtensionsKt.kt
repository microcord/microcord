package com.discord.widgets.user.profile

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.content.res.AppCompatResources
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.setVitoSpan
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.span.DraweeSpanStringBuilder

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
    setVitoSpan(
        context = context,
        url = avatarUrl,
        start = start,
        end = start,
        width = size,
        height = size,
        alignment = 2,
        autoPlay = animateAvatar,
        placeholder = AppCompatResources.getDrawable(context, R.drawable.asset_default_avatar_32dp),
        scaleType = ScalingUtils.ScaleType.CENTER_CROP,
        circular = true,
        background = roundingOverlayColor?.let(::ColorDrawable),
        resizeWidth = proxySize,
        resizeHeight = proxySize,
    )
}
