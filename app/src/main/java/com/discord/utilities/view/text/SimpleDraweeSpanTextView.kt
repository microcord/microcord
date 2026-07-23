package com.discord.utilities.view.text

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView.BufferType
import androidx.appcompat.widget.AppCompatTextView
import com.facebook.drawee.span.DraweeSpanStringBuilder

open class SimpleDraweeSpanTextView : AppCompatTextView {
    private var draweeStringBuilder: DraweeSpanStringBuilder? = null
    private var isAttached = false

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    fun detachCurrentDraweeSpanStringBuilder() {
        draweeStringBuilder?.onDetachFromView(this)
        draweeStringBuilder = null
    }

    fun setDraweeSpanStringBuilder(builder: DraweeSpanStringBuilder?) {
        setText(builder, BufferType.SPANNABLE)
        draweeStringBuilder = builder
        if (builder != null && isAttached) builder.onAttachToView(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        draweeStringBuilder?.onAttachToView(this)
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        draweeStringBuilder?.onDetachFromView(this)
        super.onDetachedFromWindow()
    }

    override fun onFinishTemporaryDetach() {
        super.onFinishTemporaryDetach()
        isAttached = true
        draweeStringBuilder?.onAttachToView(this)
    }

    override fun onStartTemporaryDetach() {
        isAttached = false
        draweeStringBuilder?.onDetachFromView(this)
        super.onStartTemporaryDetach()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
        super.onTextChanged(text, start, before, count)
        detachCurrentDraweeSpanStringBuilder()
    }
}
