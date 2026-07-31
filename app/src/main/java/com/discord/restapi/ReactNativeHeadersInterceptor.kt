package com.discord.restapi

import android.os.Build
import com.discord.utilities.cache.SharedPreferencesProvider
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import okhttp3.Interceptor
import okio.ByteString.Companion.toByteString

/** React Native request metadata used only for non-premium sticker sends. */
object ReactNativeRequestMetadata {
    const val BUILD_NUMBER = 6081
    const val VERSION_CODE = 341200
    const val VERSION_NAME = "341.0 - rn"
    const val USER_AGENT = "Discord-Android/$VERSION_CODE;RNA"

    private const val VENDOR_ID_KEY = "rn_vendor_id"
    private val launchId = UUID.randomUUID().toString()
    private val heartbeatSessionId = UUID.randomUUID().toString()
    private val launchSignature = (System.currentTimeMillis() * 1_000_000L).toString()

    private val vendorId: String by lazy {
        val preferences = runCatching { SharedPreferencesProvider.get() }.getOrNull()
        preferences?.getString(VENDOR_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            preferences?.edit()?.putString(VENDOR_ID_KEY, it)?.apply()
        }
    }

    val superPropertiesBase64: String
        get() = JsonObject().apply {
            addProperty("os", "Android")
            addProperty("browser", "Discord Android")
            addProperty("device", Build.DEVICE)
            addProperty("system_locale", Locale.getDefault().toString().replace('_', '-'))
            addProperty("has_client_mods", false)
            addProperty("client_version", VERSION_NAME)
            addProperty("release_channel", "canaryRelease")
            addProperty("device_vendor_id", vendorId)
            addProperty("design_id", 2)
            addProperty("browser_user_agent", "")
            addProperty("browser_version", "")
            addProperty("os_version", Build.VERSION.SDK_INT.toString())
            addProperty("client_build_number", BUILD_NUMBER)
            add("client_event_source", JsonNull.INSTANCE)
            addProperty("client_launch_id", launchId)
            addProperty("launch_signature", launchSignature)
            addProperty("client_app_state", "active")
            addProperty("client_heartbeat_session_id", heartbeatSessionId)
        }.toString().toByteArray().toByteString().base64()
}

object ReactNativeHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain) = chain.proceed(
        chain.request().newBuilder()
            .header("User-Agent", ReactNativeRequestMetadata.USER_AGENT)
            .header("X-Super-Properties", ReactNativeRequestMetadata.superPropertiesBase64)
            .header("X-Discord-Timezone", TimeZone.getDefault().id)
            .build(),
    )
}
