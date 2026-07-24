package com.discord.widgets.auth

import androidx.lifecycle.ViewModel
import java.io.Serializable
import rx.subjects.BehaviorSubject

enum class AuthRoute : Serializable {
    LANDING,
    LOGIN,
    REGISTER_IDENTITY,
    REGISTER_ACCOUNT,
    BIRTHDAY,
    MFA,
    PHONE_VERIFICATION,
    EMAIL_VERIFICATION,
    REQUIRED_ACTION,
    AGE_VERIFY,
    AGE_GATED,
    FORCE_PASSWORD,
    RESET_PASSWORD,
    UNDELETE_ACCOUNT,
}

enum class AuthIdentityMode : Serializable { EMAIL, PHONE }
enum class AuthAgeStep : Serializable { VERIFY, CONFIRM, UNDERAGE }
enum class AuthRequiredAction : Serializable { VERIFY_EMAIL, VERIFY_PHONE, UPDATE_PASSWORD }

sealed class AuthLandingContent : Serializable {
    data class GuildTemplate(val name: String) : AuthLandingContent()
    data class GuildInvite(val id: Long, val name: String, val icon: String?, val inviter: String?, val memberCount: Int) : AuthLandingContent()
    data class GroupInvite(val name: String?, val inviterId: Long?, val inviter: String?, val inviterAvatar: String?) : AuthLandingContent()
    data class DirectInvite(val inviterId: Long, val inviter: String, val discriminator: String, val inviterAvatar: String?) : AuthLandingContent()
}

data class AuthUiState(
    val route: AuthRoute = AuthRoute.LANDING,
    val loading: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val identifier: String = "",
    val username: String = "",
    val email: String? = null,
    val phone: String? = null,
    val phoneToken: String? = null,
    val ticket: String? = null,
    val resetToken: String? = null,
    val birthday: Long? = null,
    val identityMode: AuthIdentityMode = AuthIdentityMode.EMAIL,
    val consentRequired: Boolean = false,
    val consented: Boolean = false,
    val requiredAction: AuthRequiredAction? = null,
    val allowEmail: Boolean = true,
    val allowPhone: Boolean = true,
    val ageStep: AuthAgeStep = AuthAgeStep.VERIFY,
    val underageMessage: String? = null,
    val disabledAccount: Boolean = false,
    val landingContent: AuthLandingContent? = null,
) : Serializable

sealed class AuthAction {
    class Login(val identifier: String, val password: String) : AuthAction() {
        override fun toString() = "Login(identifier=<redacted>, password=<redacted>)"
    }

    class Register(
        val email: String?,
        val phoneToken: String?,
        val username: String,
        val password: String,
        val birthday: Long?,
        val consented: Boolean,
    ) : AuthAction() {
        override fun toString() = "Register(credentials=<redacted>, birthday=$birthday, consented=$consented)"
    }

    class SendPhoneCode(val phone: String) : AuthAction() {
        override fun toString() = "SendPhoneCode(phone=<redacted>)"
    }
    class VerifyPhone(val phone: String, val code: String) : AuthAction() {
        override fun toString() = "VerifyPhone(phone=<redacted>, code=<redacted>)"
    }
    class ResendPhoneCode(val phone: String) : AuthAction() {
        override fun toString() = "ResendPhoneCode(phone=<redacted>)"
    }
    class VerifyMfa(val ticket: String, val code: String) : AuthAction() {
        override fun toString() = "VerifyMfa(ticket=<redacted>, code=<redacted>)"
    }
    class ResendEmail(val email: String?) : AuthAction() {
        override fun toString() = "ResendEmail(email=<redacted>)"
    }
    data class VerifyAge(val birthday: Long) : AuthAction()
    class ForgotPassword(val identifier: String) : AuthAction() {
        override fun toString() = "ForgotPassword(identifier=<redacted>)"
    }
    class ResetPassword(val token: String) : AuthAction() {
        override fun toString() = "ResetPassword(token=<redacted>)"
    }
    data class UndeleteAccount(val restore: Boolean) : AuthAction()
    class ChangePassword(val currentPassword: String, val newPassword: String, val mfaCode: String?) : AuthAction() {
        override fun toString() = "ChangePassword(credentials=<redacted>)"
    }
}

sealed class AuthResult {
    data class Success(val nextRoute: AuthRoute? = null, val message: String? = null) : AuthResult()
    data class Failure(val fieldErrors: Map<String, String> = emptyMap(), val message: String? = null) : AuthResult()
    data class Challenge(val route: AuthRoute, val state: AuthUiState) : AuthResult()
}

interface AuthUiActions {
    fun navigate(route: AuthRoute, state: AuthUiState = AuthUiState(route = route))
    fun submit(action: AuthAction, callback: (AuthResult) -> Unit)
    fun close()
    fun openPasswordManager() = Unit
    fun openSupport() = Unit
}

class AuthUiViewModel : ViewModel() {
    private val states = BehaviorSubject.create(AuthUiState())
    val state: AuthUiState get() = states.value ?: AuthUiState()

    fun observe() = states.distinctUntilChanged()

    fun update(transform: (AuthUiState) -> AuthUiState) {
        states.onNext(transform(state))
    }

    fun restore(state: AuthUiState) = states.onNext(state)
}

object AuthUiRules {
    fun login(identifier: String, password: String): Map<String, String> = buildMap {
        if (identifier.isBlank()) put("identifier", "Email or phone number is required.")
        if (password.isBlank()) put("password", "Password is required.")
    }

    fun identity(mode: AuthIdentityMode, value: String): Map<String, String> = buildMap {
        val valid = when (mode) {
            AuthIdentityMode.EMAIL -> value.trim().let { email ->
                email.count { it == '@' } == 1 && email.substringAfter('@').contains('.') && !email.any(Char::isWhitespace)
            }
            AuthIdentityMode.PHONE -> value.count(Char::isDigit) >= 7
        }
        if (!valid) put("identifier", if (mode == AuthIdentityMode.EMAIL) "Enter a valid email." else "Enter a valid phone number.")
    }

    fun account(username: String, password: String, consentRequired: Boolean, consented: Boolean) = buildMap {
        if (username.isBlank()) put("username", "Username is required.")
        if (password.length < 6) put("password", "Password must be at least 6 characters.")
        if (consentRequired && !consented) put("consent", "You must agree before continuing.")
    }

    fun code(code: String, minimum: Int = 6) =
        if (code.length >= minimum) emptyMap() else mapOf("code" to "Enter the complete verification code.")

    fun routeFor(action: AuthRequiredAction) = when (action) {
        AuthRequiredAction.VERIFY_EMAIL -> AuthRoute.EMAIL_VERIFICATION
        AuthRequiredAction.VERIFY_PHONE -> AuthRoute.PHONE_VERIFICATION
        AuthRequiredAction.UPDATE_PASSWORD -> AuthRoute.FORCE_PASSWORD
    }
}
