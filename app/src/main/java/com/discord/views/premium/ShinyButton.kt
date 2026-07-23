package com.discord.views.premium

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.ImageViewCompat
import com.discord.R

class ShinyButton(context: Context, attrs: AttributeSet?) : RelativeLayout(context, attrs, 0) {
    private val buttonContainer: LinearLayout
    private val icon: ImageView
    private val text: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.shiny_button, this)
        buttonContainer = requireNotNull(findViewById(R.id.button_container))
        icon = requireNotNull(findViewById(R.id.icon))
        text = requireNotNull(findViewById(R.id.text))
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.ShinyButton, 0, 0).run {
                try {
                    setBackgroundTint(
                        getColor(
                            R.styleable.ShinyButton_sb_background_tint,
                            ContextCompat.getColor(context, R.color.uikit_btn_bg_color_selector_green),
                        ),
                    )
                    val color = getColor(
                        R.styleable.ShinyButton_sb_color,
                        ContextCompat.getColor(context, R.color.white),
                    )
                    setTextColor(color)
                    setDrawableColor(color)
                    getDrawable(R.styleable.ShinyButton_sb_drawable_start)?.let(::setDrawable)
                    getString(R.styleable.ShinyButton_sb_text)?.let(::setText)
                } finally {
                    recycle()
                }
            }
        }
    }

    fun setTextColor(color: Int) = text.setTextColor(color)
    fun setText(text: String) {
        this.text.text = text
    }
    fun setDrawable(iconStart: Drawable) = icon.setImageDrawable(iconStart)
    fun setDrawableColor(color: Int) =
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
    fun setBackgroundTint(color: Int) = DrawableCompat.setTint(buttonContainer.background, color)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            buttonContainer.layoutParams = buttonContainer.layoutParams.apply {
                width = this@ShinyButton.layoutParams.width
                height = this@ShinyButton.layoutParams.height
            }
        }
    }
}
