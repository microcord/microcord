package com.discord.stores

import android.Manifest
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Patterns
import androidx.core.content.ContextCompat
import com.discord.api.auth.AuthSessionChange
import com.discord.api.auth.ForgotPasswordResponse
import com.discord.api.auth.RegisterResponse
import com.discord.api.auth.RequiredAction
import com.discord.api.auth.mfa.MfaMethodType
import com.discord.api.auth.mfa.SendMfaSmsResponse
import com.discord.api.channel.Channel
import com.discord.models.authentication.AuthState
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.auth.ModelLoginResult
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.restapi.cookie.CookieJars
import com.discord.stores.authentication.AuthStateCache
import com.discord.utilities.error.AccountSuspendedException
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.TimeUtils
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import retrofit2.HttpException
import retrofit2.Response
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import rx.subjects.SerializedSubject

class StoreAuthentication(
    private val dispatcher: Dispatcher,
    private val preferences: SharedPreferences,
    private val sessionDurablePreferences: SharedPreferences,
    private val onAuthStateChanged: (AuthState?) -> Unit = {},
    private val onLoginResult: (ModelLoginResult) -> Unit = {},
    private val onPreLogout: () -> Unit = {},
    private val pushTokenProvider: () -> String? = { null },
    private val timeoutScheduler: Scheduler = Schedulers.computation(),
    private val logoutTimeoutMillis: Long = 10_000L,
    /** `StoreUser.observeMe(true)` — must emit the empty user too, or the gate never evaluates. */
    private val observeMe: () -> Observable<MeUser> = { Observable.never() },
    /** `StoreChannelsSelected.observeSelectedChannel()`. */
    private val observeSelectedChannel: () -> Observable<Channel?> = { Observable.never() },
) {
    private var _isConsentRequired: Boolean? = null
    private val ageGateError = BehaviorSubject.create<String?>()
    private var authState: AuthState? = null
    private var authSessionIdHash: String? = null
    private var sessionId: String? = null
    private val authStateCache = AuthStateCache(preferences)
    /**
     * Seeded with `null` so a subscriber attaching before [init] immediately learns "logged out"
     * rather than hanging. Every routing decision downstream is driven by this subject, so an
     * unseeded one leaves the app on the splash screen until the first dispatch lands.
     */
    private val authStateSubject: SerializedSubject<AuthState?, AuthState?> =
        // The cast is load-bearing: a bare `null` here resolves to the inherited
        // `Observable.create(OnSubscribe)` overload instead of `BehaviorSubject.create(default)`.
        SerializedSubject(BehaviorSubject.create(null as AuthState?))
    private var fingerprint: String? = null
    private var installation: String? = null
    private val fingerprintSubject = SerializedSubject<String, String>(BehaviorSubject.create())
    private var isConsentRequiredSubscription: Subscription? = null
    private val oauthUriSubject = SerializedSubject<Uri, Uri>(BehaviorSubject.create(Uri.EMPTY))
    private var savedLogin: String? = null
    /** In memory only, as in the reference: a restart drops it and returns to the login screen. */
    private var suspendedUserToken: String? = null
    private val suspendedUserTokenSubject: SerializedSubject<String?, String?> =
        SerializedSubject(BehaviorSubject.create(null as String?))
    private val userInitiatedAuthEventSubject = PublishSubject.create<Boolean>()
    private var api: RestAPIInterface? = null

    fun bindApi(api: RestAPIInterface) {
        this.api = api
    }

    fun init(context: Context? = null) {
        sessionDurablePreferences.getString(CACHE_KEY_FINGERPRINT, null)?.let { setFingerprint(it, false) }
        installation = sessionDurablePreferences.getString(CACHE_KEY_INSTALLATION, null)?.takeIf(String::isNotEmpty)
        val cached = authStateCache.getCachedAuthState()
            ?: context?.getSharedPreferences(RNA_CACHE_NAME, Context.MODE_PRIVATE)
                ?.getString(RNA_TOKEN_KEY, null)
                ?.replace("\"", "")
                ?.let { AuthState(it, emptySet()) }
        savedLogin = sessionDurablePreferences.getString(CACHE_KEY_LOGIN, null) ?: context?.let(::getDeviceEmail)
        if (AuthState.isUpdatePasswordRequired(cached)) logout() else publishAuthState(cached)
        handleAgeGateError(null)
    }

    fun login(
        login: String,
        password: String,
        undelete: Boolean = false,
        loginSource: String? = null,
    ): Observable<ModelLoginResult> {
        setSavedLogin(login)
        return getFingerprintSnapshotOrGenerate().flatMap {
            requireApi().postAuthLogin(
                RestAPIParams.AuthLogin(login, password, undelete, loginSource),
            ).loginBody()
        }.doOnNext { loginResult ->
            // An MFA challenge is a 200 carrying a ticket and no token. Dispatching it would
            // publish a null-token auth state and fire the "logged in" event; the caller must be
            // allowed to present the code screen and call [authMFA] instead.
            if (!loginResult.mfa) handleLoginResult(loginResult)
        }
    }

    fun register(
        username: String,
        email: String?,
        phoneToken: String?,
        password: String,
        invite: String? = null,
        guildTemplateCode: String? = null,
        consent: Boolean = false,
        dateOfBirth: String? = null,
        globalName: String? = null,
        giftCodeSkuId: String? = null,
        promotionalEmailOptIn: Boolean? = null,
    ): Observable<RegisterResponse> {
        setSavedLogin(email)
        return getFingerprintSnapshotOrGenerate().flatMap { currentFingerprint ->
            requireApi().postAuthRegister(
                RestAPIParams.AuthRegister(
                    currentFingerprint,
                    username,
                    email,
                    phoneToken,
                    password,
                    invite,
                    guildTemplateCode,
                    consent,
                    dateOfBirth,
                    globalName,
                    giftCodeSkuId,
                    promotionalEmailOptIn,
                ),
            )
        }.doOnNext { result ->
            handleLoginResult(ModelLoginResult(token = result.token, ticket = null, userSettings = null, requiredActions = null))
        }
    }

    fun authMFA(
        authenticatorType: MfaMethodType,
        code: String,
        ticket: String,
        loginInstanceId: String? = null,
    ): Observable<ModelLoginResult> = requireApi()
        .postMFACode(authenticatorType.wireName, RestAPIParams.MFALogin(ticket, code, loginInstanceId))
        .loginBody()
        .doOnNext(::handleLoginResult)

    /** Starts the SMS leg of a login challenge; returns the redacted number the code went to. */
    fun sendMfaSms(ticket: String): Observable<SendMfaSmsResponse> =
        requireApi().sendMFASms(RestAPIParams.MFASendSms(ticket))

    fun oneTimeLogin(ticket: String): Observable<ModelLoginResult> =
        requireApi().oneTimeLogin(RestAPIParams.OneTimeLogin(ticket)).doOnNext(::handleLoginResult)

    fun authorizeIP(token: String): Observable<Unit> =
        requireApi().authorizeIP(RestAPIParams.AuthorizeIP(token))

    fun forgotPassword(login: String): Observable<ForgotPasswordResponse> =
        requireApi().forgotPassword(RestAPIParams.ForgotPassword(login))

    fun resendVerificationEmail(): Observable<Unit> =
        requireApi().postAuthVerifyResend(RestAPIParams.EmptyBody())

    fun handleLoginResult(loginResult: ModelLoginResult) = dispatcher.schedule { onLoginResult(loginResult) }

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        StoreThreadScheduler.assertCurrentThread()
        authSessionIdHash = payload.authSessionIdHash
        sessionId = payload.sessionId
        // A logged-in client is handed its installation here rather than by `/experiments`, which
        // only runs while logged out.
        setInstallation(payload.installation)
    }

    /** A password reset moves the connection to a new auth session without dropping it. */
    @StoreThread
    fun handleAuthSessionChange(change: AuthSessionChange) {
        StoreThreadScheduler.assertCurrentThread()
        authSessionIdHash = change.authSessionIdHash
    }

    /** Which entry of `auth/sessions` is this connection, so the list can refuse to revoke it. */
    fun getAuthSessionIdHash(): String? = authSessionIdHash

    /** Reference `AuthenticationStore.getSessionId`: the gateway session this client is on. */
    fun getSessionId(): String? = sessionId

    fun loginWithToken(token: String) {
        require(token.isNotBlank()) { "Token is required." }
        setAuthed(token.trim())
    }

    @StoreThread
    internal fun handleLoginResultInternal(loginResult: ModelLoginResult) {
        setFingerprint(null, true)
        publishAuthStateInternal(AuthState.from(loginResult.token, loginResult.requiredActions.orEmpty().toSet()))
        if (loginResult.token != null) handleAgeGateError(null)
        userInitiatedAuthEventSubject.onNext(true)
    }

    fun onPasswordChanged(token: String) = dispatcher.schedule {
        val remaining = authState?.requiredActions.orEmpty() - RequiredAction.UPDATE_PASSWORD
        publishAuthStateInternal(AuthState(token, remaining))
    }

    fun setAuthed(token: String?) = publishAuthState(AuthState.from(token, emptySet()))

    private fun setSuspendedUserToken(token: String?) {
        suspendedUserToken = token
        suspendedUserTokenSubject.onNext(token)
    }

    fun getSuspendedUserToken(): String? = suspendedUserToken

    fun observeSuspendedUserToken(): Observable<String?> = suspendedUserTokenSubject.distinctUntilChanged()

    fun closeSuspendedUser() {
        setSuspendedUserToken(null)
        publishAuthState(null)
    }

    fun logout() {
        userInitiatedAuthEventSubject.onNext(false)
        onPreLogout()
        requireApi().logout(RestAPIParams.UserDevices(pushTokenProvider()))
            .flatMap { getFingerprintSnapshotOrGenerate() }
            .timeout(logoutTimeoutMillis, TimeUnit.MILLISECONDS, timeoutScheduler)
            .take(1)
            .subscribe({ publishAuthState(null) }, { publishAuthState(null) })
    }

    fun invalidate() {
        userInitiatedAuthEventSubject.onNext(false)
        onPreLogout()
        publishAuthState(null)
    }

    @StoreThread
    internal fun handleAuthStateInternal(state: AuthState?) {
        authState = state
        authStateCache.cacheAuthState(state)
        if (state == null) {
            // Logout must leave nothing of the previous account behind: every Kryo-backed
            // Persister value, the whole preference store, and the cookie jar — which lives in its
            // own preference file and holds the `__Secure-recent_mfa` elevation bypass. Without
            // this the next account inherits guild order, folders, read states and settings.
            Persister.reset()
            preferences.edit().clear().apply()
            CookieJars.clear()
        }
    }

    fun handlePreLogout() = resetIsConsentRequired()

    fun getAuthState(): AuthState? = authState
    fun observeAuthState(): Observable<AuthState?> = authStateSubject.distinctUntilChanged()
    fun getAuthedToken(): Observable<String?> = observeAuthState().map { it?.token }
    fun observeIsAuthed(): Observable<Boolean> = observeAuthState().map { it != null }.distinctUntilChanged()
    fun isAuthed(): Boolean = authState != null
    fun getSavedLogin(): String? = savedLogin
    fun getFingerprint(): String? = fingerprint

    /**
     * The installation token, or null when tracking consent has not been given.
     *
     * Gated at read rather than at store: the token is kept so it survives a consent change, but it
     * only reaches the wire while consent stands.
     */
    fun getInstallationForTracking(): String? = installation?.takeIf { !isConsentRequired() }

    /**
     * Records the installation token the server minted.
     *
     * First write wins. The server mints one per install and reuses it; accepting a later, different
     * one would split this device's history in two and is the thing the reference explicitly guards
     * against.
     */
    @Synchronized
    fun setInstallation(value: String?) {
        if (value.isNullOrEmpty()) return
        if (!installation.isNullOrEmpty()) return
        installation = value
        sessionDurablePreferences.edit().putString(CACHE_KEY_INSTALLATION, value).apply()
    }
    fun observeFingerprint(): Observable<String> = fingerprintSubject.distinctUntilChanged()
    fun getPreLogoutSignal(): Observable<Boolean> = userInitiatedAuthEventSubject.filter { !it }
    fun observeAgeGateError(): Observable<String?> = ageGateError.distinctUntilChanged()
    fun getOAuthUriObservable(): Observable<Uri> = oauthUriSubject.distinctUntilChanged()
    fun setOAuthUriSubject(uri: Uri) = oauthUriSubject.onNext(uri)

    /**
     * Which age gate, if any, must be presented before the user can continue.
     *
     * Two distinct gates share one source because both are answered by the same "enter your date
     * of birth" screen:
     *
     * - **Register gate** — the account has no birthday on file *and* was created after Discord
     *   started requiring one. Older accounts are grandfathered, which is why the snowflake
     *   timestamp is compared rather than just checking for a missing birthday.
     * - **NSFW gate** — the account has no birthday and the user opened an age-restricted channel.
     *   Suppressed while an age-gate error is outstanding so a rejected submission does not
     *   immediately re-prompt.
     */
    fun getShouldShowAgeGate(): Observable<StoreNavigation.AgeGate?> = Observable.combineLatest(
        observeMe(),
        observeAgeGateError(),
        observeSelectedChannel(),
    ) { meUser, ageGateError, channel ->
        when {
            !meUser.hasBirthday && TimeUtils.parseSnowflake(meUser.id) > BIRTHDAY_REQUIRED_AFTER_MS ->
                StoreNavigation.AgeGate.REGISTER_AGE_GATE
            ageGateError == null && !meUser.hasBirthday && channel?.nsfw == true ->
                StoreNavigation.AgeGate.NSFW_CHANNEL_AGE_GATE
            else -> null
        }
    }.distinctUntilChanged()

    fun setAgeGateError(error: String?) = dispatcher.schedule { handleAgeGateError(error) }

    @StoreThread
    fun handleAgeGateError(error: String?) = ageGateError.onNext(error)

    @Synchronized
    fun setFingerprint(value: String?, force: Boolean) {
        if (!force && fingerprint != null) return
        sessionDurablePreferences.edit().putString(CACHE_KEY_FINGERPRINT, value).apply()
        fingerprint = value
        if (value != null) fingerprintSubject.onNext(value)
    }

    @Synchronized
    fun requestConsentRequired() {
        if (_isConsentRequired != null) return
        resetIsConsentRequired()
        isConsentRequiredSubscription = requireApi().getLocationMetadata().subscribe(
            { setConsentRequired(it.consentRequired) },
            { setConsentRequired(true) },
        )
    }

    @Synchronized
    fun isConsentRequired(): Boolean = _isConsentRequired ?: true

    private fun getFingerprintSnapshotOrGenerate(): Observable<String> = fingerprint?.let { Observable.just(it) }
        ?: requireApi().postAuthFingerprint(RestAPIParams.EmptyBody())
            .map { it.fingerprint }
            .doOnNext { setFingerprint(it, false) }

    private fun setSavedLogin(login: String?) {
        savedLogin = login
        sessionDurablePreferences.edit().putString(CACHE_KEY_LOGIN, login).apply()
    }

    private fun publishAuthState(state: AuthState?) = dispatcher.schedule { publishAuthStateInternal(state) }

    @StoreThread
    private fun publishAuthStateInternal(state: AuthState?) {
        authStateSubject.onNext(state)
        onAuthStateChanged(state)
    }

    @Synchronized
    private fun setConsentRequired(value: Boolean) {
        _isConsentRequired = value
    }

    @Synchronized
    private fun resetIsConsentRequired() {
        _isConsentRequired = null
        isConsentRequiredSubscription?.unsubscribe()
        isConsentRequiredSubscription = null
    }

    private fun requireApi(): RestAPIInterface = checkNotNull(api) { "Authentication REST API is not initialized" }

    private fun <T : Any> Observable<Response<T>>.body(): Observable<T> = flatMap { response ->
        response.body()?.takeIf { response.isSuccessful }?.let { Observable.just(it) }
            ?: Observable.error(HttpException(response))
    }

    /**
     * As [body], but recognises the suspension response.
     *
     * A suspended account answers login and MFA with a 403 whose body looks like a success
     * (`user_id` plus `suspended_user_token`) and carries no error code, so the generic classifier
     * would report it as an ordinary forbidden.
     */
    private fun Observable<Response<ModelLoginResult>>.loginBody(): Observable<ModelLoginResult> =
        flatMap { response ->
            // Emit the response in hand; `body()` here would re-subscribe and re-send the POST.
            val result = response.body()
            if (response.isSuccessful && result != null) return@flatMap Observable.just(result)
            if (response.isSuccessful) return@flatMap Observable.error(HttpException(response))
            // Peeked, not consumed: the same body still has to classify the failure downstream.
            val suspended = runCatching {
                response.errorBody()?.source()?.peek()?.readUtf8()
                    ?.let { SUSPENSION_GSON.fromJson(it, ModelLoginResult::class.java) }
            }.getOrNull()?.suspendedUserToken?.takeUnless(String::isBlank)
            if (suspended != null) setSuspendedUserToken(suspended)
            Observable.error(
                if (suspended != null) AccountSuspendedException(suspended) else HttpException(response),
            )
        }

    @SuppressLint("MissingPermission")
    private fun getDeviceEmail(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            AccountManager.get(context).accounts.firstOrNull { Patterns.EMAIL_ADDRESS.matcher(it.name).matches() }?.name
        }.getOrNull()
    }

    companion object {
        const val CACHE_KEY_FINGERPRINT = "STORE_AUTHED_FINGERPRINT"
        /** The reference's mobile key; kept verbatim so an existing install keeps its token. */
        const val CACHE_KEY_INSTALLATION = "analytics_installation"
        const val CACHE_KEY_LOGIN = "STORE_AUTHED_LOGIN"

        private val SUSPENSION_GSON: Gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
        private const val RNA_CACHE_NAME = "CacheStore"
        private const val RNA_TOKEN_KEY = "token"

        /**
         * Accounts created after this instant must have a date of birth on file. Accounts older
         * than it are grandfathered and are only age-gated when they open NSFW content.
         */
        private val BIRTHDAY_REQUIRED_AFTER_MS = TimeUtils.parseUTCDate("2021-02-05T12:00:00+0000")
    }
}
