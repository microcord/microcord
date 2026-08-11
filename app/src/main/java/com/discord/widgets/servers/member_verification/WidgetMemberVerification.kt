package com.discord.widgets.servers.member_verification

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.view.View
import androidx.core.graphics.drawable.DrawableCompat
import com.discord.R
import com.discord.databinding.WidgetMemberVerificationBinding
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.models.domain.ModelInvite
import com.discord.models.guild.Guild
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.email.WidgetUserEmailVerify
import com.discord.widgets.user.phone.WidgetUserPhoneManage
import com.google.gson.Gson

class WidgetMemberVerification : AppFragment(R.layout.widget_member_verification) {

    private val guildId: Long get() = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, 0L)
    private val location: String get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_LOCATION).orEmpty()
    private val inviteGuild: Guild? by lazy {
        mostRecentIntent.getStringExtra(INTENT_EXTRA_INVITE_GUILD)
            ?.let { Gson().fromJson(it, Guild::class.java) }
    }
    private val inviteCode: String? get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_INVITE_CODE)
    private val viewModel: WidgetMemberVerificationViewModel by appViewModels {
        WidgetMemberVerificationViewModel(guildId, location, inviteGuild, inviteCode)
    }

    private var _binding: WidgetMemberVerificationBinding? = null
    private val binding get() = checkNotNull(_binding)
    private lateinit var adapter: MemberVerificationRulesAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetMemberVerificationBinding.bind(view)
        setActionBarTitle(R.string.member_verification_modal_title)
        binding.actionBarToolbar.navigationIcon?.mutate()?.let { icon ->
                DrawableCompat.setTint(icon, ColorCompat.getThemedColor(requireContext(), R.attr.colorInteractiveNormal))
            }
        adapter = MemberVerificationRulesAdapter(binding.memberVerificationRulesRecycler, this).apply {
            onUpdateRulesApproval = viewModel::updateTermsApproval
            onUserInputDataEntered = viewModel::updateFormValidation
        }
        binding.memberVerificationRulesRecycler.setHasFixedSize(false)
        binding.memberVerificationRulesRecycler.isNestedScrollingEnabled = false
        binding.memberVerificationRulesRecycler.adapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        binding.actionBarToolbar.setNavigationOnClickListener { closeRules() }
        setOnBackPressed({
            closeRules()
            true
        })
        viewModel.observeViewState().ui(this)
            .appSubscribe(WidgetMemberVerification::class.java, context, onNext = ::configure)
        viewModel.observeEvents().ui(this)
            .appSubscribe(WidgetMemberVerification::class.java, context, onNext = ::handleEvent)
    }

    private fun closeRules() {
        requireActivity().setResult(Activity.RESULT_CANCELED)
        requireActivity().finish()
    }

    private fun configure(state: WidgetMemberVerificationViewModel.ViewState) {
        when (state) {
            WidgetMemberVerificationViewModel.ViewState.Invalid -> {
                AppToast.show(this, R.string.default_failure_to_perform_action_message)
                requireActivity().finish()
            }
            WidgetMemberVerificationViewModel.ViewState.Loading -> {
                binding.memberVerificationScrollview.visibility = View.GONE
                binding.dimmerView.setDimmed(true)
            }
            is WidgetMemberVerificationViewModel.ViewState.Loaded -> configureLoaded(state)
        }
    }

    private fun configureLoaded(state: WidgetMemberVerificationViewModel.ViewState.Loaded) {
        binding.memberVerificationScrollview.visibility = View.VISIBLE
        binding.dimmerView.setDimmed(false)
        binding.memberVerificationRulesConfirm.isEnabled = !state.disabled
        binding.memberVerificationRulesConfirm.setIsLoading(state.submitting)
        binding.memberVerificationRulesConfirm.setOnClickListener { viewModel.applyToJoinGuild() }
        binding.memberVerificationRulesRecycler.visibility = if (state.isRulesListVisible) View.VISIBLE else View.GONE
        adapter.setData(state.formItems)
        configureGuildIcon(state.guild)
        binding.memberVerificationContainer.visibility = if (state.verificationType == null) View.GONE else View.VISIBLE
        when (state.verificationType) {
            WidgetMemberVerificationViewModel.VerificationType.PHONE -> binding.memberVerificationContainer.configure(
                R.drawable.ic_phone_verification_24dp,
                R.string.member_verification_form_item_phone_verification_label,
                !state.needsAdditionalVerification,
            ) {
                WidgetUserPhoneManage.launch(
                    requireContext(),
                    WidgetUserAccountVerifyBase.Mode.UNFORCED,
                    WidgetUserPhoneManage.Source.GUILD_PHONE_REQUIRED,
                )
            }
            WidgetMemberVerificationViewModel.VerificationType.EMAIL -> binding.memberVerificationContainer.configure(
                R.drawable.ic_email_verification_24dp,
                R.string.member_verification_form_item_email_verification_label,
                !state.needsAdditionalVerification,
            ) {
                WidgetUserEmailVerify.launch(requireContext(), WidgetUserAccountVerifyBase.Mode.UNFORCED)
            }
            null -> Unit
        }
    }

    private fun configureGuildIcon(guild: Guild?) {
        val avatar = binding.memberVerificationGuildIcon.avatar
        val fallback = binding.memberVerificationGuildIcon.text
        val hasIcon = guild?.hasIcon() == true
        avatar.visibility = if (hasIcon) View.VISIBLE else View.GONE
        fallback.visibility = if (hasIcon) View.GONE else View.VISIBLE
        if (hasIcon) {
            MGImages.setImage(avatar, IconUtils.getForGuild(guild))
            fallback.text = ""
        } else {
            fallback.text = guild?.name.orEmpty().split(' ').mapNotNull { it.firstOrNull() }.joinToString("")
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun handleEvent(event: WidgetMemberVerificationViewModel.Event) {
        when (event) {
            is WidgetMemberVerificationViewModel.Event.Error ->
                AppToast.show(this, event.message ?: getString(R.string.guild_settings_public_update_failed))
            is WidgetMemberVerificationViewModel.Event.Success -> {
                requireActivity().setResult(Activity.RESULT_OK)
                requireActivity().finish()
                when {
                    event.applicationStatus == ApplicationStatus.PENDING &&
                        event.guildId != null && event.guildName != null ->
                        MemberVerificationPendingDialog.enqueue(
                            event.guildId,
                            MemberVerificationPendingViewModel.DialogState.PENDING,
                        )
                    event.applicationStatus == ApplicationStatus.APPROVED &&
                        event.guildId != null && event.guildName != null && event.lastSeen == null ->
                        MemberVerificationSuccessDialog.enqueue(event.guildId)
                }
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_INVITE_GUILD = "INTENT_EXTRA_INVITE_GUILD"
        private const val INTENT_EXTRA_INVITE_CODE = "INTENT_EXTRA_INVITE_CODE"
        private const val INTENT_EXTRA_LOCATION = "INTENT_EXTRA_LOCATION"

        @JvmOverloads
        fun create(context: Context, guildId: Long, location: String, invite: ModelInvite? = null) {
            SettingsNavigation.launch(
                context,
                WidgetMemberVerification::class.java,
                Intent().apply {
                    putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    putExtra(INTENT_EXTRA_LOCATION, location)
                    invite?.guild?.let { putExtra(INTENT_EXTRA_INVITE_GUILD, Gson().toJson(it)) }
                    // Carried so a rejected applicant can read the form again: the server serves it
                    // to them only when the invite they arrived on is presented alongside.
                    invite?.code?.let { putExtra(INTENT_EXTRA_INVITE_CODE, it) }
                },
            )
        }

        fun createIntent(context: Context, guildId: Long, location: String): Intent =
            com.discord.app.AppScreenRouter.createScreenIntent(
                context,
                WidgetMemberVerification::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_LOCATION, location),
            )
    }
}
