package com.discord.widgets.user

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.discord.models.user.User
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.spans.TypefaceSpanCompat
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator

fun getSpannableForUserNameWithDiscrim(
    user: User,
    nickname: String?,
    context: Context,
    primaryColorRes: Int,
    primaryFont: Int,
    primaryTextSizeSp: Int,
    secondaryColorRes: Int,
    secondaryFont: Int,
    secondaryTextSizeSp: Int,
): SpannableStringBuilder {
    val displayName = user.getDisplayName(nickname)
    val value = if (displayName == user.username) user.getUserNameWithDiscriminator() else displayName
    val result = SpannableStringBuilder(value)
    val primaryEnd = if (displayName == user.username) user.username.length else result.length
    result.setStyle(context, 0, primaryEnd, primaryColorRes, primaryFont, primaryTextSizeSp)
    if (primaryEnd < result.length) {
        result.setStyle(
            context,
            primaryEnd,
            result.length,
            secondaryColorRes,
            secondaryFont,
            secondaryTextSizeSp,
        )
    }
    return result
}

private fun SpannableStringBuilder.setStyle(
    context: Context,
    start: Int,
    end: Int,
    colorAttr: Int,
    fontAttr: Int,
    textSizeResource: Int,
) {
    setSpan(
        ForegroundColorSpan(ColorCompat.getThemedColor(context, colorAttr)),
        start,
        end,
        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
    )
    resolveThemedFont(context, fontAttr)?.let {
        setSpan(TypefaceSpanCompat(it), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }
    setSpan(
        AbsoluteSizeSpan(context.resources.getInteger(textSizeResource), true),
        start,
        end,
        Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
    )
}

private fun resolveThemedFont(context: Context, fontAttr: Int) = TypedValue().let { value ->
    if (!context.theme.resolveAttribute(fontAttr, value, true) || value.resourceId == 0) null
    else ResourcesCompat.getFont(context, value.resourceId)
}
