package com.discord.widgets.servers.member_verification

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.models.domain.ModelInvite
import com.discord.models.guild.Guild
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.LoadingButton
import com.discord.views.MemberVerificationAvatarView
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.email.WidgetUserEmailVerify
import com.discord.widgets.user.phone.WidgetUserPhoneManage
import com.facebook.drawee.view.SimpleDraweeView
import com.google.gson.Gson

class WidgetMemberVerification : AppFragment(R.layout.widget_member_verification) {

    private val guildId: Long get() = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, 0L)
    private val location: String get() = mostRecentIntent.getStringExtra(INTENT_EXTRA_LOCATION).orEmpty()
    private val inviteGuild: Guild? by lazy {
        mostRecentIntent.getStringExtra(INTENT_EXTRA_INVITE_GUILD)
            ?.let { Gson().fromJson(it, Guild::class.java) }
    }
    private val viewModel: WidgetMemberVerificationViewModel by appViewModels {
        WidgetMemberVerificationViewModel(guildId, location, inviteGuild)
    }

    private lateinit var scrollView: NestedScrollView
    private lateinit var dimmer: DimmerView
    private lateinit var rules: RecyclerView
    private lateinit var confirm: LoadingButton
    private lateinit var verification: MemberVerificationView
    private lateinit var guildIcon: MemberVerificationAvatarView
    private lateinit var adapter: MemberVerificationRulesAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.member_verification_modal_title)
        scrollView = view.findViewById(R.id.member_verification_scrollview)
        dimmer = view.findViewById(R.id.dimmer_view)
        rules = view.findViewById(R.id.member_verification_rules_recycler)
        confirm = view.findViewById(R.id.member_verification_rules_confirm)
        verification = view.findViewById(R.id.member_verification_container)
        guildIcon = view.findViewById(R.id.member_verification_guild_icon)
        adapter = MemberVerificationRulesAdapter(rules, this).apply {
            onUpdateRulesApproval = viewModel::updateTermsApproval
            onUserInputDataEntered = viewModel::updateFormValidation
        }
        rules.setHasFixedSize(false)
        rules.isNestedScrollingEnabled = false
        rules.adapter = adapter
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this)
            .appSubscribe(WidgetMemberVerification::class.java, context, onNext = ::configure)
        viewModel.observeEvents().ui(this)
            .appSubscribe(WidgetMemberVerification::class.java, context, onNext = ::handleEvent)
    }

    private fun configure(state: WidgetMemberVerificationViewModel.ViewState) {
        when (state) {
            WidgetMemberVerificationViewModel.ViewState.Invalid -> {
                AppToast.show(this, R.string.default_failure_to_perform_action_message)
                requireActivity().finish()
            }
            WidgetMemberVerificationViewModel.ViewState.Loading -> {
                scrollView.visibility = View.GONE
                dimmer.setDimmed(true)
            }
            is WidgetMemberVerificationViewModel.ViewState.Loaded -> configureLoaded(state)
        }
    }

    private fun configureLoaded(state: WidgetMemberVerificationViewModel.ViewState.Loaded) {
        scrollView.visibility = View.VISIBLE
        dimmer.setDimmed(false)
        confirm.isEnabled = !state.disabled
        confirm.setIsLoading(state.submitting)
        confirm.setOnClickListener { viewModel.applyToJoinGuild() }
        rules.visibility = if (state.isRulesListVisible) View.VISIBLE else View.GONE
        adapter.setData(state.formItems)
        configureGuildIcon(state.guild)
        verification.visibility = if (state.verificationType == null) View.GONE else View.VISIBLE
        when (state.verificationType) {
            WidgetMemberVerificationViewModel.VerificationType.PHONE -> verification.configure(
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
            WidgetMemberVerificationViewModel.VerificationType.EMAIL -> verification.configure(
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
        val avatar = guildIcon.findViewById<SimpleDraweeView>(R.id.member_verification_avatar)
        val fallback = guildIcon.findViewById<TextView>(R.id.member_verification_text)
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

    private fun handleEvent(event: WidgetMemberVerificationViewModel.Event) {
        when (event) {
            WidgetMemberVerificationViewModel.Event.Error ->
                AppToast.show(this, R.string.guild_settings_public_update_failed)
            is WidgetMemberVerificationViewModel.Event.Success -> {
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
                },
            )
        }
    }
}
