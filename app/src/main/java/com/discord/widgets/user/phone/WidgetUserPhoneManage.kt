package com.discord.widgets.user.phone

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import com.discord.R
import com.discord.app.AppScreenRouter
import com.discord.i18n.i18nFormat
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.user.UserUtils.isMfaSMSEnabled
import com.discord.views.phone.PhoneOrEmailInputView
import com.discord.widgets.contact_sync.ContactSyncDependencies
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.user.WidgetUserPasswordVerify
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase.Mode
import com.google.android.material.button.MaterialButton

/**
 * Add, replace or remove the phone number on the account.
 *
 * One screen covers all three because the API does: `POST users/@me/phone` starts a verification for
 * whatever number is typed, whether or not one is already on file, and the only branch is the
 * "remove" row that appears once a number exists.
 *
 * Removal is gated twice. It is hidden outright when the account has no e-mail address — taking the
 * phone number off an account with no other way to sign in would lock the user out — and when SMS
 * two-factor is on it first warns that removing the number also disables that.
 *
 * ### Secrets
 * This screen never touches the password: removal and confirmation both hand off to
 * [WidgetUserPasswordVerify], which collects it. The phone number itself is treated as sensitive per
 * the project contract — it is read from the input at submit time, rendered into the description
 * through the recovered string, and never logged (`RestAPIParams.Phone.toString()` redacts it, and
 * so does `MeUser.toString()`).
 */
class WidgetUserPhoneManage : WidgetUserAccountVerifyBase(R.layout.widget_user_phone_manage) {

    private lateinit var title: TextView
    private lateinit var descriptionNote: TextView
    private lateinit var phoneInput: PhoneOrEmailInputView
    private lateinit var nextButton: MaterialButton
    private lateinit var removePhoneRow: TextView
    private lateinit var dimmer: DimmerView

    /** Where the analytics on the resulting phone-add event says this flow started. */
    private lateinit var source: String

    /**
     * Verifying the password is the last step of a removal, so a success here means the whole
     * screen is done.
     */
    private val passwordVerifyLauncher: ActivityResultLauncher<Intent> =
        WidgetUserPasswordVerify.registerForResult(this) {
            activity?.setResult(android.app.Activity.RESULT_OK)
            activity?.finish()
        }

    /**
     * A completed verification only closes this screen when someone is waiting on its result;
     * entered from settings there is nobody to return to, so the refreshed screen is left visible.
     */
    private val phoneVerificationLauncher: ActivityResultLauncher<Intent> =
        WidgetUserPhoneVerify.registerForResult(this) {
            if (requireActivity().callingActivity != null) {
                activity?.setResult(android.app.Activity.RESULT_OK)
                activity?.finish()
            }
        }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        title = view.findViewById(R.id.user_phone_title)
        descriptionNote = view.findViewById(R.id.user_phone_add_description_note)
        phoneInput = view.findViewById(R.id.user_phone_add_number)
        nextButton = view.findViewById(R.id.user_phone_add_next)
        removePhoneRow = view.findViewById(R.id.remove_phone)
        dimmer = view.findViewById(R.id.dimmer_view)

        // A forced screen has no way back, so it shows no up arrow; otherwise the arrow is a close
        // cross rather than a back chevron, because this is a modal task, not a step in a stack.
        setActionBarDisplayHomeAsUpEnabled(
            showHomeAsUp = !isForced,
            iconRes = view.getThemedDrawableRes(R.attr.ic_action_bar_close),
            iconAccessibilityLabel = if (!isForced) R.string.close else null,
        )

        source = mostRecentIntent.getStringExtra(INTENT_SOURCE) ?: Source.DEFAULT.source

        // Named, because a trailing lambda would bind to `onCountryCodeClick` and silently replace
        // the country picker instead of watching the text.
        phoneInput.bind(this, onAfterTextChanged = { handlePhoneNumberTextChanged() })
        handlePhoneNumberTextChanged()

        StoreStream.getPhone()
            .observeCountryCode()
            .ui(this)
            .appSubscribe(WidgetUserPhoneManage::class.java) { phoneInput.setCountryCode(it) }

        nextButton.setOnClickListener { updatePhoneNumber() }
        removePhoneRow.setOnClickListener { removePhoneNumber() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUsers()
            .observeMe()
            .ui(this)
            .appSubscribe(WidgetUserPhoneManage::class.java) { configureUI(it) }
    }

    private fun configureUI(meUser: MeUser) {
        val existingPhone = meUser.phoneNumber
        if (existingPhone != null) {
            title.setText(R.string.phone_verification_update_title)
            descriptionNote.text = descriptionNote.i18nFormat(
                R.string.phone_verification_current_phone,
                "phone" to existingPhone,
            )
            removePhoneRow.visibility = View.VISIBLE
        } else {
            title.setText(R.string.enter_phone_title)
            descriptionNote.setText(R.string.enter_phone_description)
            removePhoneRow.visibility = View.GONE
        }

        // With no e-mail on file the phone number is the only recovery factor left; removing it
        // would lock the account, so the option is not offered at all.
        if (meUser.email == null) {
            removePhoneRow.visibility = View.GONE
        }
    }

    /**
     * The CTA only lights up for a number that carries a country code. `getTextOrEmpty` returns the
     * country code concatenated with the typed digits, so a leading `+` is present exactly when a
     * country has been resolved.
     */
    private fun handlePhoneNumberTextChanged() {
        val text = phoneInput.getTextOrEmpty()
        nextButton.isEnabled = text.isNotEmpty() && text.startsWith("+")
    }

    private fun removePhoneNumber() {
        if (!requireMeUser().isMfaSMSEnabled) {
            WidgetUserPasswordVerify.launchRemovePhoneNumber(requireContext(), passwordVerifyLauncher)
            return
        }
        // Removing the number silently turns off SMS two-factor, which is a security downgrade the
        // user has to be told about before it happens.
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = getString(R.string.user_settings_account_remove_phone_number_warning_title),
            bodyText = getString(R.string.user_settings_account_remove_phone_number_warning_body),
            okText = getString(R.string.remove),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf<Int, (View) -> Unit>(
                R.id.notice_ok to {
                    WidgetUserPasswordVerify.launchRemovePhoneNumber(requireContext(), passwordVerifyLauncher)
                },
            ),
            dialogTheme = R.attr.notice_theme_positive_red,
        )
    }

    private fun requireMeUser(): MeUser = StoreStream.getUsers().getMe()

    /**
     * Starts the verification for the typed number. On success the code screen opens; a captcha
     * demand is retried once with the solved token.
     */
    private fun updatePhoneNumber(captchaPayload: CaptchaHelper.CaptchaPayload? = null) {
        val phoneNumber = phoneInput.getTextOrEmpty()
        RestAPI.api
            .userAddPhone(
                RestAPIParams.Phone(
                    phone = phoneNumber,
                    source = source,
                    captchaKey = captchaPayload?.captchaKey,
                    captchaRqtoken = captchaPayload?.captchaRqtoken,
                ),
            )
            .restSubscribeOn()
            .withDimmer(dimmer)
            .ui(this)
            .appSubscribe(
                errorClass = WidgetUserPhoneManage::class.java,
                context = context,
                errorHandler = { error -> maybeLaunchCaptcha(error) },
            ) {
                WidgetUserPhoneVerify.launch(
                    requireContext(),
                    phoneVerificationLauncher,
                    mode,
                    phoneNumber,
                    source,
                )
            }
    }

    /**
     * Reference: `GrowthTeamFeatures.isPhoneVerifyCaptchaEnabled()` plus
     * `WidgetCaptcha.processErrorsForCaptcha`. Neither `utilities/features` nor `WidgetCaptcha`
     * exists in this reconstruction, so the flag is read from the single existing seam
     * (`ContactSyncDependencies`, which stands in for the same experiment) and the challenge is
     * shown by [CaptchaHelper] directly — the same substitution `WidgetContactSync` already makes.
     * While the flag is unset the API error simply surfaces, which is visible rather than silent.
     */
    private fun maybeLaunchCaptcha(error: RestApiError) {
        if (!ContactSyncDependencies.isPhoneVerifyCaptchaEnabled() || !error.isCaptchaRequired) return
        val siteKey = error.captchaSitekey ?: return
        CaptchaHelper.tryShowCaptcha(CaptchaHelper.CaptchaRequest.HCaptcha(siteKey, requireActivity()))
            .ui(this)
            .appSubscribe(WidgetUserPhoneManage::class.java) { token ->
                updatePhoneNumber(CaptchaHelper.CaptchaPayload(token, error.captchaRqtoken))
            }
    }

    /**
     * Where the flow started, as the analytics pipeline names it. The wire values are part of the
     * API contract, so they are spelled out rather than derived from the constant name — note
     * [DEFAULT] sends `update_phone`, not `default`.
     *
     * Ordinals verified against the enum's `<clinit>` in the DEX.
     */
    enum class Source(val source: String) {
        USER_ACTION_REQUIRED("user_action_required"),
        USER_SETTINGS_UPDATE("user_settings_update"),
        GUILD_PHONE_REQUIRED("guild_phone_required"),
        MFA_PHONE_UPDATE("mfa_phone_update"),
        CONTACT_SYNC("contact_sync"),
        DEFAULT("update_phone"),
    }

    companion object {
        private const val INTENT_SOURCE = "intent_args_key"

        @JvmStatic
        fun launch(context: Context, mode: Mode, source: Source) {
            val intent = WidgetUserAccountVerifyBase
                .getLaunchIntent(mode, phoneAllowed = true, emailAllowed = false)
                .putExtra(INTENT_SOURCE, source.source)
            // Reached from settings this screen is a detour, so it is kept out of the back stack.
            if (mode == Mode.NO_HISTORY_FROM_USER_SETTINGS) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            AppScreenRouter.launch(context, WidgetUserPhoneManage::class.java, intent)
        }
    }
}
