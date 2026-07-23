package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.widget.Checkable
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import com.discord.R

class CheckableImageView(context: Context, attrs: AttributeSet?) :
    AppCompatImageView(context, attrs), Checkable {
    private val checkedStateSet = intArrayOf(android.R.attr.state_checked)
    private var checked = false

    init {
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.CheckableImageView, 0, 0).run {
                try {
                    checked = getBoolean(R.styleable.CheckableImageView_android_checked, checked)
                } finally {
                    recycle()
                }
            }
        }
    }

    override fun isChecked() = checked

    override fun setChecked(checked: Boolean) {
        this.checked = checked
        refreshDrawableState()
    }

    override fun toggle() = setChecked(!checked)

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val state = super.onCreateDrawableState(extraSpace + 1)
        if (checked) ImageView.mergeDrawableStates(state, checkedStateSet)
        return state
    }
}
