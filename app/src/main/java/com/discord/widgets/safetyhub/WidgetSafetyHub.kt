package com.discord.widgets.safetyhub

import android.graphics.Color
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.api.safetyhub.AccountStandingState
import com.discord.app.AppFragment
import com.discord.i18n.I18nSpans
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.time.TimeUtils
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import rx.Subscription

/** The account's standing and the violations behind it. */
class WidgetSafetyHub : AppFragment(R.layout.widget_safety_hub) {

    private lateinit var viewModel: SafetyHubViewModel
    private var subscription: Subscription? = null
    private var activeShown = PAGE_SIZE
    private var expiredShown = PAGE_SIZE

    /** Embedded in the suspended page there is no settings stack to sit in, so no chrome either. */
    private val isEmbedded: Boolean get() = arguments?.getBoolean(ARG_EMBEDDED) == true

    /** Promoting this toolbar while embedded would hand the suspended page an action bar. */
    override fun bindToolbar(view: View?) {
        if (!isEmbedded) super.bindToolbar(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.safety_hub_app_bar).isVisible = !isEmbedded
        if (!isEmbedded) {
            setActionBarTitle(R.string.safety_hub_page_title)
            setActionBarDisplayHomeAsUpEnabled()
        }
        viewModel = ViewModelProvider(this)[SafetyHubViewModel::class.java]
        view.findViewById<MaterialButton>(R.id.safety_hub_error_retry)
            .setOnClickListener { viewModel.retry() }
    }

    override fun onStart() {
        super.onStart()
        subscription = viewModel.observeViewState().distinctUntilChanged().subscribe(::render)
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun render(state: SafetyHubViewModel.ViewState) {
        val view = view ?: return
        val loaded = state as? SafetyHubViewModel.ViewState.Loaded
        view.findViewById<View>(R.id.safety_hub_loading).isVisible =
            state is SafetyHubViewModel.ViewState.Loading
        view.findViewById<View>(R.id.safety_hub_error).isVisible =
            state is SafetyHubViewModel.ViewState.Failed
        view.findViewById<View>(R.id.safety_hub_scroll).isVisible = loaded != null
        if (loaded == null) return

        renderStanding(view, loaded.standing)
        renderViolations(
            card = view.findViewById(R.id.safety_hub_active),
            violations = loaded.active,
            shown = activeShown,
            header = R.string.safety_hub_violations_container_header_active,
            subheader = R.string.safety_hub_violations_container_subheader_active,
            onShowMore = {
                activeShown += PAGE_SIZE
                render(loaded)
            },
        )
        renderViolations(
            card = view.findViewById(R.id.safety_hub_expired),
            violations = loaded.expired,
            shown = expiredShown,
            header = R.string.safety_hub_violations_container_header_expired,
            subheader = R.string.safety_hub_violations_container_subheader_expired,
            onShowMore = {
                expiredShown += PAGE_SIZE
                render(loaded)
            },
        )
    }

    private fun renderStanding(view: View, standing: AccountStandingState) {
        val accent = accentColor(standing)
        val copy = STANDING_COPY.getValue(standing)
        view.findViewById<TextView>(R.id.safety_hub_standing_title).text =
            I18nSpans.renderWithHook(getString(copy.title)) { ForegroundColorSpan(accent) }
        view.findViewById<TextView>(R.id.safety_hub_standing_description).text = I18nSpans.render(
            getString(copy.description),
            "termsOfService" to TOS_URL,
            "communityGuidelines" to GUIDELINES_URL,
        )
        view.findViewById<TextView>(R.id.safety_hub_standing_short).apply {
            text = I18nSpans.renderWithHook(getString(copy.short)) { ForegroundColorSpan(accent) }
            setTextColor(accent)
        }
        view.findViewById<AccountStandingProgressView>(R.id.safety_hub_standing_progress)
            .setStanding(standing, accent, ColorCompat.getThemedColor(view, R.attr.colorBackgroundModifierAccent))
    }

    private fun renderViolations(
        card: View,
        violations: List<SafetyHubViewModel.Violation>,
        shown: Int,
        @StringRes header: Int,
        @StringRes subheader: Int,
        onShowMore: () -> Unit,
    ) {
        card.isVisible = violations.isNotEmpty()
        if (violations.isEmpty()) return

        card.findViewById<TextView>(R.id.violations_card_header).text =
            I18nSpans.render(getString(header), "count" to violations.size)
        card.findViewById<TextView>(R.id.violations_card_subheader).text = getString(subheader)

        val items = card.findViewById<LinearLayout>(R.id.violations_card_items)
        items.removeAllViews()
        val visible = violations.take(shown)
        val inflater = LayoutInflater.from(card.context)
        visible.forEach { items.addView(violationRow(inflater, items, it)) }

        val remaining = violations.size - visible.size
        card.findViewById<TextView>(R.id.violations_card_more).apply {
            isVisible = remaining > 0
            text = I18nSpans.render(
                getString(R.string.safety_hub_violations_container_pagination_button),
                "nextPageSize" to minOf(remaining, PAGE_SIZE),
            )
            setOnClickListener { onShowMore() }
        }
    }

    private fun violationRow(
        inflater: LayoutInflater,
        parent: ViewGroup,
        violation: SafetyHubViewModel.Violation,
    ): View {
        val row = inflater.inflate(R.layout.widget_safety_hub_violation, parent, false)
        val description = violation.classification.description.orEmpty()
        val guildName = violation.guildName
        val emphasis = { ForegroundColorSpan(themed(R.attr.colorHeaderPrimary)) }
        row.findViewById<TextView>(R.id.violation_description).text = if (guildName == null) {
            I18nSpans.renderWithHook(
                getString(R.string.safety_hub_violations_container_description),
                "description" to description,
                hookSpan = emphasis,
            )
        } else {
            I18nSpans.renderWithHook(
                getString(R.string.safety_hub_violations_container_description_guild),
                "description" to description,
                "guildName" to guildName,
                hookSpan = emphasis,
            )
        }
        row.findViewById<TextView>(R.id.violation_expiration).apply {
            val expiry = violation.expiresAtMs
            isVisible = expiry != null
            if (expiry != null) {
                val template = if (violation.isExpired) {
                    R.string.safety_hub_violations_container_expiration_date_expired
                } else {
                    R.string.safety_hub_violations_container_expiration_date_active
                }
                text = I18nSpans.render(
                    getString(template),
                    "expirationDate" to TimeUtils.renderUtcDate(expiry, context, DateFormat.MEDIUM),
                )
            }
        }
        row.setOnClickListener {
            WidgetClassificationDetail.show(parentFragmentManager, violation.id)
        }
        return row
    }

    private fun accentColor(standing: AccountStandingState): Int = when (standing) {
        AccountStandingState.ALL_GOOD -> themed(R.attr.colorTextPositive)
        AccountStandingState.LIMITED -> themed(R.attr.colorTextWarning)
        // No token matches; the reference hardcodes this one too.
        AccountStandingState.VERY_LIMITED -> Color.parseColor("#FF7A00")
        AccountStandingState.AT_RISK, AccountStandingState.SUSPENDED -> themed(R.attr.colorTextDanger)
    }

    private fun themed(attr: Int): Int = ColorCompat.getThemedColor(requireContext(), attr)

    private data class StandingCopy(
        @StringRes val title: Int,
        @StringRes val description: Int,
        @StringRes val short: Int,
    )

    companion object {
        private const val ARG_EMBEDDED = "embedded"
        private const val PAGE_SIZE = 3
        private const val TOS_URL = "https://discord.com/terms"
        private const val GUIDELINES_URL = "https://discord.com/guidelines"

        private val STANDING_COPY = mapOf(
            AccountStandingState.ALL_GOOD to StandingCopy(
                R.string.safety_hub_safety_hub_all_good_title,
                R.string.safety_hub_safety_hub_all_good_description,
                R.string.safety_hub_safety_hub_all_good_short,
            ),
            AccountStandingState.LIMITED to StandingCopy(
                R.string.safety_hub_safety_hub_limited_title,
                R.string.safety_hub_safety_hub_limited_description,
                R.string.safety_hub_safety_hub_limited_short,
            ),
            AccountStandingState.VERY_LIMITED to StandingCopy(
                R.string.safety_hub_safety_hub_very_limited_title,
                R.string.safety_hub_safety_hub_very_limited_description,
                R.string.safety_hub_safety_hub_very_limited_short,
            ),
            AccountStandingState.AT_RISK to StandingCopy(
                R.string.safety_hub_safety_hub_at_risk_title,
                R.string.safety_hub_safety_hub_at_risk_description,
                R.string.safety_hub_safety_hub_at_risk_short,
            ),
            AccountStandingState.SUSPENDED to StandingCopy(
                R.string.safety_hub_safety_hub_suspended_title,
                R.string.safety_hub_safety_hub_suspended_description,
                R.string.safety_hub_safety_hub_suspended_short,
            ),
        )

        fun embedded(): WidgetSafetyHub = WidgetSafetyHub().apply {
            arguments = Bundle().apply { putBoolean(ARG_EMBEDDED, true) }
        }
    }
}
