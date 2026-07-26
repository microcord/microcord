package com.discord.widgets.tos

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.report.ReportReason
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * One selectable reason row on the report screen: a title, a description and a radio button.
 *
 * The row behaves as a single control. Tapping anywhere and tapping the radio must both report
 * exactly one click, so the radio's checked-change callback is suppressed while the row's own click
 * listener is running — otherwise selecting a row would fire the listener twice and the caller would
 * see a spurious deselect/reselect.
 */
class WidgetTosReportViolationReasonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RelativeLayout(context, attrs, defStyleAttr) {

    private val content: View =
        LayoutInflater.from(context).inflate(R.layout.widget_tos_report_violation_reason, this, false)
            .also(::addView)

    private val header: TextView = content.findViewById(R.id.report_reason_header)
    private val description: TextView = content.findViewById(R.id.report_reason_descriptipn)
    private val radio: MaterialRadioButton = content.findViewById(R.id.report_reason_radio)

    /** True while the row's own click listener runs, so the radio does not re-enter it. */
    private var handlingRowClick = false

    var reason: ReportReason? = null
        set(value) {
            field = value
            header.text = value?.label
            description.text = value?.description
        }

    var isChecked: Boolean
        get() = radio.isChecked
        set(value) {
            radio.isChecked = value
        }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        content.isEnabled = enabled
        radio.isEnabled = enabled
    }

    override fun setOnClickListener(onClickListener: OnClickListener?) {
        content.setOnClickListener {
            handlingRowClick = true
            onClickListener?.onClick(this)
            handlingRowClick = false
        }
        radio.setOnCheckedChangeListener { _, isChecked ->
            if (!handlingRowClick && isChecked) onClickListener?.onClick(this)
        }
    }
}
