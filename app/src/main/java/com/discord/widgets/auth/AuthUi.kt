package com.discord.widgets.auth

import androidx.lifecycle.ViewModel
import com.discord.R
import com.discord.api.auth.mfa.MfaChallenge
import com.discord.api.auth.mfa.MfaMethodType
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
enum class AuthPhonePurpose : Serializable { REGISTRATION, LOGIN, PASSWORD_RESET }
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
    /** Failure text that belongs to no single input; [message] is its success counterpart. */
    val error: String? = null,
    val message: String? = null,
    val identifier: String = "",
    val username: String = "",
    val globalName: String = "",
    val email: String? = null,
    val phone: String? = null,
    val phonePurpose: AuthPhonePurpose = AuthPhonePurpose.REGISTRATION,
    private val credentialHandle: String? = null,
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
) : Serializable {
    internal val phoneToken: String? get() = AuthCredentialMemory[credentialHandle]?.phoneToken
    internal val ticket: String? get() = AuthCredentialMemory[credentialHandle]?.ticket
    internal val resetToken: String? get() = AuthCredentialMemory[credentialHandle]?.resetToken
    internal val mfaChallenge: MfaChallenge? get() = AuthCredentialMemory[credentialHandle]?.mfaChallenge
    internal val loginInstanceId: String? get() = AuthCredentialMemory[credentialHandle]?.loginInstanceId

    /** Keeps bearer credentials outside this serializable state and every Bundle containing it. */
    internal fun withCredentials(
        phoneToken: String? = this.phoneToken,
        ticket: String? = this.ticket,
        resetToken: String? = this.resetToken,
        mfaChallenge: MfaChallenge? = this.mfaChallenge,
        loginInstanceId: String? = this.loginInstanceId,
    ): AuthUiState {
        AuthCredentialMemory.remove(credentialHandle)
        return copy(
            credentialHandle = AuthCredentialMemory.store(
                phoneToken, ticket, resetToken, mfaChallenge, loginInstanceId,
            ),
        )
    }

    internal fun clearCredentials(): AuthUiState {
        AuthCredentialMemory.remove(credentialHandle)
        return copy(credentialHandle = null)
    }

    /**
     * Redacted rendering.
     *
     * [ticket], [phoneToken] and [resetToken] resolve through process memory and are not fields of
     * this serializable object. The custom rendering also omits the non-secret lookup handle.
     *
     * No password, MFA code or backup code is ever stored on this state; those are read straight
     * off their password-style inputs at submit time and are never held here.
     */
    override fun toString(): String = "AuthUiState(route=$route, loading=$loading, " +
        "fieldErrorKeys=${fieldErrors.keys}, hasError=${error != null}, " +
        "identifier=<redacted>, username=$username, globalName=$globalName, " +
        "email=<redacted>, phone=<redacted>, phonePurpose=$phonePurpose, phoneToken=<redacted>, ticket=<redacted>, " +
        "resetToken=<redacted>, birthday=$birthday, identityMode=$identityMode, " +
        "consentRequired=$consentRequired, consented=$consented, requiredAction=$requiredAction, " +
        "allowEmail=$allowEmail, allowPhone=$allowPhone, ageStep=$ageStep, " +
        "disabledAccount=$disabledAccount, landingContent=$landingContent)"
}

private object AuthCredentialMemory {
    class Credentials(
        val phoneToken: String?,
        val ticket: String?,
        val resetToken: String?,
        val mfaChallenge: MfaChallenge?,
        val loginInstanceId: String?,
    )

    private val credentials = ConcurrentHashMap<String, Credentials>()

    operator fun get(handle: String?): Credentials? = handle?.let(credentials::get)

    fun store(
        phoneToken: String?,
        ticket: String?,
        resetToken: String?,
        mfaChallenge: MfaChallenge?,
        loginInstanceId: String?,
    ): String? {
        if (phoneToken == null && ticket == null && resetToken == null && mfaChallenge == null) return null
        return UUID.randomUUID().toString().also {
            credentials[it] = Credentials(phoneToken, ticket, resetToken, mfaChallenge, loginInstanceId)
        }
    }

    fun remove(handle: String?) {
        handle?.let(credentials::remove)
    }
}

sealed class AuthAction {
    /** True when the flow continues on the same screen and will reuse the same ticket or token. */
    open val keepsCredentials: Boolean get() = false

    class Login(val identifier: String, val password: String) : AuthAction() {
        override fun toString() = "Login(identifier=<redacted>, password=<redacted>)"
    }

    class TokenLogin(val token: String) : AuthAction() {
        override fun toString() = "TokenLogin(token=<redacted>)"
    }

    class Register(
        val email: String?,
        val phoneToken: String?,
        val username: String,
        val password: String,
        val birthday: Long?,
        val consented: Boolean,
        val globalName: String? = null,
        val promotionalEmailOptIn: Boolean? = null,
    ) : AuthAction() {
        override fun toString() = "Register(credentials=<redacted>, birthday=$birthday, consented=$consented)"
    }

    class SendPhoneCode(val phone: String) : AuthAction() {
        override fun toString() = "SendPhoneCode(phone=<redacted>)"
    }
    class VerifyPhone(
        val phone: String,
        val code: String,
        val purpose: AuthPhonePurpose,
    ) : AuthAction() {
        override fun toString() = "VerifyPhone(phone=<redacted>, code=<redacted>, purpose=$purpose)"
    }
    class ResendPhoneCode(val phone: String) : AuthAction() {
        override fun toString() = "ResendPhoneCode(phone=<redacted>)"
    }
    class VerifyMfa(
        val ticket: String,
        val code: String,
        val authenticatorType: MfaMethodType,
        val loginInstanceId: String? = null,
    ) : AuthAction() {
        override fun toString() = "VerifyMfa(ticket=<redacted>, code=<redacted>, authenticatorType=$authenticatorType)"
    }

    class SendMfaSms(val ticket: String) : AuthAction() {
        override val keepsCredentials get() = true
        override fun toString() = "SendMfaSms(ticket=<redacted>)"
    }

    class OneTimeLogin(val ticket: String) : AuthAction() {
        override fun toString() = "OneTimeLogin(ticket=<redacted>)"
    }
    class ResendEmail(val email: String?) : AuthAction() {
        override fun toString() = "ResendEmail(email=<redacted>)"
    }
    data class VerifyAge(val birthday: Long) : AuthAction()
    class ForgotPassword(val identifier: String) : AuthAction() {
        override fun toString() = "ForgotPassword(identifier=<redacted>)"
    }
    data class UndeleteAccount(val restore: Boolean) : AuthAction()
    class ChangePassword(val currentPassword: String, val newPassword: String) : AuthAction() {
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

    /** Opens the platform credential/password manager so the user can look up their password. */
    fun openPasswordManager()

    /** Opens Discord support, for an account the user cannot recover in-app. */
    fun openSupport()
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

/**
 * Client-side form validation.
 *
 * Failures are string resource ids, not text: these run without a `Context` (including from
 * [AuthRequests], which has none) and the screen resolves them when it renders.
 */
object AuthUiRules {
    fun login(identifier: String, password: String): Map<String, Int> = buildMap {
        if (identifier.isBlank()) put("identifier", R.string.login_required)
        if (password.isBlank()) put("password", R.string.password_required)
    }

    fun token(token: String): Map<String, Int> =
        if (token.isBlank()) mapOf("token" to R.string.manticord_token_required) else emptyMap()

    fun identity(mode: AuthIdentityMode, value: String): Map<String, Int> = buildMap {
        val valid = when (mode) {
            AuthIdentityMode.EMAIL -> value.trim().let { email ->
                email.count { it == '@' } == 1 && email.substringAfter('@').contains('.') && !email.any(Char::isWhitespace)
            }
            AuthIdentityMode.PHONE -> value.count(Char::isDigit) >= 7
        }
        if (!valid) {
            put("identifier", if (mode == AuthIdentityMode.EMAIL) R.string.email_invalid else R.string.phone_invalid)
        }
    }

    fun account(username: String, password: String, consentRequired: Boolean, consented: Boolean): Map<String, Int> = buildMap {
        if (username.isBlank()) put("username", R.string.username_required)
        if (password.length < MINIMUM_PASSWORD_LENGTH) put("password", R.string.password_length_error)
        if (consentRequired && !consented) put("consent", R.string.consent_required)
    }

    fun code(code: String, minimum: Int = 6): Map<String, Int> =
        if (code.length >= minimum) emptyMap() else mapOf("code" to R.string.two_fa_token_required)

    const val MINIMUM_PASSWORD_LENGTH = 6

    fun routeFor(action: AuthRequiredAction) = when (action) {
        AuthRequiredAction.VERIFY_EMAIL -> AuthRoute.EMAIL_VERIFICATION
        AuthRequiredAction.VERIFY_PHONE -> AuthRoute.PHONE_VERIFICATION
        AuthRequiredAction.UPDATE_PASSWORD -> AuthRoute.FORCE_PASSWORD
    }
}
