package com.discord.widgets.channels.permissions

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R

class AddPermissionOwnerView : ConstraintLayout {
    private val label: TextView

    constructor(context: Context) : super(context) {
        label = inflateContent()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        label = inflateContent()
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        label = inflateContent()
        initialize(attrs)
    }

    private fun inflateContent(): TextView {
        LayoutInflater.from(context).inflate(R.layout.view_add_permission_owner, this)
        return requireNotNull(findViewById(R.id.label))
    }

    private fun initialize(attrs: AttributeSet?) {
        context.obtainStyledAttributes(attrs, R.styleable.AddPermissionOwnerView, 0, 0).run {
            try {
                label.text = getString(R.styleable.AddPermissionOwnerView_apov_label_text)
            } finally {
                recycle()
            }
        }
    }
}
