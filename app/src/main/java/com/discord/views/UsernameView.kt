package com.discord.views

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.TextViewCompat
import com.discord.R
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder
import com.facebook.drawee.generic.RoundingParams
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.drawee.span.SimpleDraweeSpanTextView
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequestBuilder

class UsernameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs) {
    private val tag: TextView
    private val usernameText: SimpleDraweeSpanTextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_username, this)
        tag = requireNotNull(findViewById(R.id.username_tag))
        usernameText = requireNotNull(findViewById(R.id.username_text))
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.UsernameView, 0, 0).run {
                try {
                    usernameText.text = getText(R.styleable.UsernameView_username)
                    usernameText.isSingleLine = getBoolean(R.styleable.UsernameView_username_singleline, true)
                    getResourceId(R.styleable.UsernameView_username_text_appearance, 0).takeIf { it != 0 }
                        ?.let { TextViewCompat.setTextAppearance(usernameText, it) }
                    val sizeResource = getResourceId(
                        R.styleable.UsernameView_username_text_size,
                        R.dimen.uikit_textsize_medium,
                    )
                    usernameText.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeResource))
                    tag.text = getText(R.styleable.UsernameView_android_text)
                    val spacing = getFloat(R.styleable.UsernameView_username_line_spacing_multiplier, 0f)
                    if (spacing > 0f) usernameText.setLineSpacing(0f, spacing)
                    usernameText.setTextColor(
                        getColor(
                            R.styleable.UsernameView_username_text_color,
                            themedColor(R.attr.colorInteractiveActive),
                        ),
                    )
                } finally {
                    recycle()
                }
            }
        }
    }

    @JvmOverloads
    fun configureUsername(
        username: CharSequence,
        avatarUrl: String? = null,
        animateAvatar: Boolean = false,
        avatarSizePx: Int? = null,
        roundingOverlayColor: Int? = null,
    ) {
        val builder = DraweeSpanStringBuilder()
        builder.setAvatar(context, avatarUrl, animateAvatar, avatarSizePx, roundingOverlayColor)
        builder.append(username)
        usernameText.setDraweeSpanStringBuilder(builder)
    }

    fun configureTag(visible: Boolean, tagText: Int, isVerified: Boolean) {
        if (visible) {
            tag.visibility = View.VISIBLE
            tag.setText(tagText)
            tag.setCompoundDrawablesWithIntrinsicBounds(
                if (isVerified) R.drawable.ic_verified_10dp else 0,
                0,
                0,
                0,
            )
        } else {
            tag.visibility = View.GONE
        }
    }

    fun setUsernameColor(color: Int) {
        usernameText.setTextColor(color)
    }

    private fun DraweeSpanStringBuilder.setAvatar(
        context: Context,
        avatarUrl: String?,
        animateAvatar: Boolean,
        avatarSizePx: Int?,
        roundingOverlayColor: Int?,
        spaceCharacter: Char = ' ',
    ) {
        if (avatarUrl == null) return
        val start = length
        append('#')
        append(spaceCharacter)
        val size = avatarSizePx ?: (16 * resources.displayMetrics.density).toInt()
        val proxySize = MEDIA_PROXY_SIZES.first { it >= size }
        val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(avatarUrl))
            .setResizeOptions(ResizeOptions(proxySize, proxySize))
            .build()
        val controller = Fresco.newDraweeControllerBuilder()
            .setImageRequest(request)
            .setAutoPlayAnimations(animateAvatar)
            .build()
        val rounding = RoundingParams.asCircle().apply {
            roundingOverlayColor?.let(::setOverlayColor)
        }
        val hierarchy = GenericDraweeHierarchyBuilder(resources)
            .setPlaceholderImage(
                AppCompatResources.getDrawable(context, R.drawable.asset_default_avatar_32dp),
            )
            .setRoundingParams(rounding)
            .setActualImageScaleType(ScalingUtils.ScaleType.CENTER_CROP)
            .build()
        setImageSpan(context, hierarchy, controller, start, start, size, size, false, 2)
    }

    private fun themedColor(attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private companion object {
        val MEDIA_PROXY_SIZES = intArrayOf(
            16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128,
            160, 240, 256, 300, 320, 480, 512, 600, 640, 1024, 1280, 1536, 2048,
            3072, 4096,
        )
    }
}
