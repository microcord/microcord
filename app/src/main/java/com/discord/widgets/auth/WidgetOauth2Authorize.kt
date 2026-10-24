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
import com.discord.restapi.ApplicationAuthorizationBody
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.intent.DeepLinkParser
import com.discord.utilities.intent.LinkParseResult
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
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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
 * Reference: `com.discord.widgets.auth.WidgetOauth2Authorize`.
 */
open class WidgetOauth2Authorize : AppFragment(R.layout.widget_oauth_authorize) {

    protected val oauth2ViewModel: OAuth2ViewModel by appViewModels { OAuth2ViewModel() }

    private val validationManager = ValidationManager()
    private val toastManager = ToastManager()

    private val adapter = SimpleRecyclerAdapter<PermissionModel, OAuthPermissionViewHolder>(
        createViewHolder = { inflater, parent -> OAuthPermissionViewHolder.create(inflater, parent) },
    )

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
        ).forEach { id -> view.findViewById<MaterialButton>(id).setOnClickListener { cancelFromUser() } }

        // Backing out of an authorization must leave nothing behind: the app that sent the user
        // here is a separate task, and returning to a half-finished consent screen is confusing.
        setOnBackPressed({
            oauth2ViewModel.completion?.let(::finish) ?: cancelFromUser()
            true
        })

        configureAgeNoticeUI(oauth2ViewModel.oauthAuthorize.clientId)
        configureSecurityNoticeUI(oauth2ViewModel.oauthAuthorize.scopeNames)
        StoreStream.getNotices().markSeen(noticeName(oauth2ViewModel.oauthAuthorize.clientId))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (activity?.isFinishing == true) return
        oauth2ViewModel.completion?.let {
            configureCompletionUI(it)
            return
        }
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

        val filler = if (shouldAppendFakeScope(permissions.size)) {
            listOf(PermissionModel(fakeText = randomFakeScopeText()))
        } else {
            emptyList()
        }
        adapter.setData(permissions + filler)

        view.findViewById<MaterialButton>(R.id.oauth_authorize_grant)
            .setOnClickListener { authorizeApplication() }
    }

    private fun authorizeApplication() {
        oauth2ViewModel.oauthAuthorize
            .post()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = javaClass,
                errorHandler = { error ->
                    validationManager.setErrors(error.messages)
                    complete(CompletionResult.ERROR)
                },
            ) { response ->
                when (val completion = classifyCompletion(oauth2ViewModel.oauthAuthorize, response.location)) {
                    CompletionRoute.Internal -> complete(CompletionResult.SUCCESS)
                    is CompletionRoute.External -> {
                        pluginRedirects.remove(oauth2ViewModel.oauthAuthorize.clientId)?.let { redirect ->
                            runCatching { redirect(completion.location) }
                            complete(CompletionResult.SUCCESS)
                            return@appSubscribe
                        }
                        var handled = true
                        UriHandler.handle(
                            requireContext(),
                            completion.location,
                            preventCustomTab = true,
                            onFailure = { handled = false },
                        )
                        if (handled) finish(CompletionResult.EXTERNAL) else complete(CompletionResult.ERROR)
                    }
                    CompletionRoute.Invalid -> {
                        AppLog.w("Refusing an invalid OAuth completion location.")
                        complete(CompletionResult.ERROR)
                    }
                }
            }
    }

    private fun complete(result: CompletionResult) {
        oauth2ViewModel.completion = result
        configureCompletionUI(result)
    }

    private fun configureCompletionUI(result: CompletionResult) {
        val view = view ?: return
        showOnly(R.id.oauth_authorize_completion)
        view.findViewById<ImageView>(R.id.oauth_authorize_completion_icon).setImageResource(
            if (result == CompletionResult.SUCCESS) R.drawable.ic_check_circle_green_24dp
            else R.drawable.ic_close_circle_grey_24dp,
        )
        view.findViewById<TextView>(R.id.oauth_authorize_completion_title).setText(
            if (result == CompletionResult.SUCCESS) R.string.authorized else R.string.oauth2_unknown_error,
        )
        view.findViewById<TextView>(R.id.oauth_authorize_completion_message).setText(
            if (result == CompletionResult.SUCCESS) R.string.application_directory_install_success
            else R.string.application_directory_install_error,
        )
        view.findViewById<MaterialButton>(R.id.oauth_authorize_completion_done)
            .setOnClickListener { finish(result) }
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

    /** Builds the request description; open so a caller can special-case an application. */
    protected open fun createOauthAuthorize(requestUrl: Uri): OAuth2Authorize = parse(requestUrl)

    @Suppress("DEPRECATION")
    private fun requestUri(): Uri =
        arguments?.getParcelable<Uri>(INTENT_PARAM_URI)
            ?: mostRecentIntent.getParcelableExtra<Uri>(INTENT_PARAM_URI)
            ?: Uri.EMPTY

    private fun showOnly(visibleId: Int) {
        val view = view ?: return
        listOf(
            R.id.oauth_authorize_content,
            R.id.oauth_authorize_loading,
            R.id.oauth_authorize_not_supported,
            R.id.oauth_authorize_completion,
        )
            .forEach { id ->
                view.findViewById<View>(id).visibility = if (id == visibleId) View.VISIBLE else View.GONE
            }
    }

    private fun finishCancelled() {
        finish(CompletionResult.CANCELLED)
    }

    private fun cancelFromUser() {
        finish(CompletionResult.CANCELLED, removeTask = oauth2ViewModel.oauthAuthorize.internalReferrer?.toBoolean() != true)
    }

    private fun finish(result: CompletionResult, removeTask: Boolean = false) {
        appActivity?.setResult(
            if (result.isSuccess) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,
            Intent().putExtra(RESULT_COMPLETION, result.name),
        )
        if (removeTask) activity?.finishAndRemoveTask() else activity?.finish()
    }

    private fun randomFakeScopeText(): Int = FAKE_SCOPE_TEXTS[Random.nextInt(FAKE_SCOPE_TEXTS.size)]

    /** State that has to outlive a rotation: the parsed request and the `GET` it already made. */
    class OAuth2ViewModel : AppViewModel<Unit>() {
        lateinit var oauthAuthorize: OAuth2Authorize
        var oauthGetResponse: RestAPIParams.OAuth2Authorize.ResponseGet? = null
        var completion: CompletionResult? = null
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
        val scope: String? = null,
        val permissions: String? = null,
        val codeChallenge: String? = null,
        val codeChallengeMethod: String? = null,
        val integrationType: Int? = null,
        val guildId: Long? = null,
        val disableGuildSelect: Boolean = false,
        val internalReferrer: String? = null,
    ) {
        val scopeNames: List<String> get() = scope.orEmpty().split(" ").filter { it.isNotEmpty() }

        /**
         * Incoming webhooks still need a channel picker. Bot grants are supported when the
         * application-directory flow already selected and locked an eligible guild.
         */
        val isUnsupported: Boolean
            get() = scopeNames.any {
                it.equals(OAuthScope.WebhookIncoming.scopeName, ignoreCase = true) ||
                    it.equals(OAuthScope.Bot.scopeName, ignoreCase = true) && guildId == null
            }

        fun get(): Observable<RestAPIParams.OAuth2Authorize.ResponseGet> =
            RestAPI.applicationDirectoryApiOrNull?.previewAuthorization(
                clientId.toString(), state, responseType, redirectUrl, prompt, scope, permissions,
                codeChallenge, codeChallengeMethod, integrationType,
            ) ?: scope?.let {
                RestAPI.api.getOauth2Authorize(clientId.toString(), state, responseType, redirectUrl, prompt, it, permissions)
            } ?: Observable.error(IllegalArgumentException(QUERY_PARAM_SCOPE))

        fun post(): Observable<RestAPIParams.OAuth2Authorize.ResponsePost> {
            RestAPI.applicationDirectoryApiOrNull?.let { service ->
                return service.authorize(
                    clientId.toString(), state, responseType, redirectUrl, prompt, scope,
                    codeChallenge, codeChallengeMethod,
                    ApplicationAuthorizationBody(
                        integrationType = integrationType,
                        permissions = permissions,
                        guildId = guildId,
                    ),
                )
            }
            val requiredScope = scope ?: return Observable.error(IllegalArgumentException(QUERY_PARAM_SCOPE))
            return RestAPI.api.postOauth2Authorize(
                clientId.toString(), state, responseType, redirectUrl, prompt, requiredScope,
                permissions, codeChallenge, codeChallengeMethod, mapOf("authorize" to "true"),
            )
        }

        override fun toString(): String = "OAuth2Authorize(clientId=$clientId, state=$state, " +
            "responseType=$responseType, redirectUrl=$redirectUrl, prompt=$prompt, scope=$scope, " +
            "permissions=$permissions, codeChallenge=<redacted>, " +
            "codeChallengeMethod=$codeChallengeMethod, integrationType=$integrationType, " +
            "guildId=$guildId, disableGuildSelect=$disableGuildSelect, internalReferrer=$internalReferrer)"
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

    enum class CompletionResult(val isSuccess: Boolean) {
        SUCCESS(true),
        EXTERNAL(true),
        CANCELLED(false),
        ERROR(false),
    }

    internal sealed interface CompletionRoute {
        data object Internal : CompletionRoute
        data class External(val location: String) : CompletionRoute
        data object Invalid : CompletionRoute
    }

    companion object {
        private val pluginRedirects = ConcurrentHashMap<Long, (String) -> Unit>()
        const val INTENT_PARAM_URI = "REQ_URI"
        const val RESULT_COMPLETION = "OAUTH_COMPLETION"

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
        private const val QUERY_PARAM_INTEGRATION_TYPE = "integration_type"
        private const val QUERY_PARAM_GUILD_ID = "guild_id"
        private const val QUERY_PARAM_DISABLE_GUILD_SELECT = "disable_guild_select"
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

        internal fun classifyCompletion(request: OAuth2Authorize, location: String): CompletionRoute {
            val response = runCatching { URI(location) }.getOrNull()
                ?: return CompletionRoute.Invalid
            if (response.scheme == null || response.isOpaque && response.schemeSpecificPart.isNullOrBlank()) {
                return CompletionRoute.Invalid
            }

            val redirect = request.redirectUrl?.let { runCatching { URI(it) }.getOrNull() }
            if (redirect != null) {
                if (!sameCallback(redirect, response) || !UriHandler.isSafeScheme(location)) {
                    return CompletionRoute.Invalid
                }
                return if (DeepLinkParser.parse(location) is LinkParseResult.Internal) {
                    CompletionRoute.Internal
                } else {
                    CompletionRoute.External(location)
                }
            }

            return if (DeepLinkParser.parse(location) is LinkParseResult.Internal) {
                CompletionRoute.Internal
            } else {
                CompletionRoute.Invalid
            }
        }

        internal fun isSafeRedirect(location: String): Boolean =
            runCatching { URI(location) }.getOrNull()?.scheme != null && UriHandler.isSafeScheme(location)

        private fun sameCallback(expected: URI, actual: URI): Boolean =
            expected.scheme.equals(actual.scheme, ignoreCase = true) &&
                expected.host.equals(actual.host, ignoreCase = true) &&
                expected.port == actual.port &&
                expected.path.orEmpty() == actual.path.orEmpty() &&
                expected.userInfo == null && actual.userInfo == null

        internal fun completionResult(value: String?): CompletionResult =
            CompletionResult.entries.firstOrNull { it.name == value } ?: CompletionResult.CANCELLED

        internal fun shouldAppendFakeScope(permissionCount: Int): Boolean = permissionCount > 0

        /**
         * Parses the request URL.
         *
         * @throws IllegalArgumentException naming the first required parameter that is absent.
         *   Modern application installs may omit `scope` when `integration_type` selects the
         *   application's server-provided integration configuration.
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
        fun parse(parameters: Map<String, String?>): OAuth2Authorize {
            val rawIntegrationType = parameters[QUERY_PARAM_INTEGRATION_TYPE]
            val integrationType = rawIntegrationType?.toIntOrNull()
            if (rawIntegrationType != null && integrationType !in setOf(0, 1)) {
                throw IllegalArgumentException(QUERY_PARAM_INTEGRATION_TYPE)
            }
            val scope = parameters[QUERY_PARAM_SCOPE]?.takeIf(String::isNotBlank)
                ?: if (integrationType == null) throw IllegalArgumentException(QUERY_PARAM_SCOPE) else null
            return OAuth2Authorize(
                clientId = parameters.require(QUERY_PARAM_CLIENT_ID).toLong(),
                state = parameters[QUERY_PARAM_STATE],
                responseType = parameters[QUERY_PARAM_RESPONSE_TYPE],
                redirectUrl = parameters[QUERY_PARAM_REDIRECT],
                scope = scope,
                permissions = parameters[QUERY_PARAM_PERMISSIONS],
                codeChallenge = parameters[QUERY_PARAM_CODE_CHALLENGE],
                codeChallengeMethod = parameters[QUERY_PARAM_CODE_CHALLENGE_METHOD],
                integrationType = integrationType,
                guildId = parameters[QUERY_PARAM_GUILD_ID]?.toLongOrNull(),
                disableGuildSelect = parameters[QUERY_PARAM_DISABLE_GUILD_SELECT].toBoolean(),
                internalReferrer = parameters[QUERY_INTERNAL_REFERRER],
            )
        }

        private fun Map<String, String?>.require(name: String): String =
            get(name) ?: throw IllegalArgumentException(name)

        /**
         * The application a bare install link (`client_id` and nothing that picks scopes, an
         * integration type or a redirect) asks to add, or null for an ordinary authorization.
         * Discord answers such links from the application's own install settings.
         */
        fun installApplicationId(parameters: Map<String, String?>): Long? =
            parameters[QUERY_PARAM_CLIENT_ID]?.toLongOrNull()?.takeIf {
                parameters[QUERY_PARAM_SCOPE].isNullOrBlank() &&
                    parameters[QUERY_PARAM_INTEGRATION_TYPE] == null &&
                    parameters[QUERY_PARAM_REDIRECT] == null
            }

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

        fun launchForPlugin(context: Context, requestUri: Uri, onRedirect: (String) -> Unit) {
            val internalRequestUri = requestUri.buildUpon()
                .appendQueryParameter(QUERY_INTERNAL_REFERRER, true.toString())
                .build()
            val request = try {
                parse(internalRequestUri)
            } catch (invalid: Exception) {
                handleError(context, invalid)
                return
            }
            require(pluginRedirects.putIfAbsent(request.clientId, onRedirect) == null) {
                "OAuth is already pending for client ${request.clientId}"
            }
            context.startActivity(createLaunchIntent(context, internalRequestUri, request))
        }

        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            requestUri: Uri,
        ) {
            val request = try {
                parse(requestUri)
            } catch (invalid: Exception) {
                handleError(context, invalid)
                return
            }
            launcher.launch(createLaunchIntent(context, requestUri, request))
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
