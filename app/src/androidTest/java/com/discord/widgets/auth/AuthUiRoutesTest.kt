package com.discord.widgets.auth

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthUiRoutesTest {
    @Test
    fun everyAuthStateRouteInflatesItsRecoveredLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            val inflater = LayoutInflater.from(context)
            routes.forEach { (route, layout, requiredView) ->
                val root = inflater.inflate(layout, null, false)
                assertNotNull("$route failed to inflate", root)
                assertNotNull("$route omitted its actionable state view", root.findViewById(requiredView))
            }
        }
    }

    private val routes = listOf(
        Triple(AuthRoute.LANDING, R.layout.widget_auth_landing, R.id.auth_landing_login),
        Triple(AuthRoute.LOGIN, R.layout.widget_auth_login, R.id.auth_login),
        Triple(AuthRoute.REGISTER_IDENTITY, R.layout.widget_auth_register_identity, R.id.auth_register_identity_button),
        Triple(AuthRoute.REGISTER_ACCOUNT, R.layout.widget_auth_register_account_information, R.id.auth_register_account_information_button),
        Triple(AuthRoute.BIRTHDAY, R.layout.widget_auth_birthday, R.id.auth_register_button),
        Triple(AuthRoute.MFA, R.layout.widget_auth_mfa, R.id.auth_mfa_verification),
        Triple(AuthRoute.PHONE_VERIFICATION, R.layout.widget_auth_phone_verify, R.id.auth_phone_verify_code),
        Triple(AuthRoute.EMAIL_VERIFICATION, R.layout.widget_user_email_verify, R.id.alert_verify_email_resend),
        Triple(AuthRoute.REQUIRED_ACTION, R.layout.widget_user_account_verify, R.id.verify_account_by_phone),
        Triple(AuthRoute.AGE_VERIFY, R.layout.widget_age_verify, R.id.age_verify_view_flipper),
        Triple(AuthRoute.AGE_GATED, R.layout.widget_auth_age_gated, R.id.auth_register_underage_button),
        Triple(AuthRoute.FORCE_PASSWORD, R.layout.widget_settings_account_change_password, R.id.change_password_save),
        Triple(AuthRoute.RESET_PASSWORD, R.layout.widget_auth_reset_password, R.id.auth_reset_password_submit),
        Triple(AuthRoute.UNDELETE_ACCOUNT, R.layout.widget_auth_undelete_account, R.id.auth_undelete_cancel),
    )
}
