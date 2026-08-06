package com.discord.widgets.user.email

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import com.discord.R
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.i18n.i18nFormat
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase.Mode
import com.google.android.material.button.MaterialButton
import rx.Observable
import com.discord.i18n.i18nRender

/**
 * "Check your inbox" — the waiting room for an unverified e-mail address, with the two escapes the
 * user actually has: resend the message, or change the address.
 *
 * The screen is driven entirely by the account record, so it needs no manual refresh: as soon as the
 * gateway reports the address verified it finishes with `RESULT_OK`, and if the account somehow has
 * no address at all it forwards straight to [WidgetUserEmailUpdate] rather than offering to resend
 * a message to nowhere.
 */
class WidgetUserEmailVerify : WidgetUserAccountVerifyBase(R.layout.widget_user_email_verify) {

    private lateinit var changeButton: MaterialButton
    private lateinit var resendButton: MaterialButton

    /** Changing the address makes this screen obsolete, so it closes with its parent's result. */
    private val emailUpdateLauncher: ActivityResultLauncher<Intent> =
        WidgetUserEmailUpdate.registerForResult(this) { appActivity?.finish() }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        changeButton = view.findViewById(R.id.alert_verify_email_change)
        resendButton = view.findViewById(R.id.alert_verify_email_resend)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        observeModel()
            .ui(this)
            .appSubscribe(WidgetUserEmailVerify::class.java) { model -> configureUI(model) }
    }

    private fun configureUI(model: Model) {
        val email = model.email
        when {
            model.verified -> {
                requireActivity().setResult(android.app.Activity.RESULT_OK)
                requireActivity().finish()
            }

            // Nothing to resend to, and nothing to show; the only useful screen is the one that
            // sets an address.
            email == null ->
                WidgetUserEmailUpdate.launch(requireContext(), mode, emailUpdateLauncher)

            else -> {
                changeButton.setOnClickListener {
                    WidgetUserEmailUpdate.launch(it.context, mode)
                }
                resendButton.setOnClickListener { resendVerificationEmail(email) }
            }
        }
    }

    private fun resendVerificationEmail(email: String) {
        RestAPI.api
            .postAuthVerifyResend(RestAPIParams.EmptyBody())
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetUserEmailVerify::class.java, context) {
                AppToast.show(
                    this,
                    requireContext().i18nRender(R.string.verification_email_body, "email" to email),
                )
            }
    }

    /**
     * The two facts this screen renders, projected off the account record.
     *
     * [email] and [verified] are body-level properties, not constructor parameters, so equality —
     * and therefore the `distinctUntilChanged` above — is on the whole account record, exactly as
     * the reference does it. The generated `toString` contains only [me], which already redacts the
     * token, e-mail and phone number.
     */
    private data class Model(val me: MeUser) {
        val email: String? = me.email
        val verified: Boolean = me.isVerified
    }

    private fun observeModel(): Observable<Model> = StoreStream.getUsers()
        .observeMe()
        .map { me -> Model(me) }
        .distinctUntilChanged()

    companion object {
        @JvmStatic
        fun launch(context: Context, mode: Mode) {
            AppScreenRouter.launch(
                context,
                WidgetUserEmailVerify::class.java,
                WidgetUserAccountVerifyBase.getLaunchIntent(mode, phoneAllowed = false, emailAllowed = true),
            )
        }
    }
}
