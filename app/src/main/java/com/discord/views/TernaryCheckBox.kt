package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import com.discord.R
import com.discord.utilities.view.text.LinkifiedTextView

class TernaryCheckBox(context: Context, attrs: AttributeSet?) : RelativeLayout(context, attrs) {
    private val offContainer: FrameLayout
    private val offDisabledOverlay: View
    private val disabledOverlay: View
    private val labelView: TextView
    private val subtextView: LinkifiedTextView
    private val neutralView: CheckableImageView
    private val offView: CheckableImageView
    private val onView: CheckableImageView

    var onSwitchStatusChangedListener: OnSwitchStatusChangedListener? = null
    var switchStatus: Int = -1
        private set

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.view_ternary_checkbox, this, false)
        addView(content)
        offContainer = requireNotNull(content.findViewById(R.id.checkable_off_container))
        offDisabledOverlay = requireNotNull(content.findViewById(R.id.off_disabled_overlay))
        disabledOverlay = requireNotNull(content.findViewById(R.id.setting_disabled_overlay))
        labelView = requireNotNull(content.findViewById(R.id.setting_label))
        subtextView = requireNotNull(content.findViewById(R.id.setting_subtext))
        neutralView = requireNotNull(content.findViewById(R.id.ternary_check_neutral))
        offView = requireNotNull(content.findViewById(R.id.ternary_check_off))
        onView = requireNotNull(content.findViewById(R.id.ternary_check_on))

        var label: CharSequence? = null
        var subtext: CharSequence? = null
        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.TernaryCheckBox, 0, 0).run {
                try {
                    label = getText(R.styleable.TernaryCheckBox_android_text)
                    subtext = getText(R.styleable.TernaryCheckBox_tcs_subtext)
                } finally {
                    recycle()
                }
            }
        }
        labelView.visibility = if (label == null) View.GONE else View.VISIBLE
        labelView.text = label
        subtextView.visibility = if (subtext == null) View.GONE else View.VISIBLE
        subtextView.text = subtext

        onView.setOnClickListener { setSwitchStatus(1) }
        offView.setOnClickListener { setSwitchStatus(-1) }
        neutralView.setOnClickListener { setSwitchStatus(0) }
    }

    fun interface OnSwitchStatusChangedListener {
        fun onSwitchStatusChanged(status: Int)
    }

    fun isOn() = switchStatus == 1
    fun setNeutral() = setSwitchStatus(0)
    fun setOff() = setSwitchStatus(-1)
    fun setOn() = setSwitchStatus(1)

    private fun setSwitchStatus(status: Int) {
        switchStatus = status
        onView.isChecked = status == 1
        offView.isChecked = status == -1
        neutralView.isChecked = status == 0
        onSwitchStatusChangedListener?.onSwitchStatusChanged(status)
    }

    fun clearDisabled() {
        offView.isEnabled = true
        offDisabledOverlay.visibility = View.GONE
        disabledOverlay.visibility = View.GONE
    }

    fun setDisabled(messageRes: Int) {
        offContainer.isEnabled = true
        offDisabledOverlay.visibility = View.GONE
        disabledOverlay.setOnClickListener { showToast(context.getString(messageRes)) }
        disabledOverlay.visibility = View.VISIBLE
    }

    fun setOffDisabled(messageRes: Int) {
        offView.isEnabled = false
        disabledOverlay.visibility = View.GONE
        offDisabledOverlay.setOnClickListener { showToast(context.getString(messageRes)) }
        offDisabledOverlay.visibility = View.VISIBLE
    }

    fun setLabel(label: CharSequence?) {
        labelView.text = label
    }

    fun setSubtext(subtext: CharSequence?) {
        subtextView.text = subtext
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
