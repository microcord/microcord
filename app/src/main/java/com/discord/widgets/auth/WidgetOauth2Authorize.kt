package com.discord.widgets.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.auth.OAuthScope
import com.discord.api.auth.OAuthScopeTypeAdapter
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.i18n.setI18nText
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.ToastManager
import com.discord.utilities.view.validators.ValidationManager
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.views.OAuthPermissionViews
import com.google.android.material.button.MaterialButton
import kotlin.random.Random
import rx.Observable

/**
 * "«App» wants to access your Discord account" — the OAuth2 authorization screen.
 *
 * Everything the screen needs is on the incoming `oauth2/authorize` URL. It is `GET`-ed first, to
 * find out who is asking and for what; pressing Authorize `POST`s the same parameters and follows
 * the redirect the server hands back.
 *
 * Two behaviours are load-bearing and are reproduced exactly:
 *
 * - **An unknown scope aborts the whole flow.** [OAuthPermissionViews.InvalidScopeException] is not
 *   swallowed: a client that cannot describe a permission must not let the user grant it. The
 *   screen toasts the offending scope and finishes with a cancelled result.
 * - **Bot and webhook grants are refused in-app** ([OAuth2Authorize.isUnsupported]). Adding a bot to
 *   a server needs a permission picker this client does not have, so the screen offers the browser
 *   instead of a half-working grant.
 *
 * A `captcha_key` failure on the POST hands off to [WidgetAuthCaptcha] and retries with the solved
 * payload; that payload is passed as a request parameter and is never stored or logged.
 *
 * Reference: `com.discord.widgets.auth.WidgetOauth2Authorize`.
 */
open class WidgetOauth2Authorize : AppFragment(R.layout.widget_oauth_authorize) {

    protected val oauth2ViewModel: OAuth2ViewModel by appViewModels { OAuth2ViewModel() }

    private val validationManager = ValidationManager()
    private val toastManager = ToastManager()

    private val adapter = SimpleRecyclerAdapter<PermissionModel, OAuthPermissionViewHolder>(
        createViewHolder = { inflater, parent -> OAuthPermissionViewHolder.create(inflater, parent) },
    )

    protected open val captchaLauncher: ActivityResultLauncher<Intent> =
        WidgetAuthCaptcha.registerForResult(this) { payload -> authorizeApplication(payload) }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<RecyclerView>(R.id.oauth_authorize_permissions_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@WidgetOauth2Authorize.adapter
        }

        val requestUrl = requestUri()
        try {
            oauth2ViewModel.oauthAuthorize = createOauthAuthorize(requestUrl)
        } catch (missingParameter: IllegalArgumentException) {
            handleError(requireContext(), missingParameter) { finishCancelled() }
            return
        }

        listOf(
            R.id.oauth_authorize_deny,
            R.id.oauth_authorize_loading_cancel,
            R.id.oauth_authorize_unsupported_cancel,
        ).forEach { id -> view.findViewById<MaterialButton>(id).setOnClickListener { finishCancelled() } }

        // Backing out of an authorization must leave nothing behind: the app that sent the user
        // here is a separate task, and returning to a half-finished consent screen is confusing.
        setOnBackPressed({
            requireActivity().finishAndRemoveTask()
            true
        })

        configureAgeNoticeUI(oauth2ViewModel.oauthAuthorize.clientId)
        configureSecurityNoticeUI(oauth2ViewModel.oauthAuthorize.scopeNames)
        StoreStream.getNotices().markSeen(noticeName(oauth2ViewModel.oauthAuthorize.clientId))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (activity?.isFinishing == true) return
        if (oauth2ViewModel.oauthAuthorize.isUnsupported) {
            configureNotSupportedUI(requestUri())
            return
        }
        val cached = oauth2ViewModel.oauthGetResponse
        if (cached != null) {
            configureUI(cached)
            return
        }
        showOnly(R.id.oauth_authorize_loading)
        oauth2ViewModel.oauthAuthorize.get()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = javaClass,
                context = requireContext(),
                errorHandler = { error ->
                    val messages = error.messages.takeIf { it.isNotEmpty() }
                        ?: mapOf(ROOT_ERROR_KEY to listOf(error.message.orEmpty()))
                    error.showErrorToasts = validationManager.setErrors(messages).isEmpty()
                    finishCancelled()
                },
            ) { response ->
                oauth2ViewModel.oauthGetResponse = response
                configureUI(response)
            }
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    protected open fun configureUI(data: RestAPIParams.OAuth2Authorize.ResponseGet) {
        val view = view ?: return
        showOnly(R.id.oauth_authorize_content)

        MGImages.setImage(
            view.findViewById<ImageView>(R.id.oauth_authorize_user_icon),
            IconUtils.getForUser(data.user.id, data.user.avatar?.value, data.user.discriminator.toIntOrNull()),
        )
        view.findViewById<TextView>(R.id.oauth_authorize_application_name).text = data.application.name
        view.findViewById<TextView>(R.id.oauth_authorize_permissions_label_tv)
            .setI18nText(R.string.oauth2_scopes_label, "application" to data.application.name)
        MGImages.setImage(
            view.findViewById<ImageView>(R.id.oauth_authorize_application_icon),
            data.application.icon?.let { IconUtils.getApplicationIcon(data.application.id, it) },
        )

        val permissions = try {
            oauth2ViewModel.oauthAuthorize.scopeNames.map { scopeName ->
                val scope = OAuthScopeTypeAdapter.scopeOf(scopeName)
                // Resolving the string now is what makes an unknown scope fail *before* the
                // Authorize button is wired up.
                OAuthPermissionViews.scopePermissionStringRes(scope)
                PermissionModel(scope = scope)
            }
        } catch (unknownScope: OAuthPermissionViews.InvalidScopeException) {
            onUnknownScope(unknownScope)
            return
        }

        // A single decorative row when there is nothing real to list, so the panel is never empty.
        val filler = if (permissions.isEmpty()) listOf(PermissionModel(fakeText = randomFakeScopeText())) else emptyList()
        adapter.setData(permissions + filler)

        view.findViewById<MaterialButton>(R.id.oauth_authorize_grant)
            .setOnClickListener { authorizeApplication(null) }
    }

    private fun authorizeApplication(captchaPayload: CaptchaHelper.CaptchaPayload?) {
        oauth2ViewModel.oauthAuthorize
            .post(captchaPayload?.captchaKey, captchaPayload?.captchaRqtoken)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = javaClass,
                errorHandler = { error ->
                    val unhandled = validationManager.setErrors(error.messages).toMutableList()
                    WidgetAuthCaptcha.processErrorsForCaptcha(requireContext(), captchaLauncher, unhandled, error)
                },
            ) { response ->
                if (!isSafeRedirect(response.location)) {
                    AppLog.w("Refusing an OAuth redirect with an unsupported scheme.")
                    finishCancelled()
                } else {
                    var handled = true
                    UriHandler.handle(
                        requireContext(),
                        response.location,
                        preventCustomTab = true,
                        onFailure = { handled = false },
                    )
                    if (handled) {
                        appActivity?.setResult(android.app.Activity.RESULT_OK)
                        requireActivity().finish()
                    } else {
                        finishCancelled()
                    }
                }
            }
    }

    private fun configureAgeNoticeUI(clientId: Long) {
        val createdAtMs = TimeUtils.parseSnowflake(clientId)
        requireView().findViewById<TextView>(R.id.oauth_authorize_age_notice).setI18nText(
            R.string.oauth2_details_creation_date,
            "date" to DateUtils.formatDateTime(requireContext(), createdAtMs, DateUtils.FORMAT_ABBREV_MONTH),
        )
    }

    /** Tells the user, in as many words, whether this grant lets the app read their messages. */
    private fun configureSecurityNoticeUI(scopeNames: List<String>) {
        val noticeRes = if (OAuthScope.MessagesRead.scopeName in scopeNames) {
            R.string.oauth2_can_read_notice
        } else {
            R.string.oauth2_cannot_read_send_notice
        }
        requireView().findViewById<TextView>(R.id.oauth_authorize_security_notice).setI18nText(noticeRes)
    }

    private fun configureNotSupportedUI(uri: Uri) {
        showOnly(R.id.oauth_authorize_not_supported)
        requireView().findViewById<MaterialButton>(R.id.oauth_authorize_unsupported_browser)
            .setOnClickListener {
                UriHandler.handle(requireContext(), uri.toString())
                finishCancelled()
            }
    }

    private fun onUnknownScope(exception: OAuthPermissionViews.InvalidScopeException) {
        AppLog.e("invalid scope ${exception.scope} in ${javaClass.simpleName}", exception)
        toastManager.show(
            requireContext(),
            com.discord.i18n.FormatUtils.format(
                getString(R.string.oauth2_request_invalid_scope),
                "scope" to exception.scope,
            ),
            android.widget.Toast.LENGTH_LONG,
        )
        finishCancelled()
    }

    /** Builds the request description; overridden by the Samsung variant. */
    protected open fun createOauthAuthorize(requestUrl: Uri): OAuth2Authorize = parse(requestUrl)

    @Suppress("DEPRECATION")
    private fun requestUri(): Uri =
        arguments?.getParcelable<Uri>(INTENT_PARAM_URI)
            ?: mostRecentIntent.getParcelableExtra<Uri>(INTENT_PARAM_URI)
            ?: Uri.EMPTY

    private fun showOnly(visibleId: Int) {
        val view = view ?: return
        listOf(R.id.oauth_authorize_content, R.id.oauth_authorize_loading, R.id.oauth_authorize_not_supported)
            .forEach { id ->
                view.findViewById<View>(id).visibility = if (id == visibleId) View.VISIBLE else View.GONE
            }
    }

    private fun finishCancelled() {
        appActivity?.setResult(android.app.Activity.RESULT_CANCELED)
        activity?.finish()
    }

    private fun randomFakeScopeText(): Int = FAKE_SCOPE_TEXTS[Random.nextInt(FAKE_SCOPE_TEXTS.size)]

    /** State that has to outlive a rotation: the parsed request and the `GET` it already made. */
    class OAuth2ViewModel : AppViewModel<Unit>() {
        lateinit var oauthAuthorize: OAuth2Authorize
        var oauthGetResponse: RestAPIParams.OAuth2Authorize.ResponseGet? = null
    }

    /**
     * The `oauth2/authorize` request, parsed off the incoming URL.
     *
     * `toString` is left with the parameters visible except [codeChallenge], which is the PKCE
     * secret for the exchange that follows.
     */
    data class OAuth2Authorize(
        val clientId: Long,
        val state: String? = null,
        val responseType: String? = null,
        val redirectUrl: String? = null,
        val prompt: String = PROMPT_CONSENT,
        val scope: String,
        val permissions: String? = null,
        val codeChallenge: String? = null,
        val codeChallengeMethod: String? = null,
        val internalReferrer: String? = null,
    ) {
        val scopeNames: List<String> get() = scope.split(" ").filter { it.isNotEmpty() }

        /**
         * Grants this client cannot complete: adding a bot or an incoming webhook needs a channel
         * and permission picker that only the web/desktop consent screen has.
         */
        val isUnsupported: Boolean
            get() = scopeNames.any {
                it.equals(OAuthScope.Bot.scopeName, ignoreCase = true) ||
                    it.equals(OAuthScope.WebhookIncoming.scopeName, ignoreCase = true)
            }

        fun get(): Observable<RestAPIParams.OAuth2Authorize.ResponseGet> = RestAPI.api.getOauth2Authorize(
            clientId.toString(), state, responseType, redirectUrl, prompt, scope, permissions,
        )

        fun post(
            captchaKey: String? = null,
            captchaRqtoken: String? = null,
        ): Observable<RestAPIParams.OAuth2Authorize.ResponsePost> {
            val body = buildMap {
                put("authorize", "true")
                captchaKey?.let { put(CaptchaHelper.CAPTCHA_KEY, it) }
                captchaRqtoken?.let { put("captcha_rqtoken", it) }
            }
            return RestAPI.api.postOauth2Authorize(
                clientId.toString(), state, responseType, redirectUrl, prompt, scope, permissions,
                codeChallenge, codeChallengeMethod, body,
            )
        }

        override fun toString(): String = "OAuth2Authorize(clientId=$clientId, state=$state, " +
            "responseType=$responseType, redirectUrl=$redirectUrl, prompt=$prompt, scope=$scope, " +
            "permissions=$permissions, codeChallenge=<redacted>, " +
            "codeChallengeMethod=$codeChallengeMethod, internalReferrer=$internalReferrer)"
    }

    private class PermissionModel(val scope: OAuthScope? = null, val fakeText: Int? = null)

    private class OAuthPermissionViewHolder(
        itemView: View,
    ) : SimpleRecyclerAdapter.ViewHolder<PermissionModel>(itemView) {

        private val icon: ImageView = itemView.findViewById(R.id.oauth_token_permission_detailed_icon)
        private val name: TextView = itemView.findViewById(R.id.oauth_token_permission_detailed_name)

        override fun bind(data: PermissionModel) {
            when {
                data.scope != null -> {
                    icon.setImageResource(R.drawable.ic_check_circle_green_24dp)
                    OAuthPermissionViews.setScopePermissionText(name, data.scope)
                }
                data.fakeText != null -> {
                    icon.setImageResource(R.drawable.ic_close_circle_grey_24dp)
                    name.setText(data.fakeText)
                }
            }
        }

        companion object {
            fun create(inflater: LayoutInflater, parent: ViewGroup) = OAuthPermissionViewHolder(
                inflater.inflate(R.layout.oauth_token_permission_detailed_list_item, parent, false),
            )
        }
    }

    companion object {
        const val INTENT_PARAM_URI = "REQ_URI"

        private const val PROMPT_CONSENT = "consent"
        private const val ROOT_ERROR_KEY = "_root"

        private const val QUERY_PARAM_CLIENT_ID = "client_id"
        private const val QUERY_PARAM_STATE = "state"
        private const val QUERY_PARAM_RESPONSE_TYPE = "response_type"
        private const val QUERY_PARAM_REDIRECT = "redirect_uri"
        private const val QUERY_PARAM_SCOPE = "scope"
        private const val QUERY_PARAM_PERMISSIONS = "permissions"
        private const val QUERY_PARAM_CODE_CHALLENGE = "code_challenge"
        private const val QUERY_PARAM_CODE_CHALLENGE_METHOD = "code_challenge_method"
        private const val QUERY_INTERNAL_REFERRER = "internal_referrer"

        private val FAKE_SCOPE_TEXTS = intArrayOf(
            R.string.oauth2_fake_scope_1,
            R.string.oauth2_fake_scope_2,
            R.string.oauth2_fake_scope_3,
            R.string.oauth2_fake_scope_4,
            R.string.oauth2_fake_scope_5,
            R.string.oauth2_fake_scope_6,
            R.string.oauth2_fake_scope_7,
            R.string.oauth2_fake_scope_8,
        )

        /** Notice key the OAuth request queues under, so two requests never stack. */
        fun noticeName(clientId: Long): String = "OAUTH_REQUEST:$clientId"

        internal fun isSafeRedirect(location: String): Boolean = UriHandler.isSafeScheme(location)

        /**
         * Parses the request URL.
         *
         * @throws IllegalArgumentException naming the first required parameter that is absent —
         *   `client_id` and `scope` are the two the request cannot be built without.
         */
        fun parse(requestUrl: Uri): OAuth2Authorize = parse(
            requestUrl.queryParameterNames.orEmpty()
                .associateWith { requestUrl.getQueryParameter(it) },
        )

        /**
         * Parses the already-extracted query parameters.
         *
         * Split out from the [Uri] overload so the parameter rules can be exercised without an
         * Android runtime, and so a deep link that was already decomposed into a parameter map does
         * not have to be re-serialised into a URL first.
         *
         * @throws IllegalArgumentException naming the first required parameter that is absent.
         */
        fun parse(parameters: Map<String, String?>): OAuth2Authorize = OAuth2Authorize(
            clientId = parameters.require(QUERY_PARAM_CLIENT_ID).toLong(),
            state = parameters[QUERY_PARAM_STATE],
            responseType = parameters[QUERY_PARAM_RESPONSE_TYPE],
            redirectUrl = parameters[QUERY_PARAM_REDIRECT],
            scope = parameters.require(QUERY_PARAM_SCOPE),
            permissions = parameters[QUERY_PARAM_PERMISSIONS],
            codeChallenge = parameters[QUERY_PARAM_CODE_CHALLENGE],
            codeChallengeMethod = parameters[QUERY_PARAM_CODE_CHALLENGE_METHOD],
            internalReferrer = parameters[QUERY_INTERNAL_REFERRER],
        )

        private fun Map<String, String?>.require(name: String): String =
            get(name) ?: throw IllegalArgumentException(name)

        /** Rebuilds an authorize URL from the query parameters a deep link was parsed into. */
        fun authorizeUri(parameters: Map<String, String>): Uri =
            Uri.parse("https://discord.com/oauth2/authorize").buildUpon()
                .apply { parameters.forEach { (key, value) -> appendQueryParameter(key, value) } }
                .build()

        /**
         * Opens the authorization screen for [requestUri].
         *
         * A malformed URL never reaches the screen: it is reported here and the launch is dropped,
         * which is what the reference does.
         */
        fun launch(context: Context, requestUri: Uri) {
            val request = try {
                parse(requestUri)
            } catch (invalid: Exception) {
                handleError(context, invalid)
                return
            }
            context.startActivity(createLaunchIntent(context, requestUri, request))
        }

        /**
         * Targets the **base** [com.discord.app.AppActivity] rather than `AppActivity.Main`.
         *
         * `Main` is `singleTask`, so routing consent through it would make "deny" finish the whole
         * app instead of just this screen — and would drop the user back into Discord rather than
         * into whatever asked for the authorization.
         */
        private fun createLaunchIntent(context: Context, requestUri: Uri, request: OAuth2Authorize): Intent {
            val intent = Intent(context, com.discord.app.AppActivity::class.java)
                .putExtra(AppScreenRouter.EXTRA_SCREEN, WidgetOauth2Authorize::class.java)
                .putExtra(INTENT_PARAM_URI, requestUri)
            // A request that did not come from inside the app starts a fresh task, so backing out
            // of consent returns to whatever asked for it rather than into Discord's own stack.
            val fromInsideApp = request.internalReferrer?.toBoolean() == true
            if (!fromInsideApp && !request.isUnsupported) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            return intent
        }

        private fun handleError(context: Context, exception: Exception, onComplete: () -> Unit = {}) {
            val message = com.discord.i18n.FormatUtils.format(
                context.getString(R.string.oauth2_request_missing_param),
                "param" to exception.message,
            )
            AppLog.w(message, exception)
            ToastManager().show(context, message, android.widget.Toast.LENGTH_LONG)
            onComplete()
        }
    }
}
