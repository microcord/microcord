package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R

class IconRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs, 0) {
    private val textView: TextView
    private val imageView: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.icon_row, this)
        textView = requireNotNull(findViewById(R.id.text))
        imageView = requireNotNull(findViewById(R.id.image))
    }

    fun setText(text: String) {
        textView.text = text
        textView.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    fun setImageRes(imageRes: Int?) {
        imageRes?.let(imageView::setImageResource)
        imageView.visibility = if (imageRes == 0) View.GONE else View.VISIBLE
    }
}
