package com.discord.widgets.servers.creator_monetization_eligibility

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R

class EligibilityChecklistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    data class Requirement(
        val title: CharSequence,
        val description: CharSequence,
        val isMet: Boolean,
    )

    private var lastRequirements: List<Requirement> = emptyList()

    override fun onFinishInflate() {
        super.onFinishInflate()
        orientation = VERTICAL
    }

    fun bind(requirements: List<Requirement>) {
        if (requirements == lastRequirements) return
        removeAllViews()
        requirements.forEachIndexed { index, requirement ->
            if (index > 0) addView(divider())
            val row = LayoutInflater.from(context).inflate(R.layout.view_eligibility_checklist_row, this, false)
            requireNotNull<ImageView>(row.findViewById(R.id.eligibility_indicator)).isActivated = requirement.isMet
            requireNotNull<TextView>(row.findViewById(R.id.title)).text = requirement.title
            requireNotNull<TextView>(row.findViewById(R.id.description)).text = requirement.description
            addView(row)
        }
        // Reference intentionally never updates lastRequirements.
    }

    private fun divider() = View(context).apply {
        val margin = (16 * resources.displayMetrics.density).toInt()
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (resources.displayMetrics.density).toInt()).apply {
            marginStart = margin
            marginEnd = margin
        }
        val value = TypedValue()
        context.theme.resolveAttribute(R.attr.colorBackgroundAccent, value, true)
        setBackgroundColor(value.data)
    }
}
