package com.discord.utilities.rest

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Process
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.persister.Persister
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Which server the app talks to: Discord, or a compatible backend such as a Spacebar instance.
 *
 * The backend is fixed for the life of the process. Every REST client, the Gateway and the CDN URL
 * builders read [active] once, so a change only takes effect after a restart, and it can only be
 * made while signed out: a session token belongs to the backend that issued it and must never be
 * presented to another one. Each stored account remembers its backend, and switching to an account
 * on a different backend restarts onto that backend first.
 *
 * Kept in the session-durable preferences, outside the cloud settings backup (which lives on the
 * backend itself) and safe from the sign-out wipe.
 */
object ClientBackend {
    /**
     * @param api REST base without the version segment, ending in `/` (`https://discord.com/api/`).
     * @param cdn Origin of user content (avatars, icons, emoji, attachments).
     * @param media Origin of the resizing media proxy; backends without one reuse [cdn].
     * @param gateway Fallback Gateway URL; the live one comes from `GET /gateway`.
     * @param web Origin of the web client, used for links the app shares.
     */
    data class Backend(
        val api: String,
        val cdn: String,
        val media: String,
        val gateway: String,
        val web: String,
    ) {
        /** Every endpoint must be TLS and carry no credentials, or the backend is refused. */
        fun isValid(): Boolean = listOf(api, cdn, media, web).all { isSecure(it, "https") } && isSecure(gateway, "wss")

        /** Bare host of [cdn], for allowlists that match hosts rather than URLs. */
        val cdnHost: String get() = URI(cdn).host
        val mediaHost: String get() = URI(media).host
        val webHost: String get() = URI(web).host
    }

    val DISCORD = Backend(
        api = "https://discord.com/api/",
        cdn = "https://cdn.discordapp.com",
        media = "https://media.discordapp.net",
        gateway = "wss://gateway.discord.gg",
        web = "https://discord.com",
    )

    private const val CACHE_KEY = "CACHE_KEY_CLIENT_BACKEND"
    private const val CACHE_KEY_PENDING_SWITCH = "CACHE_KEY_CLIENT_BACKEND_PENDING_SWITCH"
    private val gson = Gson()

    @Volatile private var resolved: Backend? = null

    /**
     * The backend this process runs against. Not latched until preferences exist, so an early
     * caller cannot pin the process to Discord while the user's saved backend is still unread.
     */
    val active: Backend
        get() = resolved ?: if (SharedPreferencesProvider.getOrNull() == null) DISCORD else (stored() ?: DISCORD).also { resolved = it }

    val isDiscord: Boolean get() = active == DISCORD

    /** Prefix of shareable invite links: Discord's short domain, or the backend's own `/invite`. */
    val inviteBase: String get() = if (isDiscord) "https://discord.gg" else "${active.web}/invite"

    val giftBase: String get() = if (isDiscord) "https://discord.gift" else "${active.web}/gifts"

    /** The saved custom backend, or `null` for Discord. */
    fun stored(): Backend? = SharedPreferencesProvider.getOrNull()
        ?.let { SharedPreferencesProvider.getPrefsSessionDurable().getString(CACHE_KEY, null) }
        ?.let { runCatching { gson.fromJson(it, Backend::class.java) }.getOrNull() }
        ?.takeIf { it.isValid() && it != DISCORD }

    /**
     * Saves the backend for the next start; `null` means Discord. Committed synchronously because
     * the caller restarts the process straight after.
     *
     * [switchToUserId] is the stored account to sign into once the new backend is up.
     */
    @SuppressLint("ApplySharedPref")
    fun save(backend: Backend?, switchToUserId: Long? = null) {
        require(backend == null || backend.isValid()) { "Invalid backend" }
        SharedPreferencesProvider.getPrefsSessionDurable().edit().apply {
            if (backend == null || backend == DISCORD) remove(CACHE_KEY) else putString(CACHE_KEY, gson.toJson(backend))
            if (switchToUserId == null) remove(CACHE_KEY_PENDING_SWITCH) else putLong(CACHE_KEY_PENDING_SWITCH, switchToUserId)
        }.commit()
        // The cached Gateway URL came from the old backend.
        SharedPreferencesProvider.get().edit().remove(com.discord.gateway.GatewayDiscovery.GATEWAY_URL_CACHE_KEY).commit()
    }

    /** The backend to remember on an account signed in now; `null` stands for Discord. */
    val accountTag: Backend? get() = active.takeUnless { it == DISCORD }

    fun hasPendingSwitch(): Boolean = SharedPreferencesProvider.getPrefsSessionDurable().contains(CACHE_KEY_PENDING_SWITCH)

    /** The account a cross-backend switch was heading for, consumed on read. */
    fun takePendingSwitch(): Long? {
        val prefs = SharedPreferencesProvider.getPrefsSessionDurable()
        if (!prefs.contains(CACHE_KEY_PENDING_SWITCH)) return null
        return prefs.getLong(CACHE_KEY_PENDING_SWITCH, 0L).also { prefs.edit().remove(CACHE_KEY_PENDING_SWITCH).apply() }
    }

    /**
     * Starts the app again in a new process, the only way to rebuild every client on another
     * backend. Pending writes are flushed first, since the process is killed straight after.
     */
    @SuppressLint("ApplySharedPref")
    fun restartProcess(context: Context) {
        Persister.persistAll()
        SharedPreferencesProvider.get().edit().commit()
        SharedPreferencesProvider.getPrefsSessionDurable().edit().commit()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Process.killProcess(Process.myPid())
    }

    /**
     * Resolves an instance address the way Spacebar clients do: `/.well-known/spacebar` names the
     * API, and the API's `policies/instance/domains` names the CDN and Gateway. An instance without
     * the well-known document is assumed to serve its API at `/api`. Blocking; call off the main
     * thread.
     */
    fun discover(address: String, client: OkHttpClient = discoveryClient): Backend {
        val origin = normalizeOrigin(address)
        val wellKnownApi = runCatching { fetchJson(client, "$origin/.well-known/spacebar")["api"]?.asString }.getOrNull()
        val apiRoot = (wellKnownApi ?: "$origin/api").trimEnd('/')
        val domains = fetchJson(client, "$apiRoot/policies/instance/domains")
        val api = (domains["apiEndpoint"]?.asString ?: apiRoot).trimEnd('/') + "/"
        val cdn = domains["cdn"]?.asString?.trimEnd('/') ?: error("No CDN")
        val gateway = domains["gateway"]?.asString?.trimEnd('/') ?: error("No gateway")
        return Backend(api = api, cdn = cdn, media = cdn, gateway = gateway, web = origin)
            .also { require(it.isValid()) { "Instance endpoints must use https and wss" } }
    }

    internal fun normalizeOrigin(address: String): String {
        val trimmed = address.trim().trimEnd('/')
        val uri = URI(if ("://" in trimmed) trimmed else "https://$trimmed")
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.userInfo == null) {
            "Instance must be an https address"
        }
        return "https://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
    }

    private fun fetchJson(client: OkHttpClient, url: String): JsonObject =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} from $url" }
            JsonParser.parseString(response.body.string()).asJsonObject
        }

    private val discoveryClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    private fun isSecure(url: String, scheme: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals(scheme, ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.userInfo == null
    }.getOrDefault(false)
}
