package com.discord.widgets.auth

import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaErrorBody
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import retrofit2.HttpException
import retrofit2.Response
import rx.Observable

/**
 * Every authentication request the auth screens can make, in one place.
 *
 * The screens themselves are deliberately dumb: they collect input, hand an [AuthAction] here, and
 * render whatever [AuthResult] comes back. This object owns the REST/store half — which endpoint,
 * which error codes mean "go to another screen" rather than "show a field error", and how a captcha
 * challenge is surfaced.
 *
 * The reference spreads this across the individual `Widget*` classes, each calling `RestAPI` or
 * `StoreStream.getAuthentication()` directly. It is consolidated here because this reconstruction
 * routes every screen through one [AuthUiActions.submit] seam, which is also what makes the flows
 * testable without an activity.
 *
 * ### Secret handling
 * Passwords, MFA codes, backup codes, tokens and captcha tokens pass through this object as method
 * arguments only. Nothing here logs a credential: failures are reported through [RestApiError],
 * whose `toString` already redacts messages, and every [RestAPIParams] body used below overrides
 * `toString` to redact. No value handled here is written to a `Bundle`, a `SharedPreferences` entry
 * or the app log.
 */
object AuthRequests {

    /** `code` values the login endpoint uses to redirect the flow instead of failing it. */
    private const val CODE_ACCOUNT_SCHEDULED_FOR_DELETION = 20011
    private const val CODE_ACCOUNT_DISABLED = 20013
    private const val CODE_PHONE_IP_AUTHORIZATION_REQUIRED = 70007

    private const val DATE_OF_BIRTH_FORMAT = "yyyy-MM-dd"

    /** Web page that completes a password reset; the app never takes the new password itself. */
    const val PASSWORD_RESET_URL = "https://discord.com/reset#token="

    private val authentication get() = StoreStream.getAuthentication()

    /**
     * Runs [action] and reports the outcome exactly once on the main thread.
     *
     * @param captcha payload from a challenge the user just solved, replayed with the retry.
     */
    fun submit(
        action: AuthAction,
        captcha: CaptchaHelper.CaptchaPayload? = null,
        callback: (AuthResult) -> Unit,
    ) {
        val captchaKey = captcha?.captchaKey
        val captchaRqtoken = captcha?.captchaRqtoken
        when (action) {
            is AuthAction.TokenLogin -> tokenLogin(action, callback)
            is AuthAction.Login -> login(action, captchaKey, captchaRqtoken, undelete = false, callback = callback)
            is AuthAction.UndeleteAccount -> undelete(action, callback)
            is AuthAction.Register -> register(action, captchaKey, captchaRqtoken, callback)
            is AuthAction.SendPhoneCode -> sendPhoneCode(action, captchaKey, captchaRqtoken, callback)
            is AuthAction.ResendPhoneCode -> resendPhoneCode(action, callback)
            is AuthAction.VerifyPhone -> verifyPhone(action, callback)
            is AuthAction.VerifyMfa -> verifyMfa(action, callback)
            is AuthAction.ResendEmail -> resendEmail(callback)
            is AuthAction.VerifyAge -> verifyAge(action, callback)
            is AuthAction.ForgotPassword -> forgotPassword(action, captchaKey, captchaRqtoken, callback)
            is AuthAction.ChangePassword -> changePassword(action, callback)
        }
    }

    // region actions

    private fun tokenLogin(action: AuthAction.TokenLogin, callback: (AuthResult) -> Unit) {
        val errors = AuthUiRules.token(action.token)
        if (errors.isNotEmpty()) {
            callback(AuthResult.Failure(fieldErrors = errors))
            return
        }
        authentication.loginWithToken(action.token)
        callback(AuthResult.Success())
    }

    /**
     * The reference's `WidgetAuthLogin.login`.
     *
     * Three server codes are navigation, not failure: an account scheduled for deletion or already
     * disabled goes to the undelete screen, and an unrecognised IP goes to phone verification.
     */
    private fun login(
        action: AuthAction.Login,
        captchaKey: String?,
        captchaRqtoken: String?,
        undelete: Boolean,
        callback: (AuthResult) -> Unit,
    ) {
        val identifier = action.identifier
        // Held only until this attempt terminates; the undelete confirmation needs the same
        // credential a second time because the API requires it with `undelete = true`.
        pendingLogin = action
        authentication
            .login(identifier, action.password, captchaKey, captchaRqtoken, undelete)
            .dispatch(
                tag = "REST: login",
                callback = callback,
                onSuccess = { result ->
                    // An MFA challenge is a 200 with a ticket and no token. StoreAuthentication has
                    // already declined to dispatch it as a login, so the code screen is next.
                    val ticket = result.ticket
                    if (result.mfa && ticket != null) {
                        pendingLogin = null
                        AuthResult.Challenge(
                            AuthRoute.MFA,
                            AuthUiState(route = AuthRoute.MFA, ticket = ticket, identifier = identifier),
                        )
                    } else {
                        pendingLogin = null
                        AuthResult.Success()
                    }
                },
                onError = { error ->
                    when (error.code) {
                        CODE_ACCOUNT_SCHEDULED_FOR_DELETION -> AuthResult.Challenge(
                            AuthRoute.UNDELETE_ACCOUNT,
                            AuthUiState(
                                route = AuthRoute.UNDELETE_ACCOUNT,
                                identifier = identifier,
                                disabledAccount = false,
                            ),
                        )
                        CODE_ACCOUNT_DISABLED -> AuthResult.Challenge(
                            AuthRoute.UNDELETE_ACCOUNT,
                            AuthUiState(
                                route = AuthRoute.UNDELETE_ACCOUNT,
                                identifier = identifier,
                                disabledAccount = true,
                            ),
                        )
                        CODE_PHONE_IP_AUTHORIZATION_REQUIRED -> AuthResult.Challenge(
                            AuthRoute.PHONE_VERIFICATION,
                            AuthUiState(
                                route = AuthRoute.PHONE_VERIFICATION,
                                identifier = identifier,
                                phone = identifier,
                                phonePurpose = AuthPhonePurpose.LOGIN,
                            ),
                        )
                        else -> {
                            pendingLogin = null
                            null
                        }
                    }
                },
            )
    }

    /**
     * Confirms the user wants the account back (or that they do not).
     *
     * "Restore" re-runs the login with `undelete = true`; declining just returns to the landing
     * screen, matching the reference, which simply sends the user home.
     */
    private fun undelete(action: AuthAction.UndeleteAccount, callback: (AuthResult) -> Unit) {
        if (!action.restore) {
            pendingLogin = null
            callback(AuthResult.Success(nextRoute = AuthRoute.LANDING))
            return
        }
        val credentials = pendingLogin
        if (credentials == null) {
            // The password is intentionally not retained across process death, so there is nothing
            // to replay; sending the user back to the login form is the only honest option.
            callback(AuthResult.Success(nextRoute = AuthRoute.LOGIN))
            return
        }
        login(credentials, null, null, undelete = true, callback = callback)
    }

    private fun register(
        action: AuthAction.Register,
        captchaKey: String?,
        captchaRqtoken: String?,
        callback: (AuthResult) -> Unit,
    ) {
        authentication
            .register(
                username = action.username,
                email = action.email,
                phoneToken = action.phoneToken,
                password = action.password,
                captchaKey = captchaKey,
                captchaRqtoken = captchaRqtoken,
                consent = action.consented,
                dateOfBirth = action.birthday?.let { TimeUtils.toUTCDateTime(it, DATE_OF_BIRTH_FORMAT) },
            )
            .dispatch("REST: register", callback) { AuthResult.Success() }
    }

    /** Starts phone registration; the code screen follows. */
    private fun sendPhoneCode(
        action: AuthAction.SendPhoneCode,
        captchaKey: String?,
        captchaRqtoken: String?,
        callback: (AuthResult) -> Unit,
    ) {
        RestAPI.api
            .postAuthRegisterPhone(RestAPIParams.AuthRegisterPhone(action.phone, captchaKey, captchaRqtoken))
            .requireSuccessfulEmpty()
            .dispatch("REST: register phone", callback) {
                AuthResult.Challenge(
                    AuthRoute.PHONE_VERIFICATION,
                    AuthUiState(route = AuthRoute.PHONE_VERIFICATION, phone = action.phone),
                )
            }
    }

    private fun resendPhoneCode(action: AuthAction.ResendPhoneCode, callback: (AuthResult) -> Unit) {
        RestAPI.api
            .phoneVerificationsResend(RestAPIParams.VerificationCodeResend(action.phone))
            .dispatch("REST: resend phone code", callback) { AuthResult.Success() }
    }

    /**
     * Exchanges the SMS code for a phone token and carries it into account creation.
     *
     * The token is a short-lived server credential: it is handed to the next screen through the
     * in-memory UI state only, never persisted.
     */
    private fun verifyPhone(action: AuthAction.VerifyPhone, callback: (AuthResult) -> Unit) {
        val request = RestAPI.api
            .phoneVerificationsVerify(RestAPIParams.VerificationCode(action.phone, action.code))
            .requireSuccessful()

        if (action.purpose != AuthPhonePurpose.LOGIN) {
            request.dispatch("REST: verify phone", callback) { verification ->
                verifiedPhoneResult(action.purpose, action.phone, verification.token)
            }
            return
        }

        request.execute("REST: verify phone", callback) { verification ->
            authentication.authorizeIP(verification.token)
                .requireSuccessfulEmpty()
                .execute("REST: authorize IP", callback) {
                    val credentials = pendingLogin
                    if (credentials == null) callback(AuthResult.Success(nextRoute = AuthRoute.LOGIN))
                    else login(credentials, null, null, undelete = false, callback = callback)
                }
        }
    }

    internal fun verifiedPhoneResult(
        purpose: AuthPhonePurpose,
        phone: String,
        token: String,
    ): AuthResult.Challenge = when (purpose) {
        AuthPhonePurpose.REGISTRATION -> AuthResult.Challenge(
            AuthRoute.REGISTER_ACCOUNT,
            AuthUiState(
                route = AuthRoute.REGISTER_ACCOUNT,
                phone = phone,
                phoneToken = token,
            ),
        )
        AuthPhonePurpose.PASSWORD_RESET -> AuthResult.Challenge(
            AuthRoute.RESET_PASSWORD,
            AuthUiState(route = AuthRoute.RESET_PASSWORD, resetToken = token),
        )
        AuthPhonePurpose.LOGIN -> error("Login phone verification must authorize the IP before retrying")
    }

    private fun verifyMfa(action: AuthAction.VerifyMfa, callback: (AuthResult) -> Unit) {
        authentication
            .authMFA(action.code, action.ticket)
            .dispatch("REST: mfa", callback) { AuthResult.Success() }
    }

    private fun resendEmail(callback: (AuthResult) -> Unit) {
        authentication
            .resendVerificationEmail()
            .requireSuccessfulEmpty()
            .dispatch("REST: resend verification email", callback) { AuthResult.Success() }
    }

    /** Writes the date of birth onto the account, which is what clears the age gate. */
    private fun verifyAge(action: AuthAction.VerifyAge, callback: (AuthResult) -> Unit) {
        RestAPI.api
            .patchUser(
                RestAPIParams.UserInfo(
                    dateOfBirth = TimeUtils.toUTCDateTime(action.birthday, DATE_OF_BIRTH_FORMAT),
                ),
            )
            .dispatch(
                tag = "REST: age verify",
                callback = callback,
                onSuccess = {
                    authentication.setAgeGateError(null)
                    AuthResult.Success()
                },
                onError = { error ->
                    // The server rejects an under-age date with a field error; surfacing it as the
                    // age-gated screen is what the reference does with the same message.
                    val message = error.messages["date_of_birth"]?.firstOrNull()
                    if (message != null) {
                        authentication.setAgeGateError(message)
                        AuthResult.Challenge(
                            AuthRoute.AGE_GATED,
                            AuthUiState(route = AuthRoute.AGE_GATED, underageMessage = message),
                        )
                    } else {
                        null
                    }
                },
            )
    }

    private fun forgotPassword(
        action: AuthAction.ForgotPassword,
        captchaKey: String?,
        captchaRqtoken: String?,
        callback: (AuthResult) -> Unit,
    ) {
        val login = action.identifier
        if (login.isBlank()) {
            callback(AuthResult.Failure(fieldErrors = mapOf("identifier" to "Email or phone number is required.")))
            return
        }
        authentication
            .forgotPassword(login, captchaKey, captchaRqtoken)
            .requireSuccessfulEmpty()
            .dispatch(
                tag = "REST: forgot password",
                callback = callback,
                onError = { error ->
                    if (error.code == CODE_PHONE_IP_AUTHORIZATION_REQUIRED) {
                        AuthResult.Challenge(
                            AuthRoute.PHONE_VERIFICATION,
                            AuthUiState(
                                route = AuthRoute.PHONE_VERIFICATION,
                                phone = login,
                                phonePurpose = AuthPhonePurpose.PASSWORD_RESET,
                            ),
                        )
                    } else {
                        null
                    }
                },
            ) { AuthResult.Success(message = login) }
    }

    private fun changePassword(action: AuthAction.ChangePassword, callback: (AuthResult) -> Unit) {
        RestAPI.api
            .patchUser(
                RestAPIParams.UserInfo(
                    password = action.currentPassword,
                    newPassword = action.newPassword,
                    code = action.mfaCode,
                ),
            )
            .dispatch("REST: change password", callback) { AuthResult.Success() }
    }

    // endregion

    /**
     * The identifier/password pair of the login attempt currently being resolved.
     *
     * Held only for the length of a single interrupted login — the undelete confirmation is a
     * second screen, and the API requires the whole credential again with `undelete = true`. It is
     * cleared as soon as a login terminates, is never written to disk or to saved instance state,
     * and does not survive process death.
     */
    @Volatile
    private var pendingLogin: AuthAction.Login? = null

    internal fun cancelPendingLogin() {
        pendingLogin = null
    }

    // region plumbing

    /**
     * Subscribes, maps success to an [AuthResult], and turns every failure into either a
     * navigation result, a captcha challenge, or field errors the form can render.
     *
     * @param onError first refusal on the failure; returning `null` falls through to the default
     *   captcha/field-error handling.
     */
    private fun <T> Observable<T>.dispatch(
        tag: String,
        callback: (AuthResult) -> Unit,
        onError: ((RestApiError) -> AuthResult?)? = null,
        onSuccess: (T) -> AuthResult,
    ) {
        execute(tag, callback, onError) { value -> callback(onSuccess(value)) }
    }

    private fun <T> Observable<T>.execute(
        tag: String,
        callback: (AuthResult) -> Unit,
        onError: ((RestApiError) -> AuthResult?)? = null,
        onSuccess: (T) -> Unit,
    ) {
        restSubscribeOn(retry = false)
            .ui()
            .appSubscribe(
                errorTag = tag,
                errorHandler = { error ->
                    // The form renders the failure itself; a toast on top of that is noise.
                    error.showErrorToasts = false
                    callback(onError?.invoke(error) ?: defaultFailure(error))
                },
            ) { value -> onSuccess(value) }
    }

    /**
     * Turns a non-2xx `Response` into an error.
     *
     * Endpoints declared as `Observable<Response<T>>` emit normally on a 4xx, so without this a
     * rejected phone number — or, worse, a captcha challenge — would be read as success.
     */
    private fun <T : Any> Observable<Response<T>>.requireSuccessful(): Observable<T> = flatMap { response ->
        val body = response.body()
        if (response.isSuccessful && body != null) {
            Observable.just(body)
        } else {
            Observable.error(HttpException(response))
        }
    }

    /**
     * As [requireSuccessful], for the endpoints whose success body is empty (`Response<Void>`).
     *
     * A separate name rather than an overload: `Void` also satisfies the generic receiver above, so
     * two same-named extensions would be ambiguous at the call site.
     */
    private fun Observable<Response<Void>>.requireSuccessfulEmpty(): Observable<Unit> = flatMap { response ->
        if (response.isSuccessful) Observable.just(Unit) else Observable.error(HttpException(response))
    }

    private fun defaultFailure(error: RestApiError): AuthResult {
        if (error.isCaptchaRequired) {
            return AuthResult.CaptchaRequired(CaptchaErrorBody.createFromError(error))
        }
        val fieldErrors = error.messages
            .filterKeys { it != CaptchaHelper.CAPTCHA_KEY }
            .mapNotNull { (key, values) -> values.firstOrNull()?.let { fieldNameOf(key) to it } }
            .toMap()
        return AuthResult.Failure(fieldErrors = fieldErrors, message = error.message)
    }

    /** Maps a server field name onto the key the auth forms use for that input. */
    private fun fieldNameOf(serverField: String): String = when (serverField) {
        "login", "email", "phone" -> "identifier"
        "date_of_birth" -> "birthday"
        "new_password" -> "password"
        "consent" -> "consent"
        else -> serverField
    }

    // endregion

}
