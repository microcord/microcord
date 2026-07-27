package com.discord.widgets.user.phone

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.analytics.generated.events.impression.TrackImpressionUserVerifyPhone
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.LoggingConfig
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.views.CodeVerificationView
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.user.WidgetUserPasswordVerify
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase.Mode
import rx.Observable

/**
 * Enters the six-digit code texted to the number [WidgetUserPhoneManage] submitted.
 *
 * A correct code does not by itself change the account: it buys a short-lived *phone token*, which
 * is then spent — together with the password — by [WidgetUserPasswordVerify]. That two-step shape is
 * why this screen navigates onward on success rather than finishing.
 *
 * Where it goes after the password step depends on how it was entered: back to whoever launched it
 * for a result, or to the app for a voluntary change. A forced screen stays put, because the
 * requirement that put the user here is what decides when they may leave.
 *
 * ### Secrets
 * The verification code and the resulting phone token are both single-use credentials. Neither is
 * stored in a property or in saved state: the code arrives as a callback argument and goes straight
 * into the request, and the token is passed on to the password screen through an intent extra and
 * never held here. `RestAPIParams.VerificationCode` and `ModelPhoneVerificationToken` both redact
 * themselves in `toString`, so neither can reach a log through an error path.
 */
class WidgetUserPhoneVerify : WidgetUserAccountVerifyBase(R.layout.widget_user_phone_verify) {

    private lateinit var codeVerificationView: CodeVerificationView
    private lateinit var closeButton: ImageView
    private lateinit var dimmer: DimmerView

    /**
     * One impression per screen instance (`autoLogImpressionOnChanged = false`), carrying only the
     * schema name — the number and the code are never attached to it.
     */
    override val loggingConfig = LoggingConfig(
        autoLogImpressionOnChanged = false,
        impressionSchemaProvider = { TrackImpressionUserVerifyPhone() },
    )

    /**
     * The password step is the real commit, so its success is this flow's success.
     *
     * Three destinations, in the reference's order: a caller waiting on a result gets it; a change
     * started from account settings continues to the SMS-backup follow-up; anything else voluntary
     * returns to the app.
     */
    private val passwordVerifyLauncher: ActivityResultLauncher<Intent> =
        WidgetUserPasswordVerify.registerForResult(this) {
            when {
                requireActivity().callingActivity != null -> {
                    requireActivity().setResult(android.app.Activity.RESULT_OK)
                    requireActivity().finish()
                }
                // Reference: WidgetSettingsAccount.launch(context, false, Redirect.SMS_BACKUP).
                // That screen does not exist in this reconstruction, so the flow returns to the app
                // instead of offering SMS backup codes. Recorded in the handoff report.
                mode == Mode.NO_HISTORY_FROM_USER_SETTINGS -> transitionToApp()
                !isForced -> transitionToApp()
                else -> Unit
            }
        }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        codeVerificationView = view.findViewById(R.id.phone_digit_verification_view)
        closeButton = view.findViewById(R.id.close_button)
        dimmer = view.findViewById(R.id.dimmer_view)

        codeVerificationView.onCodeEntered = ::handleCodeEntered

        closeButton.visibility = if (isForced) View.GONE else View.VISIBLE
        closeButton.setOnClickListener {
            @Suppress("DEPRECATION")
            activity?.onBackPressed()
        }

        // Reference: GrowthTeamFeatures.isAndroidSmsAutofillEnabled() gates
        // SmsListener.startSmsListener(::autofillCode), which fills the field from the incoming SMS
        // via Play Services' SMS Retriever. Neither `utilities/sms` nor that dependency exists here,
        // so the code is typed. [autofillCode] is kept so wiring a listener is a one-line change.
    }

    /**
     * Fills the field from a received message and submits it a moment later, so the user sees the
     * digits land instead of the screen jumping straight to the next step.
     *
     * Unused until an SMS retriever is wired up; see [onViewBound].
     */
    @Suppress("unused")
    private fun autofillCode(verificationCode: String) {
        if (view == null) return
        // Suppressed while the code is being written in, or setCode would submit it a second time.
        codeVerificationView.onCodeEntered = {}
        codeVerificationView.setCode(verificationCode)
        codeVerificationView.onCodeEntered = ::handleCodeEntered
        Observable.timer(AUTOFILL_SUBMIT_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .ui(this)
            .appSubscribe(WidgetUserPhoneVerify::class.java) { handleCodeEntered(verificationCode) }
    }

    private fun handleCodeEntered(verificationCode: String) {
        val phoneNumber = mostRecentIntent.getStringExtra(INTENT_EXTRA_PHONE_NUMBER) ?: return
        RestAPI.api
            .phoneVerificationsVerify(RestAPIParams.VerificationCode(phoneNumber, verificationCode))
            .restSubscribeOn()
            .withDimmer(dimmer)
            .ui(this)
            .appSubscribe(
                errorClass = WidgetUserPhoneVerify::class.java,
                context = context,
                // A rejected code leaves the field cleared and focused so it can be retyped.
                errorHandler = { codeVerificationView.clearCode() },
            ) { response ->
                val token = response.body()?.token ?: return@appSubscribe
                handleCodeReceived(token)
            }
    }

    private fun handleCodeReceived(phoneToken: String) {
        val source = mostRecentIntent.getStringExtra(INTENT_EXTRA_SOURCE_TYPE) ?: return
        WidgetUserPasswordVerify.launchUpdatePhoneNumber(
            requireContext(),
            passwordVerifyLauncher,
            phoneToken,
            source,
        )
    }

    private fun transitionToApp() {
        StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
        AppScreenRouter.launch(requireContext(), WidgetTabsHost::class.java)
    }

    companion object {
        private const val INTENT_EXTRA_PHONE_NUMBER = "INTENT_EXTRA_PHONE_NUMBER"
        private const val INTENT_EXTRA_SOURCE_TYPE = "INTENT_EXTRA_SOURCE_TYPE"
        private const val AUTOFILL_SUBMIT_DELAY_MS = 500L

        @JvmStatic
        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            mode: Mode,
            phoneNumber: String?,
            source: String,
        ) {
            val intent = WidgetUserAccountVerifyBase
                .getLaunchIntent(mode, phoneAllowed = true, emailAllowed = false)
                .putExtra(INTENT_EXTRA_PHONE_NUMBER, phoneNumber)
                .putExtra(INTENT_EXTRA_SOURCE_TYPE, source)
            if (mode == Mode.NO_HISTORY_FROM_USER_SETTINGS) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            AppScreenRouter.launchForResult(context, launcher, WidgetUserPhoneVerify::class.java, intent)
        }

        /** Registers a launcher that runs [callback] only when the number was actually verified. */
        @JvmStatic
        fun registerForResult(fragment: AppFragment, callback: () -> Unit): ActivityResultLauncher<Intent> =
            fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == android.app.Activity.RESULT_OK) callback()
            }
    }
}
