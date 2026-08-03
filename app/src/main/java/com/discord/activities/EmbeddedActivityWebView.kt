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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthentication
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthorization
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthUser
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class EmbeddedActivityWebView(
    context: Context,
    private var session: EmbeddedActivitySession,
    private val onExternalUrl: (String, (Boolean) -> Unit) -> Unit,
    private val onPermissionRequest: (PermissionRequest) -> Unit,
    private val onCrash: () -> Unit,
    private val onLoadError: (String) -> Unit,
) : WebView(context) {
    private val bridgeSecret = UUID.randomUUID().toString()
    private val bridge = Bridge()
    private val subscriptions = linkedSetOf<String>()
    private val oauthSubscriptions = CompositeSubscription()
    private val pendingOAuth = linkedMapOf<String, Subscription>()
    private var bridgeClientId: Long? = null
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        WebView.setWebContentsDebuggingEnabled(false)
        addJavascriptInterface(bridge, BRIDGE_NAME)
        webViewClient = ActivityClient()
        webChromeClient = ActivityChromeClient()
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    fun loadActivity() {
        loadDataWithBaseURL(HOST_ORIGIN, hostPage(), "text/html", "utf-8", null)
    }

    fun updateInstance(instance: com.discord.api.embeddedactivities.EmbeddedActivityInstance) {
        session = session.copy(instance = instance)
        updateParticipants()
    }

    private fun updateParticipants() {
        if (PARTICIPANTS_EVENT !in subscriptions) return
        dispatch(
            PARTICIPANTS_EVENT,
            JSONObject().put(
                "participants",
                JSONArray().apply {
                    session.instance.participants.forEach { participant ->
                        val user = StoreStream.getUsers().getUsers()[participant.userId]
                        put(
                            JSONObject()
                                .put("id", participant.userId.toString())
                                .put("username", user?.username ?: "")
                                .put("flags", 0),
                        )
                    }
                },
            ),
        )
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
    }

    fun destroySafely() {
        if (destroyed) return
        destroyed = true
        oauthSubscriptions.unsubscribe()
        pendingOAuth.clear()
        stopLoading()
        removeJavascriptInterface(BRIDGE_NAME)
        (parent as? ViewGroup)?.removeView(this)
        destroy()
    }

    private fun hostPage(): String = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
        <meta name="referrer" content="origin">
        <style>html,body,iframe{width:100%;height:100%;margin:0;border:0;overflow:hidden;background:transparent}</style>
        </head><body><iframe id="activity" sandbox="allow-pointer-lock allow-scripts allow-same-origin allow-forms" allow="autoplay; encrypted-media; fullscreen; picture-in-picture; camera; microphone"></iframe>
        <script>(function(){
          var frame=document.getElementById('activity');
          var activityOrigin=${JSONObject.quote(activityOrigin())};
          window.addEventListener('message',function(event){
            if(event.source===frame.contentWindow&&event.origin===activityOrigin&&Array.isArray(event.data)){
              try{DiscordNative.postMessage(${JSONObject.quote(bridgeSecret)},JSON.stringify(event.data));}catch(e){}
            }
          });
          window.__hostDeliver=function(json){
            try{frame.contentWindow.postMessage(JSON.parse(json),activityOrigin);}catch(e){}
          };
          frame.referrerPolicy='origin';
          frame.src=${JSONObject.quote(session.url)};
        })();</script></body></html>
    """.trimIndent()

    private fun activityOrigin(): String = Uri.parse(session.url).let { "${it.scheme}://${it.authority}" }

    private fun deliver(tuple: JSONArray) {
        if (!shouldDeliverAsyncResult(destroyed)) return
        post {
            if (shouldDeliverAsyncResult(destroyed)) {
                evaluateJavascript("window.__hostDeliver(${JSONObject.quote(tuple.toString())});", null)
            }
        }
    }

    private fun reply(command: String, nonce: Any?, data: JSONObject) = deliver(
        JSONArray().put(1).put(
            JSONObject()
                .put("cmd", command)
                .put("evt", JSONObject.NULL)
                .put("nonce", nonce ?: JSONObject.NULL)
                .put("data", data),
        ),
    )

    private fun replyError(command: String, nonce: Any?, code: Int = 4002, message: String) = deliver(
        JSONArray().put(1).put(
            JSONObject()
                .put("cmd", command)
                .put("evt", "ERROR")
                .put("nonce", nonce ?: JSONObject.NULL)
                .put("data", JSONObject().put("code", code).put("message", message)),
        ),
    )

    private fun dispatch(event: String, data: JSONObject) = deliver(
        JSONArray().put(1).put(
            JSONObject()
                .put("cmd", "DISPATCH")
                .put("evt", event)
                .put("nonce", JSONObject.NULL)
                .put("data", data),
        ),
    )

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(secret: String, payload: String) {
            if (!acceptsBridgeMessage(secret, bridgeSecret, payload, destroyed)) return
            post { runCatching { handleMessage(JSONArray(payload)) } }
        }
    }

    private fun handleMessage(tuple: JSONArray) {
        when (tuple.optInt(0, -1)) {
            0 -> {
                bridgeClientId = tuple.optJSONObject(1)?.optString("client_id")?.toLongOrNull()
                ready()
            }
            1 -> handleCommand(tuple.optJSONObject(1) ?: return)
        }
    }

    private fun ready() {
        val me = StoreStream.getUsers().getMe()
        dispatch(
            "READY",
            JSONObject()
                .put("v", 1)
                .put(
                    "config",
                    JSONObject()
                        .put("cdn_host", session.discordConfig.cdnHost)
                        .put("api_endpoint", session.discordConfig.apiEndpoint)
                        .put("environment", session.discordConfig.environment),
                )
                .put(
                    "user",
                    JSONObject()
                        .put("id", me.id.toString())
                        .put("username", me.username)
                        .put("discriminator", me.discriminator.toString())
                        .put("avatar", me.avatar ?: JSONObject.NULL),
                ),
        )
    }

    private fun handleCommand(payload: JSONObject) {
        val command = payload.optString("cmd")
        val nonce = payload.opt("nonce")
        val event = payload.optString("evt").takeIf(String::isNotBlank)
        val args = payload.optJSONObject("args") ?: JSONObject()
        when (command) {
            "GET_CHANNEL" -> reply(
                command,
                nonce,
                JSONObject()
                    .put("id", session.channel.id.toString())
                    .put("guild_id", session.channel.guildId.takeIf { it > 0L }?.toString() ?: JSONObject.NULL)
                    .put("type", session.channel.type)
                    .put("name", session.channel.name ?: ""),
            )
            "GET_CHANNEL_PERMISSIONS" -> reply(
                command,
                nonce,
                JSONObject().put(
                    "permissions",
                    StoreStream.getPermissions().getPermissionsByChannel()[session.channel.id]?.toString() ?: "0",
                ),
            )
            "SUBSCRIBE" -> {
                if (event != null) subscriptions += event
                reply(command, nonce, JSONObject().put("evt", event))
                if (event == PARTICIPANTS_EVENT) updateParticipants()
            }
            "UNSUBSCRIBE" -> {
                if (event != null) subscriptions -= event
                reply(command, nonce, JSONObject().put("evt", event))
            }
            "OPEN_EXTERNAL_LINK" -> onExternalUrl(args.optString("url")) { opened ->
                reply(command, nonce, JSONObject().put("opened", opened))
            }
            "USER_SETTINGS_GET_LOCALE" -> reply(
                command,
                nonce,
                JSONObject().put("locale", Locale.getDefault().toLanguageTag()),
            )
            "ENCOURAGE_HW_ACCELERATION" -> reply(command, nonce, JSONObject().put("enabled", true))
            "SET_ORIENTATION_LOCK_STATE", "SET_ACTIVITY", "CAPTURE_LOG", "SEND_ANALYTICS_EVENT",
            "GET_PLATFORM_BEHAVIORS", "GET_QUEST" -> reply(command, nonce, JSONObject())
            "AUTHORIZE" -> authorize(command, nonce, args)
            "AUTHENTICATE" -> authenticate(command, nonce, args)
            else -> replyError(command, nonce, message = "Unsupported command")
        }
    }

    private fun authorize(command: String, nonce: Any?, args: JSONObject) {
        val request = runCatching { parseAuthorizeRequest(args) }.getOrElse { failure ->
            val error = failure as? EmbeddedActivityOAuthException
                ?: EmbeddedActivityOAuthException.InvalidRequest()
            replyError(command, nonce, error.rpcCode, error.message.orEmpty())
            return
        }
        val client = oauthClient() ?: run {
            replyError(command, nonce, 4011, "Authorization unavailable")
            return
        }
        subscribeOAuth(command, nonce, client.authorize(request)) { code ->
            JSONObject().put("code", code)
        }
    }

    private fun authenticate(command: String, nonce: Any?, args: JSONObject) {
        val token = args.optString("access_token")
        val client = oauthClient() ?: run {
            replyError(command, nonce, 4009, "Authentication unavailable")
            return
        }
        subscribeOAuth(command, nonce, client.authenticate(token), ::authenticationJson)
    }

    private fun oauthClient(): EmbeddedActivityOAuthClient? {
        val api = RestAPI.embeddedActivityOAuthApiOrNull ?: return null
        return EmbeddedActivityOAuthClient(
            api,
            session.application.id,
            session.channel,
            StoreStream.getUsers().getMe().id,
        )
    }

    private fun parseAuthorizeRequest(args: JSONObject): EmbeddedActivityAuthorizeRequest {
        val responseType = args.optString("response_type").ifBlank { "code" }
        if (responseType != "code") throw EmbeddedActivityOAuthException.InvalidRequest()
        val clientId = args.optString("client_id").toLongOrNull()
            ?: bridgeClientId
            ?: throw EmbeddedActivityOAuthException.InvalidClient()
        val scopesValue = when {
            args.has("scopes") -> args.opt("scopes")
            else -> args.opt("scope")
        }
        val scopes = when (scopesValue) {
            is JSONArray -> List(scopesValue.length()) { index -> scopesValue.optString(index) }
            is String -> scopesValue.split(' ').filter(String::isNotBlank)
            else -> emptyList()
        }
        val state = if (!args.has("state") || args.isNull("state")) null else {
            args.opt("state") as? String ?: throw EmbeddedActivityOAuthException.InvalidRequest()
        }
        return EmbeddedActivityAuthorizeRequest(clientId, scopes, state)
    }

    private fun <T> subscribeOAuth(
        command: String,
        nonce: Any?,
        request: Observable<T>,
        toJson: (T) -> JSONObject,
    ) {
        val key = nonce?.takeUnless { it === JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)
        if (key == null || pendingOAuth.containsKey(key)) {
            replyError(command, nonce, message = "Invalid request nonce")
            return
        }
        val subscription = request
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result ->
                pendingOAuth.remove(key)
                if (shouldDeliverAsyncResult(destroyed)) reply(command, nonce, toJson(result))
            }, { failure ->
                pendingOAuth.remove(key)
                if (shouldDeliverAsyncResult(destroyed)) {
                    val error = failure as? EmbeddedActivityOAuthException
                    replyError(
                        command,
                        nonce,
                        error?.rpcCode ?: if (command == "AUTHORIZE") 4011 else 4009,
                        error?.message ?: if (command == "AUTHORIZE") "Authorization failed" else "Authentication failed",
                    )
                }
            })
        pendingOAuth[key] = subscription
        oauthSubscriptions.add(subscription)
    }

    private fun authenticationJson(authentication: EmbeddedActivityOAuthAuthentication): JSONObject =
        authorizationJson(authentication.authorization).put("access_token", authentication.accessToken)

    private fun authorizationJson(authorization: EmbeddedActivityOAuthAuthorization): JSONObject = JSONObject()
        .put(
            "application",
            JSONObject()
                .put("id", authorization.application.id.toString())
                .put("name", authorization.application.name)
                .put("icon", authorization.application.icon ?: JSONObject.NULL),
        )
        .put("scopes", JSONArray(authorization.scopes))
        .put("expires", authorization.expires)
        .put("user", authorization.user?.let(::userJson) ?: JSONObject.NULL)

    private fun userJson(user: EmbeddedActivityOAuthUser) = JSONObject()
        .put("id", user.id.toString())
        .put("username", user.username)
        .put("discriminator", user.discriminator)
        .put("avatar", user.avatar ?: JSONObject.NULL)
        .put("global_name", user.globalName ?: JSONObject.NULL)

    private inner class ActivityClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (url.startsWith(HOST_ORIGIN)) return false
            onExternalUrl(url) {}
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.url.host == Uri.parse(session.url).host) onLoadError(error.description?.toString().orEmpty())
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onCrash()
            return true
        }
    }

    private inner class ActivityChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            if (request.origin.toString().removeSuffix("/") != activityOrigin()) {
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
        const val HOST_ORIGIN = "https://discord.com/"
        const val PARTICIPANTS_EVENT = "ACTIVITY_INSTANCE_PARTICIPANTS_UPDATE"
        const val MAX_BRIDGE_MESSAGE_LENGTH = 64 * 1024

        fun acceptsBridgeMessage(secret: String, expected: String, payload: String, destroyed: Boolean) =
            !destroyed && secret == expected && payload.length <= MAX_BRIDGE_MESSAGE_LENGTH

        fun shouldDeliverAsyncResult(destroyed: Boolean) = !destroyed
    }
}
