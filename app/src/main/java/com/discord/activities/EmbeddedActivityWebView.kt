package com.discord.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthorization
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthUser
import com.discord.app.AppLog
import com.discord.rpc.RpcCloseCodes
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.utilities.analytics.AnalyticsUtils
import com.discord.rpc.RpcServer
import com.discord.rpc.RpcServerConfig
import com.discord.rpc.RpcServerManager
import com.discord.rpc.RpcSocket
import com.discord.rpc.RpcSocketSource
import com.discord.rpc.RpcTransportTypes
import com.discord.rpc.commands.RpcActivityHost
import com.discord.rpc.commands.RpcAuthHost
import com.discord.rpc.commands.rpcActivityHost
import com.discord.rpc.commands.rpcAuthHost
import com.discord.rpc.transport.PostMessageTransport
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import rx.Observable

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class EmbeddedActivityWebView(
    context: Context,
    private var session: EmbeddedActivitySession,
    private val onExternalUrl: (String, (Boolean) -> Unit) -> Unit,
    private val onPermissionRequest: (PermissionRequest) -> Unit,
    private val onAuthorizeRequest: (EmbeddedActivityAuthorizeRequest, () -> Unit, () -> Unit) -> Unit,
    private val onCrash: () -> Unit,
    private val onLoadError: (String) -> Unit,
    private val onOrientationLockState: (Int, Int?, Int?) -> Unit = { _, _, _ -> },
    private val onInviteDialog: (com.discord.api.channel.Channel) -> Unit = {},
    private val onShareLink: (String) -> Unit = {},
    private val onImageUpload: ((String?) -> Unit) -> Unit = { it(null) },
    private val onNavigateToConnections: () -> Unit = {},
) : WebView(context) {
    private val bridgeSecret = UUID.randomUUID().toString()
    private val activityOrigin = "https://${session.application.id}.discordsays.com"
    private val pageDirectory = File(context.cacheDir, "$PAGE_DIRECTORY/${session.frameId}")
    private val source = RpcSocketSource(RpcTransportTypes.POST_MESSAGE, activityOrigin, session.frameId)

    private val transport = PostMessageTransport(::validateSocketClient)
    private val server: RpcServer = RpcServerManager.create(
        RpcServerConfig(
            cdnHost = session.discordConfig.cdnHost,
            apiEndpoint = session.discordConfig.apiEndpoint,
            environment = session.discordConfig.environment,
        ),
        transport,
    )

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain("${session.application.id}.discordsays.com")
        .addPathHandler(
            "/${session.frameId}/",
            WebViewAssetLoader.InternalStoragePathHandler(context, pageDirectory),
        )
        .build()

    private var destroyed = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        overScrollMode = OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        WebView.setWebContentsDebuggingEnabled(false)
        addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webViewClient = ActivityClient()
        webChromeClient = ActivityChromeClient()

        // The activity draws edge to edge; insets reach it as CSS variables instead of padding, so
        // its own chrome moves without letterboxing the frame.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            updateSafeAreas(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        rpcActivityHost = ActivityHost()
        rpcAuthHost = AuthHost()
        server.onDisconnect = { _, code, message ->
            if (code != null && code != RpcCloseCodes.CLOSE_NORMAL) {
                AppLog.i(TAG, "activity socket closed: $code $message")
            }
        }
    }

    /**
     * Writes the wrapper page and points the WebView at it through [assetLoader].
     *
     * The page has to be served over https from the activity's own origin rather than handed to
     * `loadData`: the activity SDK needs a secure context for `crypto.subtle`, and the wrapper has
     * to share an origin with the frame to read `contentWindow`.
     */
    fun loadActivity() {
        pageDirectory.mkdirs()
        File(pageDirectory, PAGE_FILE).writeText(wrapperPage())
        transport.handleFrameMount(session.frameId)
        loadUrl("$activityOrigin/${session.frameId}/$PAGE_FILE")
    }

    fun updateInstance(instance: com.discord.api.embeddedactivities.EmbeddedActivityInstance) {
        session = session.copy(instance = instance)
        server.updateSubscriptions()
    }

    /** Re-runs every subscription's updater; the store layer has no push channel into the server. */
    fun onStoresChanged() {
        if (!destroyed) server.updateSubscriptions()
    }

    fun destroySafely() {
        if (destroyed) return
        destroyed = true
        transport.handleFrameUnmount(session.frameId)
        if (rpcActivityHost is ActivityHost) rpcActivityHost = null
        if (rpcAuthHost is AuthHost) rpcAuthHost = null
        stopLoading()
        removeJavascriptInterface(BRIDGE_NAME)
        (parent as? ViewGroup)?.removeView(this)
        pageDirectory.deleteRecursively()
        destroy()
    }

    private fun validateSocketClient(
        socket: RpcSocket,
        origin: String?,
        clientId: String?,
    ): Observable<Unit> {
        if (clientId == null) {
            return Observable.error(
                RpcError.close(RpcCloseCodes.INVALID_CLIENTID, "No Client ID Specified"),
            )
        }
        // An activity's origin is derived from its application id, so this is the whole allowlist.
        if (origin != null && origin != "https://$clientId.discordsays.com") {
            return Observable.error(RpcError.close(RpcCloseCodes.INVALID_ORIGIN, "Invalid Origin"))
        }
        val applicationId = clientId.toLongOrNull()
            ?: return Observable.error(RpcError.close(RpcCloseCodes.INVALID_CLIENTID, "Invalid Client ID"))
        socket.application.id = applicationId
        socket.application.name = session.application.name
        socket.application.icon = session.application.icon
        return Observable.just(Unit)
    }

    private fun wrapperPage(): String {
        val sandbox = "allow-pointer-lock allow-scripts allow-same-origin allow-forms"
        return """
            <html>
            <head>
            <style>body{padding:0;margin:0;width:100vw;min-height:100vh;min-height:-webkit-fill-available}</style>
            <meta name="viewport" content="width=device-width, height=device-height, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no, viewport-fit=cover"/>
            </head>
            <body>
            <script type="text/javascript">
              // The reference forwards every message it receives; only the activity frame may
              // reach the RPC server, so the sender is checked before anything crosses the bridge.
              window.addEventListener('message', function (e) {
                var f = document.getElementById('$FRAME_ELEMENT_ID');
                if (f == null || e.source !== f.contentWindow) return;
                if (e.origin !== ${JSONObject.quote(activityOrigin)}) return;
                if (!Array.isArray(e.data)) return;
                $BRIDGE_NAME.postMessage(${JSONObject.quote(bridgeSecret)}, JSON.stringify(e.data));
              });
            </script>
            <iframe id="$FRAME_ELEMENT_ID" width="100%" height="100%" src=${JSONObject.quote(session.url)} frameborder="0" allow="autoplay; encrypted-media" allowfullscreen sandbox="$sandbox" referrerPolicy="origin"></iframe>
            <script type="text/javascript">
              var iframeWindow = document.getElementById('$FRAME_ELEMENT_ID').contentWindow;
              var isIframeLoaded = false;
              function updateSafeAreaVars(insets) {
                var d = iframeWindow.document.documentElement;
                d.style.setProperty('--discord-safe-area-inset-left', insets.left + 'px');
                d.style.setProperty('--discord-safe-area-inset-right', insets.right + 'px');
                d.style.setProperty('--discord-safe-area-inset-top', insets.top + 'px');
                d.style.setProperty('--discord-safe-area-inset-bottom', insets.bottom + 'px');
                iframeWindow.dispatchEvent(new Event('resize'));
                iframeWindow.document.documentElement.offsetHeight;
              }
              iframeWindow.addEventListener('load', function () { isIframeLoaded = true; });
              iframeWindow.addEventListener('message', function (e) {
                var messageData = e.data;
                if (messageData == null || messageData.type !== 'safeAreaUpdateEvent') return;
                var insets = messageData.data.insets;
                if (isIframeLoaded) {
                  updateSafeAreaVars(insets);
                } else {
                  iframeWindow.addEventListener('load', function () { updateSafeAreaVars(insets); });
                }
              });
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * The top inset the frame is told about is the header height, not the system inset: the header
     * is what actually covers the activity, and the status bar is hidden while one is running.
     */
    private fun updateSafeAreas(left: Int, top: Int, right: Int, bottom: Int) {
        val density = resources.displayMetrics.density
        postToFrame(
            JSONObject()
                .put("type", "safeAreaUpdateEvent")
                .put(
                    "data",
                    JSONObject().put(
                        "insets",
                        JSONObject()
                            .put("left", (left / density).toInt())
                            .put("top", HEADER_HEIGHT_DP)
                            .put("right", (right / density).toInt())
                            .put("bottom", (bottom / density).toInt()),
                    ),
                ),
        )
    }

    private fun postToFrame(message: Any) {
        if (destroyed) return
        post {
            if (destroyed) return@post
            evaluateJavascript(
                "(function(){var f=document.getElementById('$FRAME_ELEMENT_ID');" +
                    "if(f)f.contentWindow.postMessage($message, ${JSONObject.quote(activityOrigin)});})()",
                null,
            )
        }
    }

    private fun postToRpcClient(tuple: JSONArray, targetOrigin: String) {
        if (destroyed) return
        post {
            if (destroyed) return@post
            evaluateJavascript(
                "(function(){var f=document.getElementById('$FRAME_ELEMENT_ID');" +
                    "if(f)f.contentWindow.postMessage($tuple, ${JSONObject.quote(targetOrigin)});})()",
                null,
            )
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(secret: String, payload: String) {
            if (!acceptsBridgeMessage(secret, bridgeSecret, payload, destroyed)) return
            post {
                if (destroyed) return@post
                runCatching {
                    transport.handleMessage(JSONTokener(payload).nextValue(), source, ::postToRpcClient)
                }
            }
        }
    }

    private inner class ActivityHost : RpcActivityHost {
        override fun connectedParticipantIds() = session.instance.participants.map { it.userId }

        override fun participantNickname(userId: Long): String? =
            StoreStream.getGuilds().getMember(session.channel.guildId, userId)?.nick

        override fun setOrientationLockState(lockState: Int, pictureInPicture: Int?, grid: Int?) =
            onOrientationLockState(lockState, pictureInPicture, grid)

        override fun setUseInteractivePip(enabled: Boolean) = Unit

        override fun openExternalLink(url: String): Observable<Boolean> =
            Observable.create { subscriber ->
                onExternalUrl(url) { opened ->
                    subscriber.onNext(opened)
                    subscriber.onCompleted()
                }
            }

        override fun refreshProxyTicket(): Observable<String> =
            EmbeddedActivitySessionManager.refreshProxyTicket(session)

        override fun openInviteDialog() = onInviteDialog(session.channel)

        override fun shareLink(message: String, customId: String?, linkId: String?): Observable<Boolean> =
            Observable.fromCallable {
                val link = buildString {
                    append("https://discord.com/activities/${session.application.id}")
                    customId?.let { append("?custom_id=$it") }
                }
                onShareLink("$message $link")
                true
            }

        override fun navigateToConnections() = onNavigateToConnections()

        override fun initiateImageUpload(): Observable<String> =
            Observable.create { subscriber ->
                onImageUpload { url ->
                    if (url == null) {
                        subscriber.onError(RpcError.error(RpcErrors.UNKNOWN_ERROR, "No image selected"))
                    } else {
                        subscriber.onNext(url)
                        subscriber.onCompleted()
                    }
                }
            }

        override fun captureLog(level: String, message: String) {
            AppLog.i(TAG, "${session.application.id} - $message")
        }

        override fun sendAnalyticsEvent(name: String, properties: JSONObject) {
            val values = properties.keys().asSequence()
                .mapNotNull { key -> properties.opt(key)?.let { key to it } }
                .toMap(mutableMapOf<String, Any>())
            values["activity_application_id"] = session.application.id
            values["activity_channel_type"] = session.channel.type
            session.channel.guildId.takeIf { it > 0L }?.let { values["activity_guild_id"] = it }
            AnalyticsUtils.Tracker.instance.track(name, values)
        }
    }

    private inner class AuthHost : RpcAuthHost {
        override fun authorize(socket: RpcSocket, args: JSONObject): Observable<String> {
            val request = runCatching { parseAuthorizeRequest(args) }.getOrElse { failure ->
                return Observable.error(
                    failure as? EmbeddedActivityOAuthException ?: EmbeddedActivityOAuthException.InvalidRequest(),
                )
            }
            val client = oauthClient()
                ?: return Observable.error(EmbeddedActivityOAuthException.AuthorizationFailed())
            return client.requiresConsent(request).flatMap { needsConsent ->
                if (!needsConsent) client.authorize(request) else promptThenAuthorize(client, request)
            }
        }

        /** Consent is a native prompt, so the grant only reaches the API once the user agrees. */
        private fun promptThenAuthorize(
            client: EmbeddedActivityOAuthClient,
            request: EmbeddedActivityAuthorizeRequest,
        ): Observable<String> = Observable.create { subscriber ->
            onAuthorizeRequest(
                request,
                {
                    client.authorize(request).subscribe(
                        { code ->
                            subscriber.onNext(code)
                            subscriber.onCompleted()
                        },
                        subscriber::onError,
                    )
                },
                {
                    subscriber.onError(
                        RpcError.error(RpcErrors.OAUTH2_ERROR, "User cancelled authorization"),
                    )
                },
            )
        }

        override fun authenticate(socket: RpcSocket, accessToken: String): Observable<JSONObject> {
            val client = oauthClient()
                ?: return Observable.error(EmbeddedActivityOAuthException.AuthenticationFailed())
            return client.authenticate(accessToken).map { authorizationJson(it.authorization) }
        }
    }

    private fun oauthClient(): EmbeddedActivityOAuthClient? {
        val authorizeApi = RestAPI.embeddedActivityOAuthApiOrNull ?: return null
        val tokenApi = RestAPI.embeddedActivityOAuthTokenApiOrNull ?: return null
        return EmbeddedActivityOAuthClient(
            authorizeApi,
            tokenApi,
            session.application.id,
            session.channel,
            StoreStream.getUsers().getMe().id,
        )
    }

    private fun parseAuthorizeRequest(args: JSONObject): EmbeddedActivityAuthorizeRequest {
        val responseType = args.optString("response_type").ifBlank { "code" }
        if (responseType != "code") throw EmbeddedActivityOAuthException.InvalidRequest()
        val clientId = args.optString("client_id").toLongOrNull()
            ?: throw EmbeddedActivityOAuthException.InvalidClient()
        val scopesValue = if (args.has("scopes")) args.opt("scopes") else args.opt("scope")
        val scopes = when (scopesValue) {
            is JSONArray -> List(scopesValue.length()) { index -> scopesValue.optString(index) }
            is String -> scopesValue.split(' ').filter(String::isNotBlank)
            else -> emptyList()
        }
        val state = if (!args.has("state") || args.isNull("state")) {
            null
        } else {
            args.opt("state") as? String ?: throw EmbeddedActivityOAuthException.InvalidRequest()
        }
        return EmbeddedActivityAuthorizeRequest(
            clientId,
            scopes,
            state,
            args.optString("code_challenge").takeIf(String::isNotBlank),
            args.optString("code_challenge_method").takeIf(String::isNotBlank),
            args.optString("prompt").takeIf(String::isNotBlank),
        )
    }

    private fun authorizationJson(authorization: EmbeddedActivityOAuthAuthorization): JSONObject = JSONObject()
        .put(
            "application",
            JSONObject()
                .put("id", authorization.application.id.toString())
                .put("name", authorization.application.name)
                .put("description", authorization.application.description)
                .put("icon", authorization.application.icon ?: JSONObject.NULL)
                .apply {
                    authorization.application.rpcOrigins?.let { put("rpc_origins", JSONArray(it)) }
                },
        )
        .put("scopes", JSONArray(authorization.scopes))
        .put("expires", authorization.expires)
        .put("user", authorization.user?.let(::oauthUserJson) ?: JSONObject.NULL)

    private fun oauthUserJson(user: EmbeddedActivityOAuthUser) = JSONObject()
        .put("id", user.id.toString())
        .put("username", user.username)
        .put("discriminator", user.discriminator)
        .put("avatar", user.avatar ?: JSONObject.NULL)
        .put("global_name", user.globalName ?: JSONObject.NULL)
        .put("public_flags", user.publicFlags)

    private inner class ActivityClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (url.startsWith(activityOrigin)) return false
            onExternalUrl(url) {}
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onLoadError(error.description?.toString().orEmpty())
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onCrash()
            return true
        }
    }

    private inner class ActivityChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            if (request.origin.toString().removeSuffix("/") != activityOrigin) {
                request.deny()
                return
            }
            val supported = request.resources.all {
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            if (!supported) {
                request.deny()
                return
            }
            val alreadyGranted = request.resources.all {
                val permission = if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                    Manifest.permission.RECORD_AUDIO
                } else {
                    Manifest.permission.CAMERA
                }
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
            if (alreadyGranted) request.grant(request.resources) else onPermissionRequest(request)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message,
        ) = false
    }

    internal companion object {
        const val BRIDGE_NAME = "DiscordNative"
        const val FRAME_ELEMENT_ID = "activityFrame"
        const val PAGE_DIRECTORY = "discord_activity_data"
        const val PAGE_FILE = "activity.html"
        const val MAX_BRIDGE_MESSAGE_LENGTH = 64 * 1024

        /** Must track `activity_chrome`'s height; it is what the frame reserves at the top. */
        const val HEADER_HEIGHT_DP = 48
        private const val TAG = "EmbeddedActivityWebView"

        fun acceptsBridgeMessage(secret: String, expected: String, payload: String, destroyed: Boolean) =
            !destroyed && secret == expected && payload.length <= MAX_BRIDGE_MESSAGE_LENGTH

        fun shouldDeliverAsyncResult(destroyed: Boolean) = !destroyed
    }
}
