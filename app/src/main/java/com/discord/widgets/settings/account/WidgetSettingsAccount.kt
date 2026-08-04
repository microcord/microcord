package com.discord.widgets.settings.account

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.airbnb.lottie.LottieAnimationView
import com.discord.R
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.AppViewFlipper
import com.discord.models.user.MeUser
import com.discord.stores.StoreMFA
import com.discord.stores.StoreStream
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.user.UserUtils.hasPhone
import com.discord.utilities.user.UserUtils.isMfaSMSEnabled
import com.discord.utilities.user.UserUtils.isPartner
import com.discord.utilities.user.UserUtils.isStaff
import com.discord.utilities.view.extensions.setEnabledAlpha
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.CheckedSetting
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.account.mfa.WidgetEnableMFASteps
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.email.WidgetUserEmailVerify
import com.discord.widgets.user.phone.WidgetUserPhoneManage
import com.google.android.material.button.MaterialButton
import com.discord.utilities.dimmer.DimmerView
import rx.Observable
import rx.functions.Action1

class WidgetSettingsAccount : AppFragment(R.layout.widget_settings_account) {

    private lateinit var dimmer: DimmerView
    private lateinit var scroll: NestedScrollView
    private lateinit var verification: View
    private lateinit var verificationTitle: TextView
    private lateinit var verificationButton: MaterialButton
    private lateinit var tagText: TextView
    private lateinit var nameContainer: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var emailText: TextView
    private lateinit var phoneText: TextView
    private lateinit var mfaEnabledHeader: TextView
    private lateinit var mfaFlipper: AppViewFlipper
    private lateinit var mfaEnabledContainer: LinearLayout
    private lateinit var mfaDisabledContainer: LinearLayout
    private lateinit var mfaLottie: LottieAnimationView
    private lateinit var mfaSalesPitch: TextView
    private lateinit var mfaEnable: MaterialButton
    private lateinit var backupCodes: TextView
    private lateinit var authenticators: TextView
    private lateinit var removeMfa: TextView
    private lateinit var smsBackup: CheckedSetting
    private lateinit var smsPhone: TextView
    private lateinit var privateDataWrap: LinearLayout

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        SettingsNavigation.bindBack(this)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.user_settings_my_account)
        setActionBarDisplayHomeAsUpEnabled()

        dimmer = view.findViewById(R.id.dimmer_view)
        scroll = view.findViewById(R.id.settings_account_scroll)
        verification = view.findViewById(R.id.settings_account_verification)
        verificationTitle = view.findViewById(R.id.settings_account_verification_title)
        verificationButton = view.findViewById(R.id.settings_account_verification_button)
        tagText = view.findViewById(R.id.settings_account_tag_text)
        nameContainer = view.findViewById(R.id.settings_account_name_container)
        nameText = view.findViewById(R.id.settings_account_name_text)
        emailText = view.findViewById(R.id.settings_account_email_text)
        phoneText = view.findViewById(R.id.settings_account_phone_text)
        mfaEnabledHeader = view.findViewById(R.id.settings_account_mfa_enabled_header)
        mfaFlipper = view.findViewById(R.id.settings_account_mfa_flipper)
        mfaEnabledContainer = view.findViewById(R.id.settings_account_mfa_enabled_container)
        mfaDisabledContainer = view.findViewById(R.id.settings_account_mfa_disabled_container)
        mfaLottie = view.findViewById(R.id.settings_account_mfa_lottie)
        mfaSalesPitch = view.findViewById(R.id.settings_account_mfa_sales_pitch)
        mfaEnable = view.findViewById(R.id.settings_account_mfa_enable)
        backupCodes = view.findViewById(R.id.settings_account_view_backup_codes)
        authenticators = view.findViewById(R.id.settings_account_authenticators)
        removeMfa = view.findViewById(R.id.settings_account_remove_two_fa)
        smsBackup = view.findViewById(R.id.settings_account_sms_backup)
        smsPhone = view.findViewById(R.id.settings_account_sms_phone)
        privateDataWrap = view.findViewById(R.id.settings_account_private_data_wrap)

        listOf(
            view.findViewById<View>(R.id.settings_account_information_header),
            view.findViewById(R.id.settings_account_2fa_header),
            view.findViewById(R.id.settings_account_management_header),
        ).forEach(AccessibilityUtils::setViewIsHeading)

        if (arguments?.getBoolean(EXTRA_HINT_DATA_MANAGEMENT) == true) {
            scroll.post { scroll.smoothScrollTo(0, privateDataWrap.bottom) }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        Model.observe()
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetSettingsAccount::class.java) { configureUI(it) }
    }

    private fun configureUI(model: Model) {
        val user = model.meUser
        configureVerification(user)
        tagText.text = user.getUserNameWithDiscriminator()
        nameContainer.visibility = if (model.contactSyncConnection != null) View.VISIBLE else View.GONE
        nameText.text = model.contactSyncConnection?.name.orEmpty()
        emailText.text = user.email
        phoneText.text = user.phoneNumber

        view?.findViewById<View>(R.id.settings_account_change_password)?.setOnClickListener {
            WidgetSettingsAccountChangePassword.launch(it.context)
        }
        view?.findViewById<View>(R.id.settings_account_tag_container)?.setOnClickListener {
            WidgetSettingsAccountUsernameEdit.launch(it.context)
        }
        nameContainer.setOnClickListener { WidgetSettingsAccountContactsNameEdit.launch(it.context) }
        view?.findViewById<View>(R.id.settings_account_email_container)?.setOnClickListener {
            WidgetSettingsAccountEmailEdit.launch(it.context, user.isVerified)
        }
        view?.findViewById<View>(R.id.settings_account_phone_container)?.setOnClickListener {
            WidgetUserPhoneManage.launch(
                it.context,
                WidgetUserAccountVerifyBase.Mode.UNFORCED,
                WidgetUserPhoneManage.Source.USER_SETTINGS_UPDATE,
            )
        }
        view?.findViewById<View>(R.id.settings_blocked_users)?.setOnClickListener {
            SettingsNavigation.launch(it.context, WidgetSettingsBlockedUsers::class.java)
        }

        val displayedMfa = when (model.pendingMFAState.activationState) {
            StoreMFA.MFAActivationState.PENDING_ENABLED -> true
            StoreMFA.MFAActivationState.PENDING_DISABLED -> false
            StoreMFA.MFAActivationState.NONE -> user.mfaEnabled
        }
        configureMfa(displayedMfa, model.pendingMFAState.activationState != StoreMFA.MFAActivationState.NONE)
        mfaEnable.setOnClickListener { WidgetEnableMFASteps.launch(it.context) }
        backupCodes.setOnClickListener { WidgetSettingsAccountBackupCodes.launch(it.context) }
        authenticators.setOnClickListener { WidgetSettingsAuthenticators.launch(it.context) }
        removeMfa.setOnClickListener { showRemoveMfaModal() }
        configureSmsBackup(user, model.pendingMFAState.isTogglingSMSBackup)

        view?.findViewById<View>(R.id.settings_account_private_data_disable)?.setOnClickListener {
            showDisableDelete(model, WidgetDisableDeleteAccountDialog.Mode.DISABLE)
        }
        view?.findViewById<View>(R.id.settings_account_private_data_delete)?.setOnClickListener {
            showDisableDelete(model, WidgetDisableDeleteAccountDialog.Mode.DELETE)
        }
    }

    private fun configureVerification(user: MeUser) {
        verification.visibility = if (user.isVerified) View.GONE else View.VISIBLE
        if (user.email == null) {
            verificationTitle.setText(R.string.add_email_banner_title)
            verificationButton.setText(R.string.add_email_short)
        } else {
            verificationTitle.setText(R.string.verify_your_email)
            verificationButton.setText(R.string.verify)
        }
        verificationButton.setOnClickListener {
            WidgetUserEmailVerify.launch(it.context, WidgetUserAccountVerifyBase.Mode.UNFORCED)
        }
    }

    private fun configureMfa(enabled: Boolean, pending: Boolean) {
        mfaEnabledHeader.visibility = if (enabled) View.VISIBLE else View.GONE
        mfaFlipper.displayedChild = if (enabled) MFA_ENABLED_VIEW_INDEX else MFA_DISABLED_VIEW_INDEX
        mfaLottie.visibility = if (enabled) View.GONE else View.VISIBLE
        mfaSalesPitch.visibility = if (enabled) View.GONE else View.VISIBLE
        mfaEnable.visibility = if (enabled) View.GONE else View.VISIBLE
        listOf(mfaEnable, backupCodes, authenticators, removeMfa).forEach { it.isEnabled = !pending }
        mfaDisabledContainer.setEnabledAlpha(!pending)
        mfaEnabledContainer.setEnabledAlpha(!pending)
    }

    private fun configureSmsBackup(user: MeUser, toggling: Boolean) {
        val disabledMessage = when {
            user.isPartner || user.isStaff -> getString(R.string.mfa_sms_disabled_partner)
            user.email == null -> getString(R.string.mfa_sms_disabled_no_email)
            else -> null
        }
        smsBackup.setChecked(user.isMfaSMSEnabled, false)
        smsBackup.setSubtext(disabledMessage ?: getString(R.string.mfa_sms_auth_sales_pitch), false)
        smsBackup.isEnabled = disabledMessage == null && !toggling
        smsPhone.setEnabledAlpha(disabledMessage == null && !toggling)
        val actionable = disabledMessage == null && !toggling
        smsBackup.setContainerClickListener(
            if (actionable && !user.hasPhone) {
                View.OnClickListener {
                    WidgetUserPhoneManage.launch(
                        requireContext(),
                        WidgetUserAccountVerifyBase.Mode.NO_HISTORY_FROM_USER_SETTINGS,
                        WidgetUserPhoneManage.Source.MFA_PHONE_UPDATE,
                    )
                }
            } else {
                null
            },
        )
        // The switch is reverted immediately and only moves once USER_UPDATE confirms the change,
        // so a cancelled password prompt cannot leave it showing a setting the account does not have.
        smsBackup.setOnCheckedListener(
            if (actionable && user.hasPhone) {
                Action1 { enable ->
                    smsBackup.setChecked(user.isMfaSMSEnabled, false)
                    WidgetEnableSMSBackupDialog.show(parentFragmentManager, enable)
                }
            } else {
                null
            },
        )
        smsPhone.setTextAndVisibilityBy(
            user.phoneNumber?.let {
                SettingsStrings.render(
                    requireContext(),
                    R.string.mfa_sms_auth_current_phone,
                    "phoneNumber" to it,
                )
            },
        )

        if (arguments?.getSerializable(EXTRA_REDIRECT) == Redirect.SMS_BACKUP) {
            arguments?.remove(EXTRA_REDIRECT)
            if (!user.hasPhone) {
                WidgetUserPhoneManage.launch(
                    requireContext(),
                    WidgetUserAccountVerifyBase.Mode.NO_HISTORY_FROM_USER_SETTINGS,
                    WidgetUserPhoneManage.Source.MFA_PHONE_UPDATE,
                )
            }
        }
    }

    private fun showDisableDelete(model: Model, mode: WidgetDisableDeleteAccountDialog.Mode) {
        if (model.ownsAnyGuilds) {
            WidgetNoticeDialog.Builder(requireContext())
                .setTitle(R.string.delete_account_transfer_ownership)
                .setMessage(R.string.delete_account_transfer_ownership_body)
                .setPositiveButton(R.string.okay)
                .show(parentFragmentManager)
        } else {
            WidgetDisableDeleteAccountDialog.show(parentFragmentManager, mode)
        }
    }

    /** The code prompt is gone: the endpoint is MFA-gated, so `MfaInterceptor` collects it. */
    private fun showRemoveMfaModal() {
        WidgetNoticeDialog.Builder(requireContext())
            .setTitle(R.string.two_fa_remove)
            .setMessage(R.string.two_fa_remove_confirmation)
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.two_fa_remove) { removeMfa() }
            .show(parentFragmentManager)
    }

    private fun removeMfa() {
        RestAPI.api.disableMFA()
            .restSubscribeOn()
            .withDimmer(dimmer)
            .ui(this)
            .appSubscribe(WidgetSettingsAccount::class.java, context) { response ->
                AppToast.show(this, R.string.user_settings_mfa_removed)
                StoreStream.getAuthentication().setAuthed(response.token)
                StoreStream.getMFA().updatePendingMFAState(StoreMFA.MFAActivationState.PENDING_DISABLED)
            }
    }

    data class Model(
        val meUser: MeUser,
        val pendingMFAState: StoreMFA.State,
        val ownsAnyGuilds: Boolean,
        val contactSyncConnection: ConnectedAccount?,
    ) {
        companion object {
            fun observe(): Observable<Model> = Observable.combineLatest(
                StoreStream.getUsers().observeMe(),
                StoreStream.getMFA().observeState(),
                StoreStream.getGuilds().observeGuilds(),
                StoreStream.getUserConnections().observeConnectedAccounts(),
                StoreStream.getExperiments().observeUserExperiment(CONTACT_SYNC_EXPERIMENT, true),
            ) { me, mfa, guilds, connections, experiment ->
                Model(
                    meUser = me,
                    pendingMFAState = mfa,
                    ownsAnyGuilds = guilds.values.any { it.ownerId == me.id },
                    contactSyncConnection = connections.connectedAccounts
                        .lastOrNull { it.type == CONTACTS_TYPE }
                        .takeIf { experiment?.bucket == 1 },
                )
            }.distinctUntilChanged()
        }
    }

    enum class Redirect { SMS_BACKUP }

    companion object {
        private const val EXTRA_HINT_DATA_MANAGEMENT = "com.discord.extra.HINT_DATA_MANAGEMENT"
        private const val EXTRA_REDIRECT = "extra_redirect"
        private const val CONTACT_SYNC_EXPERIMENT = "2021-04_contact_sync_android_main"
        private const val CONTACTS_TYPE = "contacts"
        private const val MFA_ENABLED_VIEW_INDEX = 0
        private const val MFA_DISABLED_VIEW_INDEX = 1

        @JvmStatic
        @JvmOverloads
        fun launch(context: Context, hintDataManagement: Boolean = false, redirect: Redirect? = null) {
            SettingsNavigation.launch(
                context,
                WidgetSettingsAccount::class.java,
                Intent().apply {
                    putExtra(EXTRA_HINT_DATA_MANAGEMENT, hintDataManagement)
                    putExtra(EXTRA_REDIRECT, redirect)
                    if (redirect != null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }
}
