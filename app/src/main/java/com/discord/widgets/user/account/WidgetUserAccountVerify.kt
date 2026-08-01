package com.discord.widgets.user.account

import android.content.Context
import android.view.View
import com.discord.R
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetUserAccountVerifyBinding
import com.discord.i18n.setI18nText
import com.discord.models.requiredaction.RequiredAction
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase.Mode
import com.discord.widgets.user.email.WidgetUserEmailVerify
import com.discord.widgets.user.phone.WidgetUserPhoneManage
import java.util.Locale

/**
 * The lock-out screen shown when the server reports `required_action`: "we need you to verify this
 * account", with a row per factor the account may be verified with.
 *
 * Only the factors the triggering action actually accepts are offered — [isEmailAllowed] and
 * [isPhoneAllowed] come from the launch intent, so a phone-only requirement does not show an
 * e-mail row the user could waste time on.
 *
 * Back is swallowed: this is [Mode.FORCED], and the only ways off it are verifying or the logout
 * menu item the base class installs.
 */
class WidgetUserAccountVerify : WidgetUserAccountVerifyBase(R.layout.widget_user_account_verify) {
    private var _binding: WidgetUserAccountVerifyBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetUserAccountVerifyBinding.bind(view)
        setOnBackPressed({ true })

        binding.verifyAccountTextBody.setI18nText(R.string.verification_body)

        binding.verifyAccountSupport
            .setI18nText(R.string.verification_footer_support, "supportURL" to supportRequestUrl())

        binding.verifyAccountByEmail.apply {
            visibility = if (isEmailAllowed) View.VISIBLE else View.GONE
            setOnClickListener { WidgetUserEmailVerify.launch(it.context, mode) }
        }

        binding.verifyAccountByPhone.apply {
            visibility = if (isPhoneAllowed) View.VISIBLE else View.GONE
            setOnClickListener {
                WidgetUserPhoneManage.launch(
                    it.context,
                    mode,
                    WidgetUserPhoneManage.Source.USER_ACTION_REQUIRED,
                )
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Reference `b.a.d.f.c()`: the support centre's "new request" form in the user's locale. */
    private fun supportRequestUrl(): String {
        val locale = StoreStream.getUserSettingsSystem().getLocale().lowercase(Locale.ROOT)
        return "$SUPPORT_HOST/hc/$locale/requests/new"
    }

    companion object {
        private const val SUPPORT_HOST = "https://support.discord.com"

        /**
         * Opens the gate for [action].
         *
         * A phone number is accepted for any of the three actions that can be cleared by proving
         * ownership; an e-mail only clears the e-mail requirement. That asymmetry is the reference's
         * and it is why the two flags are computed separately rather than from one `when`.
         */
        @JvmStatic
        fun launch(context: Context, action: RequiredAction) {
            val phoneAllowed = action == RequiredAction.REQUIRE_VERIFIED_PHONE ||
                action == RequiredAction.REQUIRE_VERIFIED_EMAIL ||
                action == RequiredAction.REQUIRE_CAPTCHA
            val emailAllowed = action == RequiredAction.REQUIRE_VERIFIED_EMAIL
            val intent = WidgetUserAccountVerifyBase.getLaunchIntent(Mode.FORCED, phoneAllowed, emailAllowed)
            AnalyticsTracker.openModal("Suspicious Activity", "")
            AppScreenRouter.launch(context, WidgetUserAccountVerify::class.java, intent)
        }
    }
}
