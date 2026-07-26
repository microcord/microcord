package com.discord.utilities.analytics

import android.os.Build
import android.util.Base64
import com.discord.utilities.accessibility.AccessibilityFeatureFlags
import com.google.gson.Gson
import java.util.EnumSet
import java.util.Locale

/**
 * Client identity sent with every analytics event and on the Gateway `identify` payload.
 *
 * These are the values Discord's backend uses to attribute a session to a client build. They are
 * baseline device/build facts only — no account data, no identifiers recovered from the reference
 * APK. [superPropertiesStringBase64] is what the transport headers carry.
 */
object AnalyticSuperProperties {

    @Volatile
    var superProperties: Map<String, Any> = emptyMap()
        private set

    @Volatile
    var superPropertiesString: String = "{}"
        private set

    @Volatile
    var superPropertiesStringBase64: String = ""
        private set

    init {
        setBaselineProperties()
    }

    /**
     * Publishes the accessibility state as a bit set.
     *
     * `StoreAccessibility` calls this whenever the OS accessibility configuration changes.
     */
    fun setAccessibilityProperties(
        accessibilitySupportEnabled: Boolean,
        features: EnumSet<AccessibilityFeatureFlags>,
    ) {
        val flags = features.fold(0L) { acc, feature -> acc or feature.value }
        updateSuperProperties(
            mapOf(
                PROPERTY_ACCESSIBILITY_SUPPORT_ENABLED to accessibilitySupportEnabled,
                PROPERTY_ACCESSIBILITY_FEATURES to flags,
            ),
        )
    }

    /** Publishes the advertising identifier, once the user has been asked for it. */
    fun setAdvertiserId(advertiserId: String) {
        updateSuperProperties(mapOf(PROPERTY_DEVICE_ADVERTISER_ID to advertiserId))
    }

    /**
     * Publishes install-referrer attribution.
     *
     * The raw referrer string is stored under `referrer`; recognised `key=value` pairs inside it are
     * hoisted to their own properties. Unrecognised keys are dropped rather than forwarded, so an
     * attacker-supplied referrer cannot inject arbitrary super properties.
     */
    fun setCampaignProperties(referrerUrl: String) {
        val properties = mutableMapOf<String, Any>(PROPERTY_REFERRER to referrerUrl)
        referrerUrl.split("&")
            .map { it.split("=") }
            .filter { it.size == 2 && it[0] in CAMPAIGN_PROPERTIES }
            .forEach { (key, value) -> properties[key] = value }
        updateSuperProperties(properties)
    }

    /** Publishes the most recent device-resource sample. */
    fun setClientPerformanceProperties(cpu: Int, memoryKb: Long, cpuCoreCount: Int) {
        updateSuperProperties(
            mapOf(
                PROPERTY_CLIENT_PERFORMANCE_CPU to cpu,
                PROPERTY_CLIENT_PERFORMANCE_MEMORY to memoryKb,
                PROPERTY_CPU_CORE_COUNT to cpuCoreCount,
            ),
        )
    }

    private fun setBaselineProperties() {
        updateSuperProperties(
            mapOf(
                PROPERTY_BROWSER to BROWSER,
                PROPERTY_BROWSER_USER_AGENT to "Discord-Android/$CLIENT_BUILD_NUMBER",
                PROPERTY_CLIENT_BUILD_NUMBER to CLIENT_BUILD_NUMBER,
                PROPERTY_CLIENT_VERSION to CLIENT_VERSION,
                PROPERTY_DEVICE to "${Build.MODEL}, ${Build.PRODUCT}",
                PROPERTY_OS to "Android",
                PROPERTY_OS_SDK_VERSION to Build.VERSION.SDK_INT.toString(),
                PROPERTY_OS_VERSION to Build.VERSION.RELEASE.orEmpty(),
                PROPERTY_SYSTEM_LOCALE to Locale.getDefault().toString().replace("_", "-"),
            ),
        )
    }

    @Synchronized
    private fun updateSuperProperties(extraSuperProperties: Map<String, Any>) {
        val merged = superProperties + extraSuperProperties
        superProperties = merged
        superPropertiesString = runCatching { Gson().toJson(merged) }.getOrNull() ?: "{}"
        // `Base64` is an Android framework class and returns null under unit tests.
        superPropertiesStringBase64 = runCatching {
            Base64.encodeToString(superPropertiesString.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }.getOrNull().orEmpty()
    }

    /** Reference build identity for 126.21 Stable; the transport reports the client it reproduces. */
    private const val BROWSER = "Discord Android"
    private const val CLIENT_BUILD_NUMBER = 126021
    private const val CLIENT_VERSION = "126.21 - Stable"

    private const val PROPERTY_ACCESSIBILITY_FEATURES = "accessibility_features"
    private const val PROPERTY_ACCESSIBILITY_SUPPORT_ENABLED = "accessibility_support_enabled"
    private const val PROPERTY_BROWSER = "browser"
    private const val PROPERTY_BROWSER_USER_AGENT = "browser_user_agent"
    private const val PROPERTY_CLIENT_BUILD_NUMBER = "client_build_number"
    private const val PROPERTY_CLIENT_PERFORMANCE_CPU = "client_performance_cpu"
    private const val PROPERTY_CLIENT_PERFORMANCE_MEMORY = "client_performance_memory"
    private const val PROPERTY_CLIENT_VERSION = "client_version"
    private const val PROPERTY_CPU_CORE_COUNT = "cpu_core_count"
    private const val PROPERTY_DEVICE = "device"
    private const val PROPERTY_DEVICE_ADVERTISER_ID = "device_advertiser_id"
    private const val PROPERTY_OS = "os"
    private const val PROPERTY_OS_SDK_VERSION = "os_sdk_version"
    private const val PROPERTY_OS_VERSION = "os_version"
    private const val PROPERTY_REFERRER = "referrer"
    private const val PROPERTY_SYSTEM_LOCALE = "system_locale"

    /** Referrer keys the reference hoists out of the install-referrer string. */
    private val CAMPAIGN_PROPERTIES = setOf(
        "location",
        "mp_keyword",
        "referring_domain",
        "search_engine",
        "utm_campaign",
        "utm_content",
        "utm_medium",
        "utm_source",
        "utm_term",
    )
}
