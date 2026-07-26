package com.discord.widgets.mobile_reports

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.report.NodeResult

class MobileReportsBreadcrumbs @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    init {
        LayoutInflater.from(context).inflate(R.layout.view_mobile_reports_breadcrumbs, this)
    }

    fun setup(history: List<NodeResult>?) {
        if (childCount > 1) removeViewsInLayout(1, childCount - 1)

        history.orEmpty()
            .flatMap { result ->
                listOf(
                    result.elementResult?.data?.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { it.elementValue },
                    result.destination?.name,
                )
            }
            .filterNotNull()
            .filter { it.isNotEmpty() }
            .forEach { title ->
                val item = LayoutInflater.from(context)
                    .inflate(R.layout.view_mobile_reports_breadcrumbs_item, this, false)
                addView(item)
                requireNotNull<TextView>(item.findViewById(R.id.reports_breadcrumbs_item_title)).text = title
            }
    }
}
