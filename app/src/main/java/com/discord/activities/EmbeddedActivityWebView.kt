package com.discord.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import com.discord.api.embeddedactivities.EmbeddedActivityDiscordConfig
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthorization
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthUser
import com.discord.BuildConfig
import com.discord.app.AppLog
import com.discord.rpc.RpcCloseCodes
import com.discord.rpc.RpcError
import com.discord.rpc.RpcLog
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
import rx.schedulers.Schedulers

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class EmbeddedActivityWebView(
    context: Context,
    private val applicationId: Long,
    private val frameId: String,
    discordConfig: EmbeddedActivityDiscordConfig,
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
    private val activityOrigin = "https://$applicationId.discordsays.com"
    private val pageDirectory = File(context.cacheDir, "$PAGE_DIRECTORY/$frameId")
    private val source = RpcSocketSource(RpcTransportTypes.POST_MESSAGE, activityOrigin, frameId)

    private val transport = PostMessageTransport(::validateSocketClient)
    private val server: RpcServer = RpcServerManager.create(
        RpcServerConfig(
            cdnHost = discordConfig.cdnHost,
            apiEndpoint = discordConfig.apiEndpoint,
            environment = discordConfig.environment,
        ),
        transport,
    )

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain("$applicationId.discordsays.com")
        .addPathHandler(
            "/$frameId/",
            WebViewAssetLoader.InternalStoragePathHandler(context, pageDirectory),
        )
        .build()

    private var destroyed = false
    private var landscape = false
    private var session: EmbeddedActivitySession? = null
    private var shellLoaded = false
    private var pendingFrameUrl: String? = null

    private val activeSession get() = checkNotNull(session) { "no activity session" }

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
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        RpcLog.enabled = BuildConfig.DEBUG
        addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webViewClient = ActivityClient()
        webChromeClient = ActivityChromeClient()

        // Insets go in as CSS variables rather than padding, so the frame is never letterboxed.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            updateSafeAreas(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rpcActivityHost = ActivityHost()
        rpcAuthHost = AuthHost()
        server.onDisconnect = { _, code, message ->
            if (code != null && code != RpcCloseCodes.CLOSE_NORMAL) {
                AppLog.i(TAG, "activity socket closed: $code $message")
            }
        }
    }

    /**
     * The wrapper page is served over https from the activity's own origin rather than handed to
     * `loadData`: the SDK needs a secure context for `crypto.subtle`, and the wrapper has to share
     * an origin with the frame to read `contentWindow`.
     */
    fun loadShell() {
        pageDirectory.mkdirs()
        File(pageDirectory, PAGE_FILE).writeText(wrapperPage())
        transport.handleFrameMount(frameId)
        loadUrl("$activityOrigin/$frameId/$PAGE_FILE")
    }

    /** The shell boots while the session is still being prepared; the frame follows once it is. */
    fun attachSession(value: EmbeddedActivitySession) {
        session = value
        if (shellLoaded) navigateFrame(value.url) else pendingFrameUrl = value.url
    }

    private fun navigateFrame(url: String) {
        evaluateJavascript(
            "document.getElementById('$FRAME_ELEMENT_ID').src = ${JSONObject.quote(url)};",
            null,
        )
    }

    fun updateInstance(instance: com.discord.api.embeddedactivities.EmbeddedActivityInstance) {
        session = session?.copy(instance = instance)
        server.updateSubscriptions()
    }

    /** Re-runs every subscription's updater; the store layer has no push channel into the server. */
    fun onStoresChanged() {
        if (!destroyed) server.updateSubscriptions()
    }

    fun destroySafely() {
        if (destroyed) return
        destroyed = true
        transport.handleFrameUnmount(frameId)
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
        if (origin != null && origin != "https://$clientId.discordsays.com") {
            return Observable.error(RpcError.close(RpcCloseCodes.INVALID_ORIGIN, "Invalid Origin"))
        }
        val applicationId = clientId.toLongOrNull()
            ?: return Observable.error(RpcError.close(RpcCloseCodes.INVALID_CLIENTID, "Invalid Client ID"))
        socket.application.id = applicationId
        socket.application.name = session?.application?.name
        socket.application.icon = session?.application?.icon
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
              // The reference forwards every message it receives; only the frame may reach the server.
              window.addEventListener('message', function (e) {
                var f = document.getElementById('$FRAME_ELEMENT_ID');
                if (f == null || e.source !== f.contentWindow) return;
                if (e.origin !== ${JSONObject.quote(activityOrigin)}) return;
                if (!Array.isArray(e.data)) return;
                $BRIDGE_NAME.postMessage(${JSONObject.quote(bridgeSecret)}, JSON.stringify(e.data));
              });
            </script>
            <iframe id="$FRAME_ELEMENT_ID" width="100%" height="100%" frameborder="0" allow="autoplay; encrypted-media" allowfullscreen sandbox="$sandbox" referrerPolicy="origin"></iframe>
            <script type="text/javascript">
              var frame = document.getElementById('$FRAME_ELEMENT_ID');
              var insets = ${initialInsets()};
              var isIframeLoaded = false;
              var boundWindow = null;
              function updateSafeAreaVars(value) {
                var d = frame.contentWindow.document.documentElement;
                d.style.setProperty('--discord-safe-area-inset-left', value.left + 'px');
                d.style.setProperty('--discord-safe-area-inset-right', value.right + 'px');
                d.style.setProperty('--discord-safe-area-inset-top', value.top + 'px');
                d.style.setProperty('--discord-safe-area-inset-bottom', value.bottom + 'px');
                frame.contentWindow.dispatchEvent(new Event('resize'));
                d.offsetHeight;
              }
              function onFrameMessage(e) {
                var messageData = e.data;
                if (messageData == null || messageData.type !== 'safeAreaUpdateEvent') return;
                insets = messageData.data.insets;
                if (isIframeLoaded) updateSafeAreaVars(insets);
              }
              // Rebound on every navigation: the frame gets a new window, and with it new listeners.
              function bindFrameWindow() {
                if (frame.contentWindow === boundWindow) return;
                boundWindow = frame.contentWindow;
                boundWindow.addEventListener('message', onFrameMessage);
              }
              bindFrameWindow();
              frame.addEventListener('load', function () {
                isIframeLoaded = true;
                bindFrameWindow();
                updateSafeAreaVars(insets);
                // Every load is a new SDK instance; a stale socket is refused as already connected.
                $BRIDGE_NAME.frameLoaded(${JSONObject.quote(bridgeSecret)});
              });
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun initialInsets(): JSONObject {
        val bars = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return safeAreas(bars?.left ?: 0, bars?.top ?: 0, bars?.right ?: 0, bars?.bottom ?: 0)
    }

    fun setLandscape(value: Boolean) {
        if (landscape == value) return
        landscape = value
        postToFrame(
            JSONObject()
                .put("type", "safeAreaUpdateEvent")
                .put("data", JSONObject().put("insets", initialInsets())),
        )
    }

    private fun updateSafeAreas(left: Int, top: Int, right: Int, bottom: Int) {
        postToFrame(
            JSONObject()
                .put("type", "safeAreaUpdateEvent")
                .put("data", JSONObject().put("insets", safeAreas(left, top, right, bottom))),
        )
    }

    /** Reports the chrome covering the frame, not the system bars alone. */
    private fun safeAreas(left: Int, top: Int, right: Int, bottom: Int): JSONObject {
        val density = resources.displayMetrics.density
        val rightDp = (right / density).toInt()
        val topDp = (top / density).toInt()
        return JSONObject()
            .put("left", (left / density).toInt())
            .put("top", if (landscape) topDp else topDp + HEADER_HEIGHT_DP)
            .put("right", if (landscape) maxOf(rightDp, LANDSCAPE_CHROME_WIDTH_DP) else rightDp)
            .put("bottom", (bottom / density).toInt())
    }

    private fun postToFrame(message: Any) = deliverToFrame(message.toString(), activityOrigin)

    private fun postToRpcClient(tuple: JSONArray, targetOrigin: String) =
        deliverToFrame(tuple.toString(), targetOrigin)

    private fun deliverToFrame(payload: String, targetOrigin: String) {
        if (destroyed) return
        post {
            if (destroyed) return@post
            evaluateJavascript(
                "(function(){var f=document.getElementById('$FRAME_ELEMENT_ID');" +
                    "if(f==null)return 'no-frame';" +
                    "if(f.contentWindow==null)return 'no-content-window';" +
                    "try{f.contentWindow.postMessage($payload, ${JSONObject.quote(targetOrigin)});" +
                    "return 'ok';}catch(e){return 'threw '+e;}})()",
            ) { result -> if (result != "\"ok\"") RpcLog.log("deliver failed: $result") }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun frameLoaded(secret: String) {
            if (secret != bridgeSecret || destroyed) return
            post {
                if (destroyed) return@post
                transport.handleFrameUnmount(frameId)
                transport.handleFrameMount(frameId)
            }
        }

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
        override fun connectedParticipantIds() = activeSession.instance.participants.map { it.userId }

        override fun participantNickname(userId: Long): String? =
            StoreStream.getGuilds().getMember(activeSession.channel.guildId, userId)?.nick

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
            EmbeddedActivitySessionManager.refreshProxyTicket(activeSession)

        override fun openInviteDialog() = onInviteDialog(activeSession.channel)

        override fun shareLink(message: String, customId: String?, linkId: String?): Observable<Boolean> =
            Observable.fromCallable {
                val link = buildString {
                    append("https://discord.com/activities/$applicationId")
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
            AppLog.i(TAG, "$applicationId - $message")
        }

        override fun sendAnalyticsEvent(name: String, properties: JSONObject) {
            val values = properties.keys().asSequence()
                .mapNotNull { key -> properties.opt(key)?.let { key to it } }
                .toMap(mutableMapOf<String, Any>())
            values["activity_application_id"] = applicationId
            session?.channel?.let { channel ->
                values["activity_channel_type"] = channel.type
                channel.guildId.takeIf { it > 0L }?.let { values["activity_guild_id"] = it }
            }
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
            }.onErrorResumeNext { failure -> Observable.error(asRpcError(failure)) }
        }

        /** The prompt answers on the main thread, so the grant needs its own scheduler. */
        private fun promptThenAuthorize(
            client: EmbeddedActivityOAuthClient,
            request: EmbeddedActivityAuthorizeRequest,
        ): Observable<String> = Observable.create { subscriber ->
            post {
                onAuthorizeRequest(
                    request,
                    {
                        client.authorize(request)
                            .subscribeOn(Schedulers.io())
                            .subscribe(
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
        }

        override fun authenticate(socket: RpcSocket, accessToken: String): Observable<JSONObject> {
            val client = oauthClient()
                ?: return Observable.error(EmbeddedActivityOAuthException.AuthenticationFailed())
            return client.authenticateRaw(accessToken)
                .map { JSONObject(it.toString()) }
                .onErrorResumeNext { failure -> Observable.error(asRpcError(failure)) }
        }
    }

    private fun asRpcError(failure: Throwable): Throwable = when (failure) {
        is EmbeddedActivityOAuthException.InvalidClient ->
            RpcError.error(RpcErrors.INVALID_CLIENTID, failure.message.orEmpty())
        is EmbeddedActivityOAuthException.InvalidRequest ->
            RpcError.error(RpcErrors.INVALID_PAYLOAD, failure.message.orEmpty())
        is EmbeddedActivityOAuthException.AuthorizationFailed ->
            RpcError.error(RpcErrors.OAUTH2_ERROR, failure.message.orEmpty())
        is EmbeddedActivityOAuthException.AuthenticationFailed ->
            RpcError.error(RpcErrors.INVALID_TOKEN, failure.message.orEmpty())
        else -> failure
    }

    private fun oauthClient(): EmbeddedActivityOAuthClient? {
        val authorizeApi = RestAPI.embeddedActivityOAuthApiOrNull ?: return null
        val tokenApi = RestAPI.embeddedActivityOAuthTokenApiOrNull ?: return null
        return EmbeddedActivityOAuthClient(
            authorizeApi,
            tokenApi,
            applicationId,
            activeSession.channel,
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

        override fun onPageFinished(view: WebView, url: String) {
            shellLoaded = true
            pendingFrameUrl?.let {
                pendingFrameUrl = null
                navigateFrame(it)
            }
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
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (RpcLog.enabled) {
                AppLog.i(TAG, "${message.sourceId()}:${message.lineNumber()} ${message.message()}")
            }
            return true
        }

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

        /** Must track `activity_chrome`'s height. */
        const val HEADER_HEIGHT_DP = 48
        private const val LANDSCAPE_CHROME_WIDTH_DP = 64
        private const val TAG = "EmbeddedActivityWebView"

        fun acceptsBridgeMessage(secret: String, expected: String, payload: String, destroyed: Boolean) =
            !destroyed && secret == expected && payload.length <= MAX_BRIDGE_MESSAGE_LENGTH

        /** JSON quoting escapes `/` as `\/`, which HTML takes literally and so corrupts the url. */
        fun escapeHtmlAttribute(value: String): String = value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        fun shouldDeliverAsyncResult(destroyed: Boolean) = !destroyed
    }
}
