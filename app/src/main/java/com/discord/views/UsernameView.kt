package com.discord.views

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.TextViewCompat
import com.discord.R
import com.discord.api.user.DisplayNameStyles
import com.discord.api.user.PrimaryGuild
import com.discord.databinding.ViewUsernameBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.displaynamestyles.DisplayNameStyleRenderer
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.setVitoSpan
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.span.DraweeSpanStringBuilder

class UsernameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs) {
    private val binding = ViewUsernameBinding.inflate(LayoutInflater.from(context), this)

    init {
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.UsernameView, 0, 0).run {
                try {
                    binding.usernameText.text = getText(R.styleable.UsernameView_username)
                    binding.usernameText.isSingleLine = getBoolean(R.styleable.UsernameView_username_singleline, true)
                    getResourceId(R.styleable.UsernameView_username_text_appearance, 0).takeIf { it != 0 }
                        ?.let { TextViewCompat.setTextAppearance(binding.usernameText, it) }
                    val sizeResource = getResourceId(
                        R.styleable.UsernameView_username_text_size,
                        R.dimen.uikit_textsize_medium,
                    )
                    binding.usernameText.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeResource))
                    binding.usernameTag.text = getText(R.styleable.UsernameView_android_text)
                    val spacing = getFloat(R.styleable.UsernameView_username_line_spacing_multiplier, 0f)
                    if (spacing > 0f) binding.usernameText.setLineSpacing(0f, spacing)
                    binding.usernameText.setTextColor(
                        getColor(
                            R.styleable.UsernameView_username_text_color,
                            ColorCompat.getThemedColor(context, R.attr.colorInteractiveActive),
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
        binding.usernameText.setDraweeSpanStringBuilder(builder)
    }

    fun configureTag(visible: Boolean, tagText: Int, isVerified: Boolean) {
        if (visible) {
            binding.usernameTag.visibility = View.VISIBLE
            binding.usernameTag.setText(tagText)
            binding.usernameTag.setCompoundDrawablesWithIntrinsicBounds(
                if (isVerified) R.drawable.ic_verified_10dp else 0,
                0,
                0,
                0,
            )
        } else {
            binding.usernameTag.visibility = View.GONE
        }
    }

    fun configureGuildTag(primaryGuild: PrimaryGuild?, textSizeSp: Float = 12f) {
        binding.usernameGuildTag.setSize(textSizeSp)
        binding.usernameGuildTag.bind(primaryGuild)
    }

    fun setGuildTagProfileColors(backgroundColor: Int?, textColor: Int?) =
        binding.usernameGuildTag.setProfileColors(backgroundColor, textColor)

    fun setUsernameColor(color: Int) {
        binding.usernameText.setTextColor(color)
    }

    /**
     * Draws the name in its owner's chosen font, colour and effect.
     *
     * Must be called after [configureUsername] and [setUsernameColor]: it reads the colour it finds
     * as the one to fall back to, and a style with no colours of its own keeps it.
     */
    @JvmOverloads
    fun setDisplayNameStyles(
        styles: DisplayNameStyles?,
        mode: DisplayNameStyleRenderer.Mode = DisplayNameStyleRenderer.Mode.FULL,
    ) = DisplayNameStyleRenderer.apply(binding.usernameText, styles, mode)

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
}
