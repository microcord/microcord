package com.discord.widgets.safetyhub

import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.safetyhub.SafetyGuildMemberType
import com.discord.api.safetyhub.SafetyRecordAction
import com.discord.api.safetyhub.SafetyRecordClassification
import com.discord.app.AppBottomSheet
import com.discord.i18n.I18nSpans
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import java.text.DateFormat
import rx.subscriptions.CompositeSubscription

class WidgetClassificationDetail : AppBottomSheet() {

    override fun getContentViewResId(): Int = R.layout.widget_classification_detail

    override fun bindSubscriptions(subscriptions: CompositeSubscription) {
        val classificationId = argumentsOrDefault.getString(ARG_CLASSIFICATION_ID) ?: return dismiss()
        subscriptions.add(
            StoreStream.getSafetyHub().observeState().ui(this).subscribe { state ->
                val classification = state.classifications.firstOrNull { it.id == classificationId }
                if (classification == null) dismiss() else render(classification, state.hub?.isAppealEligible == true)
            },
        )
    }

    private fun render(classification: SafetyRecordClassification, isAppealEligible: Boolean) {
        val view = view ?: return
        val emphasis = {
            ForegroundColorSpan(ColorCompat.getThemedColor(view, R.attr.colorHeaderPrimary))
        }
        val guildName = classification.guildMetadata?.name
        val header = when {
            guildName == null -> R.string.safety_hub_classification_detail_header
            classification.guildMetadata?.memberType == SafetyGuildMemberType.OWNER ->
                R.string.safety_hub_classification_detail_header_guild
            else -> R.string.safety_hub_classification_detail_header_guild_presence
        }
        view.findViewById<TextView>(R.id.classification_header).text = I18nSpans.renderWithHook(
            getString(header),
            "classificationType" to classification.description.orEmpty(),
            "guildName" to guildName.orEmpty(),
            hookSpan = emphasis,
        )

        renderEvidence(view, classification)
        renderActions(view, classification)

        view.findViewById<TextView>(R.id.classification_what_you_can_do).text = I18nSpans.render(
            getString(R.string.safety_hub_classification_detail_what_you_can_do_description),
            "tosLink" to TOS_URL,
            "communityGuidelinesLink" to GUIDELINES_URL,
        )

        view.findViewById<TextView>(R.id.classification_policy).apply {
            val link = classification.explainerLink
            isVisible = link != null
            text = I18nSpans.render(
                getString(R.string.safety_hub_classification_detail_policy_card_header),
                "classificationDescription" to classification.description.orEmpty(),
            )
            setOnClickListener { if (link != null) UriHandler.handle(context, link) }
        }

        view.findViewById<TextView>(R.id.classification_appeal).apply {
            val appealed = classification.appealStatus?.status != null
            isVisible = appealed || isAppealEligible
            if (appealed) {
                text = getString(R.string.safety_hub_classification_detail_appeal_status)
                setOnClickListener(null)
            } else {
                text = I18nSpans.renderWithHook(
                    getString(R.string.safety_hub_classification_detail_appeal),
                    hookSpan = { ForegroundColorSpan(ColorCompat.getThemedColor(this, R.attr.colorTextLink)) },
                )
                setOnClickListener {
                    WidgetSafetyHubAppeal.show(parentFragmentManager, classification.id, classification.isSpam)
                }
            }
        }
    }

    private fun renderEvidence(view: View, classification: SafetyRecordClassification) {
        val evidence = classification.flaggedContent.mapNotNull { it.content?.takeIf(String::isNotBlank) }
        view.findViewById<View>(R.id.classification_evidence_header).isVisible = evidence.isNotEmpty()
        view.findViewById<LinearLayout>(R.id.classification_evidence).apply {
            removeAllViews()
            evidence.forEach { addView(bodyText(it)) }
        }
    }

    private fun renderActions(view: View, classification: SafetyRecordClassification) {
        val lines = classification.actions.flatMap(SafetyRecordAction::descriptions)
        val expiry = classification.maxExpirationTime
            ?.let(TimeUtils::parseUTCDate)
            ?.takeIf { it > 0 }
        view.findViewById<LinearLayout>(R.id.classification_actions).apply {
            removeAllViews()
            lines.forEach { addView(bodyText("•  $it")) }
            if (expiry != null) {
                val date = TimeUtils.renderUtcDate(expiry, context, DateFormat.MEDIUM)
                addView(
                    bodyText(
                        "•  " + I18nSpans.render(
                            getString(R.string.safety_hub_classification_detail_expiration),
                            "expirationDate" to date,
                        ),
                    ),
                )
            }
        }
    }

    private fun bodyText(text: CharSequence): TextView = TextView(requireContext()).apply {
        setTextColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderSecondary))
        textSize = TEXT_SIZE_SP
        setPadding(0, PADDING_PX, 0, PADDING_PX)
        this.text = text
    }

    companion object {
        private const val ARG_CLASSIFICATION_ID = "classification_id"
        private const val TEXT_SIZE_SP = 14f
        private const val PADDING_PX = 6
        private const val TOS_URL = "https://discord.com/terms"
        private const val GUIDELINES_URL = "https://discord.com/guidelines"

        fun show(fragmentManager: FragmentManager, classificationId: String) {
            WidgetClassificationDetail()
                .apply { arguments = Bundle().apply { putString(ARG_CLASSIFICATION_ID, classificationId) } }
                .show(fragmentManager, WidgetClassificationDetail::class.java.name)
        }
    }
}
