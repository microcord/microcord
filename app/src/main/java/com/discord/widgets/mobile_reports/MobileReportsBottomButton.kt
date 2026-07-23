package com.discord.widgets.mobile_reports

import android.content.Context
import android.text.Html
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.report.ReportNodeBottomButton
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.LoadingButton
import com.google.android.material.button.MaterialButton

class MobileReportsBottomButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val button: LoadingButton
    private val cancelButton: MaterialButton
    private val description: LinkifiedTextView
    private val errorText: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mobile_reports_bottom_button, this)
        button = requireNotNull(findViewById(R.id.report_node_bottom_button))
        cancelButton = requireNotNull(findViewById(R.id.report_node_bottom_button_cancel))
        description = requireNotNull(findViewById(R.id.report_node_bottom_button_description))
        errorText = requireNotNull(findViewById(R.id.report_node_bottom_button_error_text))
    }

    fun setup(
        bottomButton: ReportNodeBottomButton?,
        submitState: MobileReportsViewModel.SubmitState?,
        handleButtonPress: ((ReportNodeBottomButton) -> Unit)?,
    ) {
        cancelButton.setOnClickListener(null)
        button.setOnClickListener(null)

        description.visibility = if (bottomButton is ReportNodeBottomButton.Submit) View.VISIBLE else View.GONE
        cancelButton.visibility = if (bottomButton is ReportNodeBottomButton.Cancel) View.VISIBLE else View.GONE
        button.visibility = if (cancelButton.visibility == View.VISIBLE) View.GONE else View.VISIBLE

        val loading = submitState is MobileReportsViewModel.SubmitState.Loading
        errorText.visibility = if (submitState is MobileReportsViewModel.SubmitState.Error) View.VISIBLE else View.GONE
        button.setIsLoading(loading)
        button.isEnabled = !loading

        when (bottomButton) {
            is ReportNodeBottomButton.Submit -> {
                val html = context.getString(R.string.mobile_reports_submit_info_text)
                    .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
                description.text = Html.fromHtml(html)
                button.setText(context.getString(R.string.mobile_reports_submit_report))
                button.setBackgroundColor(ContextCompat.getColor(context, R.color.brand_500))
                button.setOnClickListener { handleButtonPress?.invoke(bottomButton) }
            }
            is ReportNodeBottomButton.Done -> {
                button.setText(context.getString(R.string.done))
                button.setOnClickListener { handleButtonPress?.invoke(bottomButton) }
            }
            is ReportNodeBottomButton.Next -> {
                button.setText(context.getString(R.string.next))
                button.setOnClickListener { handleButtonPress?.invoke(bottomButton) }
            }
            is ReportNodeBottomButton.Cancel ->
                cancelButton.setOnClickListener { handleButtonPress?.invoke(bottomButton) }
            null -> Unit
        }
    }
}
