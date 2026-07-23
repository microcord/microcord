package com.discord.views.premium

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.models.domain.ModelSubscription
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AccountCreditView(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs) {
    private val header: TextView
    private val icon: ImageView
    private val info: TextView
    private val time: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_account_credit, this, true)
        header = requireNotNull(findViewById(R.id.credit_header))
        icon = requireNotNull(findViewById(R.id.credit_icon))
        info = requireNotNull(findViewById(R.id.credit_info))
        time = requireNotNull(findViewById(R.id.credit_time))
    }

    fun bind(planId: Long, monthsCredit: Int, currentSubscription: ModelSubscription?) {
        visibility = if (monthsCredit > 0) View.VISIBLE else View.GONE
        if (monthsCredit <= 0) return

        val planName = when (planId) {
            NITRO_CLASSIC_MONTH_PLAN_ID -> context.getString(R.string.premium_tier_1).also {
                icon.setImageResource(R.drawable.drawable_ic_nitro_classic)
            }
            NITRO_MONTH_PLAN_ID -> context.getString(R.string.premium_tier_2).also {
                icon.setImageResource(R.drawable.drawable_ic_nitro)
            }
            else -> ""
        }
        if (planName.isNotEmpty()) {
            header.text = context.getString(R.string.premium_subscription_credit)
                .replace("!!{planName}!!", planName)
        }

        info.text = if (
            currentSubscription != null &&
            !currentSubscription.isGoogleSubscription() &&
            currentSubscription.hasPlan(planId)
        ) {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(currentSubscription.currentPeriodEnd)
            val renderedDate = android.text.format.DateFormat.getMediumDateFormat(context).format(date ?: java.util.Date())
            context.getString(R.string.premium_subscription_credit_applied_on).replace("{date}", renderedDate)
        } else {
            context.getString(R.string.premium_subscription_credit_applied_mismatched_plan_android)
                .replace("!!{planName}!!", planName)
        }
        time.text = resources.getQuantityString(
            R.plurals.premium_subscription_credit_count_months_count,
            monthsCredit,
        ).replace("{#}", monthsCredit.toString())
    }

    private companion object {
        const val NITRO_CLASSIC_MONTH_PLAN_ID = 511651871736201216L
        const val NITRO_MONTH_PLAN_ID = 511651880837840896L
    }
}
