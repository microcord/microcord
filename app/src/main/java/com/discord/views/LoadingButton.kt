package com.discord.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.discord.R
import com.google.android.material.button.MaterialButton

class LoadingButton(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    private lateinit var button: MaterialButton
    private lateinit var progress: ProgressBar
    private var disabledAlpha = 0.2f
    private var cachedText: CharSequence? = null
    private var configuredIcon: Drawable? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_loading_button, this)
        button = requireNotNull(findViewById(R.id.loading_button_button))
        progress = requireNotNull(findViewById(R.id.loading_button_progress))
        isClickable = true
        isFocusable = true
        // Layouts style this wrapper as a button, which gives it the button's own background and
        // padding: a second button around the inner one, visible once the inner one is recoloured.
        if (attrs != null && attrs.styleAttribute != 0 &&
            attrs.getAttributeValue(ANDROID_NAMESPACE, "background") == null
        ) {
            background = null
            setPadding(0, 0, 0, 0)
        }

        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.LoadingButton, 0, 0).run {
                try {
                    getColorStateList(R.styleable.LoadingButton_lb_background_color)?.let {
                        button.backgroundTintList = it
                    }
                    if (getBoolean(R.styleable.LoadingButton_lb_disable_elevation, false)) {
                        button.stateListAnimator = null
                    }
                    disabledAlpha = getFloat(R.styleable.LoadingButton_lb_disabled_alpha, 0.2f)
                    configuredIcon = getDrawable(R.styleable.LoadingButton_lb_icon)
                    button.icon = configuredIcon
                    button.iconGravity = when (getString(R.styleable.LoadingButton_lb_icon_gravity)) {
                        "start" -> 1
                        "textStart" -> 2
                        "end" -> 3
                        "textEnd" -> 4
                        else -> 0
                    }
                    if (hasValue(R.styleable.LoadingButton_lb_padding_top)) {
                        button.setPadding(
                            button.paddingLeft,
                            getDimensionPixelSize(R.styleable.LoadingButton_lb_padding_top, button.paddingTop),
                            button.paddingRight,
                            button.paddingBottom,
                        )
                    }
                    if (hasValue(R.styleable.LoadingButton_lb_padding_bottom)) {
                        button.setPadding(
                            button.paddingLeft,
                            button.paddingTop,
                            button.paddingRight,
                            getDimensionPixelSize(R.styleable.LoadingButton_lb_padding_bottom, button.paddingBottom),
                        )
                    }
                    val progressColor = getColor(R.styleable.LoadingButton_lb_progress_color, Int.MAX_VALUE)
                    if (progressColor != Int.MAX_VALUE) {
                        progress.indeterminateDrawable?.setColorFilter(progressColor, PorterDuff.Mode.SRC_IN)
                    }
                    setText(getText(R.styleable.LoadingButton_lb_text))
                    getColorStateList(R.styleable.LoadingButton_lb_text_color)?.let(button::setTextColor)
                    val textSize = getDimension(R.styleable.LoadingButton_lb_text_size, -1f)
                    if (textSize > 0f) button.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                } finally {
                    recycle()
                }
            }
        }
        alpha = if (isEnabled) 1f else disabledAlpha
    }

    fun setText(text: CharSequence?) {
        cachedText = text
        button.text = if (progress.visibility == View.VISIBLE) null else text
    }

    fun setIsLoading(isLoading: Boolean) {
        if (isLoading) {
            isClickable = false
            cachedText = button.text.toString()
            button.text = null
            button.icon = null
            progress.visibility = View.VISIBLE
        } else {
            isClickable = true
            button.text = cachedText
            button.icon = configuredIcon
            progress.visibility = View.GONE
        }
    }

    fun setIconVisibility(visible: Boolean) {
        button.icon = if (visible) configuredIcon else null
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (::button.isInitialized) button.isEnabled = enabled
        alpha = if (enabled) 1f else disabledAlpha
    }

    override fun setBackgroundColor(color: Int) {
        if (::button.isInitialized) button.backgroundTintList = ColorStateList.valueOf(color)
    }
}

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
