package com.discord.utilities.analytics

import android.os.Build
import com.discord.utilities.cache.SharedPreferencesProvider
import com.google.gson.GsonBuilder
import java.util.Locale
import java.util.UUID
import okio.ByteString.Companion.encodeUtf8

/**
 * Client identity sent as `X-Super-Properties` and on the Gateway `identify` payload.
 *
 * Key order matches the reference's `getSuperProperties`, which the backend fingerprints.
 */
object AnalyticSuperProperties {

    const val CLIENT_BUILD_NUMBER = 6081
    const val CLIENT_VERSION = "341.0 - rn"
    const val VERSION_CODE = 341200
    const val USER_AGENT = "Discord-Android/$VERSION_CODE;RNA"

    val superProperties: Map<String, Any?>
        get() = linkedMapOf(
            "os" to "Android",
            "browser" to "Discord Android",
            "device" to Build.DEVICE.orEmpty(),
            "system_locale" to systemLocale(),
            "has_client_mods" to false,
            "client_version" to CLIENT_VERSION,
            "release_channel" to RELEASE_CHANNEL,
            "device_vendor_id" to vendorId,
            "design_id" to DESIGN_ID,
            "browser_user_agent" to "",
            "browser_version" to "",
            "os_version" to Build.VERSION.SDK_INT.toString(),
            "client_build_number" to CLIENT_BUILD_NUMBER,
            "client_event_source" to null,
            "client_launch_id" to SessionHeartbeat.clientLaunchId,
            "launch_signature" to launchSignature,
            "client_heartbeat_session_id" to SessionHeartbeat.activeSessionUnsafe()?.uuid,
            "client_app_state" to appState,
        )

    val superPropertiesString: String
        get() = runCatching { GSON.toJson(superProperties) }.getOrNull() ?: "{}"

    val superPropertiesStringBase64: String
        get() = superPropertiesString.encodeUtf8().base64()

    fun setBackgrounded(backgrounded: Boolean) {
        appState = if (backgrounded) "background" else "active"
    }

    @Volatile
    private var appState: String = "active"

    private val launchSignature = (System.currentTimeMillis() * 1_000_000L).toString()

    private val vendorId: String by lazy {
        val preferences = runCatching { SharedPreferencesProvider.get() }.getOrNull()
        preferences?.getString(VENDOR_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            preferences?.edit()?.putString(VENDOR_ID_KEY, it)?.apply()
        }
    }

    private fun systemLocale(): String = Locale.getDefault().let {
        if (it.country.isEmpty()) it.language else "${it.language}-${it.country}"
    }

    private const val RELEASE_CHANNEL = "canaryRelease"
    private const val DESIGN_ID = 2
    private const val VENDOR_ID_KEY = "rn_vendor_id"

    private val GSON = GsonBuilder().serializeNulls().create()
}
