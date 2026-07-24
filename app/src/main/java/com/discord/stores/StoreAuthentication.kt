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
import com.discord.api.auth.RegisterResponse
import com.discord.api.auth.RequiredAction
import com.discord.models.authentication.AuthState
import com.discord.models.domain.auth.ModelLoginResult
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.authentication.AuthStateCache
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
    preferences: SharedPreferences,
    private val sessionDurablePreferences: SharedPreferences,
    private val onAuthStateChanged: (AuthState?) -> Unit = {},
    private val onLoginResult: (ModelLoginResult) -> Unit = {},
    private val onPreLogout: () -> Unit = {},
    private val pushTokenProvider: () -> String? = { null },
    private val timeoutScheduler: Scheduler = Schedulers.computation(),
    private val logoutTimeoutMillis: Long = 10_000L,
) {
    private var _isConsentRequired: Boolean? = null
    private val ageGateError = BehaviorSubject.create<String?>()
    private var authState: AuthState? = null
    private val authStateCache = AuthStateCache(preferences)
    private val authStateSubject = SerializedSubject<AuthState?, AuthState?>(BehaviorSubject.create())
    private var fingerprint: String? = null
    private val fingerprintSubject = SerializedSubject<String, String>(BehaviorSubject.create())
    private var isConsentRequiredSubscription: Subscription? = null
    private val oauthUriSubject = SerializedSubject<Uri, Uri>(BehaviorSubject.create(Uri.EMPTY))
    private var savedLogin: String? = null
    private val userInitiatedAuthEventSubject = PublishSubject.create<Boolean>()
    private var api: RestAPIInterface? = null

    fun bindApi(api: RestAPIInterface) {
        this.api = api
    }

    fun init(context: Context? = null) {
        sessionDurablePreferences.getString(CACHE_KEY_FINGERPRINT, null)?.let { setFingerprint(it, false) }
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
        captchaKey: String? = null,
        captchaRqtoken: String? = null,
        undelete: Boolean = false,
        loginSource: String? = null,
    ): Observable<ModelLoginResult> {
        setSavedLogin(login)
        return getFingerprintSnapshotOrGenerate().flatMap {
            requireApi().postAuthLogin(
                RestAPIParams.AuthLogin(login, password, captchaKey, captchaRqtoken, undelete, loginSource),
            ).body()
        }.doOnNext(::handleLoginResult)
    }

    fun register(
        username: String,
        email: String?,
        phoneToken: String?,
        password: String,
        captchaKey: String? = null,
        captchaRqtoken: String? = null,
        invite: String? = null,
        guildTemplateCode: String? = null,
        consent: Boolean = false,
        dateOfBirth: String? = null,
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
                    captchaKey,
                    captchaRqtoken,
                    invite,
                    guildTemplateCode,
                    consent,
                    dateOfBirth,
                ),
            ).body()
        }.doOnNext { result ->
            handleLoginResult(ModelLoginResult(token = result.token, ticket = null, userSettings = null, requiredActions = null))
        }
    }

    fun authMFA(code: String, ticket: String): Observable<ModelLoginResult> =
        requireApi().postMFACode(RestAPIParams.MFALogin(ticket, code)).body().doOnNext(::handleLoginResult)

    fun authorizeIP(token: String): Observable<Response<Void>> =
        requireApi().authorizeIP(RestAPIParams.AuthorizeIP(token))

    fun forgotPassword(login: String, captchaKey: String? = null, captchaRqtoken: String? = null): Observable<Response<Void>> =
        requireApi().forgotPassword(RestAPIParams.ForgotPassword(login, captchaKey, captchaRqtoken))

    fun resendVerificationEmail(): Observable<Response<Void>> =
        requireApi().postAuthVerifyResend(RestAPIParams.EmptyBody())

    fun handleLoginResult(loginResult: ModelLoginResult) = dispatcher.schedule { onLoginResult(loginResult) }

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
    }

    fun handlePreLogout() = resetIsConsentRequired()

    fun getAuthState(): AuthState? = authState
    fun observeAuthState(): Observable<AuthState?> = authStateSubject.distinctUntilChanged()
    fun getAuthedToken(): Observable<String?> = observeAuthState().map { it?.token }
    fun observeIsAuthed(): Observable<Boolean> = observeAuthState().map { it != null }.distinctUntilChanged()
    fun isAuthed(): Boolean = authState != null
    fun getSavedLogin(): String? = savedLogin
    fun getFingerprint(): String? = fingerprint
    fun observeFingerprint(): Observable<String> = fingerprintSubject.distinctUntilChanged()
    fun getPreLogoutSignal(): Observable<Boolean> = userInitiatedAuthEventSubject.filter { !it }
    fun observeAgeGateError(): Observable<String?> = ageGateError.distinctUntilChanged()
    fun getOAuthUriObservable(): Observable<Uri> = oauthUriSubject.distinctUntilChanged()
    fun setOAuthUriSubject(uri: Uri) = oauthUriSubject.onNext(uri)

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

    @SuppressLint("MissingPermission")
    private fun getDeviceEmail(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            AccountManager.get(context).accounts.firstOrNull { Patterns.EMAIL_ADDRESS.matcher(it.name).matches() }?.name
        }.getOrNull()
    }

    companion object {
        const val CACHE_KEY_FINGERPRINT = "STORE_AUTHED_FINGERPRINT"
        const val CACHE_KEY_LOGIN = "STORE_AUTHED_LOGIN"
        private const val RNA_CACHE_NAME = "CacheStore"
        private const val RNA_TOKEN_KEY = "token"
    }
}
