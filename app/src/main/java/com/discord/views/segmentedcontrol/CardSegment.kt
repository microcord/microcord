package com.discord.views.segmentedcontrol

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.views.ThemedColor

class CardSegment(context: Context, attrs: AttributeSet?) : CardView(context, attrs), SegmentedControlSegment {
    private val textView: TextView
    private val badgeView: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.card_segment_view, this)
        textView = requireNotNull(findViewById(R.id.card_segment_text))
        badgeView = requireNotNull(findViewById(R.id.card_segment_badge))
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.CardSegment).run {
                try {
                    setText(getString(R.styleable.CardSegment_segment_text))
                } finally {
                    recycle()
                }
            }
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            false
        }
    }

    fun setText(text: CharSequence?) {
        textView.text = text
    }

    fun setBadgeText(text: CharSequence?) {
        badgeView.text = text
        badgeView.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    fun getCardText(): View = textView

    override fun setSegmentSelected(selected: Boolean) {
        setCardBackgroundColor(
            if (selected) ThemedColor.resolve(context, R.attr.colorBackgroundAccent) else ContextCompat.getColor(context, R.color.transparent),
        )
        textView.setTextColor(
            if (selected) ContextCompat.getColor(context, R.color.white)
            else ThemedColor.resolve(context, R.attr.colorInteractiveMuted),
        )
    }

}
