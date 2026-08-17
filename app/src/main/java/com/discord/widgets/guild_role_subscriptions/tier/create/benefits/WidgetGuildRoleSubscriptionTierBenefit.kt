package com.discord.widgets.guild_role_subscriptions.tier.create.benefits

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.setCompoundDrawableWithIntrinsicBounds
import com.discord.utilities.view.extensions.setTextIfDifferent
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit
import com.discord.views.VitoImageView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class WidgetGuildRoleSubscriptionTierBenefit :
    AppFragment(R.layout.widget_guild_role_subscription_tier_benefit) {

    private val benefitType: GuildRoleSubscriptionBenefitType
        @Suppress("DEPRECATION")
        get() = mostRecentIntent.getSerializableExtra(INTENT_EXTRA_BENEFIT_TYPE)
            as? GuildRoleSubscriptionBenefitType
            ?: GuildRoleSubscriptionBenefitType.UNKNOWN
    private val existingBenefit: Benefit?
        get() = intentParcelable(INTENT_EXTRA_EXISTING_BENEFIT)
    private val tierName: String?
        get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_TIER_NAME)

    private val viewModel: GuildRoleSubscriptionTierBenefitViewModel by appViewModels {
        GuildRoleSubscriptionTierBenefitViewModel(benefitType, tierName, existingBenefit)
    }

    private lateinit var subtitle: TextView
    private lateinit var channelLabel: TextView
    private lateinit var channelName: TextView
    private lateinit var intangibleNameLabel: TextView
    private lateinit var intangibleNameLayout: TextInputLayout
    private lateinit var emojiImage: VitoImageView
    private lateinit var emojiName: TextView
    private lateinit var emojiSelector: View
    private lateinit var descriptionLayout: TextInputLayout
    private lateinit var title: TextView
    private lateinit var warning: TextView
    private lateinit var save: MaterialButton
    private lateinit var remove: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        subtitle = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefit_subtitle))
        channelLabel = requireNotNull(view.findViewById(R.id.guild_role_subscription_benefit_channel_label))
        channelName = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefit_name))
        intangibleNameLabel = requireNotNull(
            view.findViewById(R.id.guild_role_subscription_intangible_benefit_name_label),
        )
        intangibleNameLayout = requireNotNull(
            view.findViewById(R.id.guild_role_subscription_intangible_benefit_name_layout),
        )
        emojiImage = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefit_emoji))
        emojiName = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefit_emoji_name))
        emojiSelector = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefit_select_emoji))
        descriptionLayout = requireNotNull(
            view.findViewById(R.id.guild_role_subscription_tier_benefit_description),
        )
        title = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefit_title))
        warning = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_channel_benefit_warning))
        save = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefit_save))
        remove = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefit_remove))

        when (benefitType) {
            GuildRoleSubscriptionBenefitType.CHANNEL -> configureChannelBenefit()
            GuildRoleSubscriptionBenefitType.INTANGIBLE -> configureIntangibleBenefit()
            GuildRoleSubscriptionBenefitType.UNKNOWN -> cancel()
        }

        // ChannelPickerBottomSheet and EmojiPickerNavigator are not reconstructed. Existing values
        // remain visible and editable around these two controls, but the controls cannot be opened.
        channelName.isEnabled = false
        emojiSelector.isEnabled = false
        channelName.alpha = BLOCKED_AFFORDANCE_ALPHA
        emojiSelector.alpha = BLOCKED_AFFORDANCE_ALPHA

        requireNotNull<TextInputEditText>(
            view.findViewById(R.id.guild_role_subscription_tier_benefit_description_edit_text),
        ).addBindedTextWatcher(this) { viewModel.updateDescription(it.toString()) }

        save.setOnClickListener { viewModel.submitResult() }
        requireNotNull<View>(view.findViewById(R.id.guild_role_subscription_tier_benefit_cancel))
            .setOnClickListener { cancel() }
        remove.visibility = if (existingBenefit == null) View.GONE else View.VISIBLE
        remove.setOnClickListener { finishWithResult(existingBenefit, null) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionTierBenefit::class.java, onNext = ::configureUI)
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionTierBenefit::class.java) { event ->
                when (event) {
                    is GuildRoleSubscriptionTierBenefitViewModel.Event.SendResult ->
                        finishWithResult(existingBenefit, event.benefit)
                }
            }
    }

    private fun configureChannelBenefit() {
        channelLabel.visibility = View.VISIBLE
        channelName.visibility = View.VISIBLE
        warning.visibility = View.VISIBLE
        intangibleNameLabel.visibility = View.GONE
        intangibleNameLayout.visibility = View.GONE
        title.setText(R.string.guild_role_subscription_tier_exclusive_channel_title)
        descriptionLayout.editText?.setHint(R.string.guild_role_subscription_tier_detail_description_placeholder)
    }

    private fun configureIntangibleBenefit() {
        channelLabel.visibility = View.GONE
        channelName.visibility = View.GONE
        warning.visibility = View.GONE
        intangibleNameLabel.visibility = View.VISIBLE
        intangibleNameLayout.visibility = View.VISIBLE
        title.setText(R.string.guild_role_subscription_tier_additional_benefit_title)
        requireNotNull<TextInputEditText>(
            intangibleNameLayout.findViewById(R.id.guild_role_subscription_intangible_benefit_name),
        ).addBindedTextWatcher(this) { viewModel.updateName(it.toString()) }
    }

    private fun configureUI(state: GuildRoleSubscriptionTierBenefitViewModel.ViewState) {
        subtitle.text = state.tierName
        val emoji = state.emoji
        MGImages.setImage(
            emojiImage,
            emoji?.getImageUri(
                false,
                minOf(IconUtils.getMediaProxySize(resources.getDimensionPixelSize(R.dimen.emoji_size)), 64),
                requireContext(),
            ),
            useSmallCache = true,
        )
        emojiName.text = emoji?.firstName
            ?: getString(R.string.guild_role_subscription_tier_benefits_emoji_placeholder)
        descriptionLayout.setTextIfDifferent(state.description)
        if (!state.name.isNullOrBlank()) {
            channelName.text = state.name
            intangibleNameLayout.setTextIfDifferent(state.name)
        }
        channelName.setCompoundDrawableWithIntrinsicBounds(
            start = state.leadingNameIconResId ?: 0,
            end = R.drawable.icon_carrot,
        )
        save.isEnabled = state.canSubmitResult
    }

    private fun cancel() {
        requireActivity().setResult(Activity.RESULT_CANCELED)
        hideKeyboard()
        requireActivity().finish()
    }

    private fun finishWithResult(existing: Benefit?, benefit: Benefit?) {
        requireActivity().setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(RESULT_EXTRA_EXISTING_BENEFIT, existing)
                .putExtra(RESULT_EXTRA_BENEFIT, benefit),
        )
        hideKeyboard()
        requireActivity().finish()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : Parcelable> intentParcelable(key: String): T? =
        mostRecentIntent.getParcelableExtra(key)

    companion object {
        private const val INTENT_EXTRA_BENEFIT_TYPE = "INTENT_EXTRA_BENEFIT_TYPE"
        private const val INTENT_EXTRA_EXISTING_BENEFIT = "INTENT_EXTRA_EXISTING_BENEFIT"
        private const val INTENT_EXTRA_TIER_NAME = "INTENT_EXTRA_TIER_NAME"
        private const val RESULT_EXTRA_BENEFIT = "RESULT_EXTRA_BENEFIT"
        private const val RESULT_EXTRA_EXISTING_BENEFIT = "RESULT_EXTRA_EXISTING_BENEFIT"
        private const val BLOCKED_AFFORDANCE_ALPHA = 0.4f

        @JvmOverloads
        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            guildId: Long,
            benefitType: GuildRoleSubscriptionBenefitType,
            tierName: String? = null,
            benefit: Benefit? = null,
        ) {
            AppScreenRouter.launchForResult(
                context,
                launcher,
                WidgetGuildRoleSubscriptionTierBenefit::class.java,
                Intent()
                    .putExtra(EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_EXISTING_BENEFIT, benefit)
                    .putExtra(INTENT_EXTRA_BENEFIT_TYPE, benefitType)
                    .putExtra(INTENT_EXTRA_TIER_NAME, tierName),
            )
        }

        fun registerForResult(
            fragment: AppFragment,
            onBenefitUpdate: (Benefit?, Benefit?) -> Unit,
        ): ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            @Suppress("DEPRECATION")
            val benefit = result.data?.getParcelableExtra<Benefit>(RESULT_EXTRA_BENEFIT)
            @Suppress("DEPRECATION")
            val existing = result.data?.getParcelableExtra<Benefit>(RESULT_EXTRA_EXISTING_BENEFIT)
            if (benefit != null || existing != null) onBenefitUpdate(existing, benefit)
        }

        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
    }
}
