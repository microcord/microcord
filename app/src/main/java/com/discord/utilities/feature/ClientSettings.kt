package com.discord.utilities.feature

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Shared native preference definitions. Merely discovering settings never opens preferences. */
object ClientSettings {
    enum class Scope(val id: String) {
        DEFAULT("default"), DURABLE("durable"), PLUGINS("plugins"), CONTENT("content"), ENGINE("engine"),
    }

    enum class BackupPolicy { INCLUDE, EXCLUDE, WHEN_TEXT_SYNC_DISABLED }

    // PLUGIN is the SDK's runtime Boolean/String exception, not a type available to first-party keys.
    // No Long setting is registered: audited Long writers are account/telemetry/upload caches.
    enum class Type { BOOLEAN, STRING, INT, FLOAT, STRING_SET, PLUGIN }

    sealed class Setting<T>(
        val key: String,
        val type: Type,
        val default: T,
        val scope: Scope,
        val backupPolicy: BackupPolicy,
    ) {
        abstract fun get(preferences: SharedPreferences): T
        protected abstract fun put(editor: SharedPreferences.Editor, value: T)

        fun set(preferences: SharedPreferences, value: T) {
            val editor = preferences.edit()
            put(editor, value)
            editor.apply()
        }
    }

    class BooleanSetting(
        key: String, default: Boolean = false, scope: Scope = Scope.DEFAULT,
        backupPolicy: BackupPolicy = BackupPolicy.INCLUDE,
    ) : Setting<Boolean>(key, Type.BOOLEAN, default, scope, backupPolicy) {
        override fun get(preferences: SharedPreferences) = preferences.getBoolean(key, default)
        override fun put(editor: SharedPreferences.Editor, value: Boolean) { editor.putBoolean(key, value) }
    }

    class StringSetting(
        key: String, default: String? = null, scope: Scope = Scope.DEFAULT,
        backupPolicy: BackupPolicy = BackupPolicy.INCLUDE,
    ) : Setting<String?>(key, Type.STRING, default, scope, backupPolicy) {
        override fun get(preferences: SharedPreferences) = preferences.getString(key, default)
        override fun put(editor: SharedPreferences.Editor, value: String?) { editor.putString(key, value) }
    }

    class IntSetting(
        key: String, default: Int, scope: Scope = Scope.DEFAULT,
        backupPolicy: BackupPolicy = BackupPolicy.INCLUDE,
    ) : Setting<Int>(key, Type.INT, default, scope, backupPolicy) {
        override fun get(preferences: SharedPreferences) = preferences.getInt(key, default)
        override fun put(editor: SharedPreferences.Editor, value: Int) { editor.putInt(key, value) }
    }

    class FloatSetting(
        key: String, default: Float, scope: Scope = Scope.DEFAULT,
        backupPolicy: BackupPolicy = BackupPolicy.INCLUDE,
    ) : Setting<Float>(key, Type.FLOAT, default, scope, backupPolicy) {
        override fun get(preferences: SharedPreferences) = preferences.getFloat(key, default)
        override fun put(editor: SharedPreferences.Editor, value: Float) { editor.putFloat(key, value) }
    }

    class StringSetSetting(
        key: String, default: Set<String> = emptySet(), scope: Scope = Scope.DEFAULT,
        backupPolicy: BackupPolicy = BackupPolicy.INCLUDE,
    ) : Setting<Set<String>>(key, Type.STRING_SET, default.toSet(), scope, backupPolicy) {
        override fun get(preferences: SharedPreferences) = preferences.getStringSet(key, default)?.toSet() ?: default.toSet()
        override fun put(editor: SharedPreferences.Editor, value: Set<String>) { editor.putStringSet(key, value.toSet()) }
    }

    private val registry = linkedMapOf<String, Setting<*>>()
    val registered: List<Setting<*>> get() = registry.values.toList()

    private fun <T : Setting<*>> register(setting: T): T = setting.also {
        check(registry.put("${it.scope.id}/${it.key}", it) == null) { "Duplicate client setting" }
    }

    val voiceMessages = register(BooleanSetting("feature_voice_messages", true))
    val tapTap = register(BooleanSetting("feature_tap_tap"))
    val tapTapReplyOwn = register(BooleanSetting("feature_tap_tap_reply_own"))
    val fetchUncachedUsers = register(BooleanSetting("feature_fetch_uncached_users"))
    val viewProfileImages = register(BooleanSetting("feature_view_profile_images"))
    val disableServerProfiles = register(BooleanSetting("feature_disable_server_profiles"))
    val chatboxCustomizations = register(BooleanSetting("feature_chatbox_customizations"))
    val headerCustomizations = register(BooleanSetting("feature_header_customizations"))
    val typingUserAvatars = register(BooleanSetting("feature_typing_user_avatars"))
    val chatHeaderStatusIndicators = register(BooleanSetting("feature_chat_header_status_indicators"))
    val moreGifProviders = register(BooleanSetting("feature_more_gif_providers"))
    val gifUrlToInput = register(BooleanSetting("feature_gif_url_to_input"))
    val quickMessageActions = register(BooleanSetting("feature_quick_message_actions"))
    val messageCustomizations = register(BooleanSetting("feature_message_customizations"))
    val messageLimitCounter = register(BooleanSetting("feature_message_limit_counter"))
    val avatarDecorations = register(BooleanSetting("feature_avatar_decorations", true))
    val nameplates = register(BooleanSetting("feature_nameplates", true))
    val profileEffects = register(BooleanSetting("feature_profile_effects", true))
    val profileFrames = register(BooleanSetting("feature_profile_frames", true))
    val nitroSpoof = register(BooleanSetting("feature_nitro_spoof"))
    val silentTyping = register(BooleanSetting("feature_silent_typing"))
    val smoothKeyboard = register(BooleanSetting("feature_smooth_keyboard"))
    val maximumRefreshRate = register(BooleanSetting("feature_maximum_refresh_rate", true))
    val cloudSettingsBackup = register(BooleanSetting("feature_cloud_settings_backup", backupPolicy = BackupPolicy.EXCLUDE))

    const val CHAT_INPUT_LAYOUT_KEY = "feature_chatbox_customizations_layout"
    val chatInputLayout = register(StringSetting(CHAT_INPUT_LAYOUT_KEY))
    val messageLayout = register(StringSetting("message_customization_layout"))
    val gifProvider = register(StringSetting("feature_gif_provider"))

    // Enum key getters must remain computed: enum construction must not call back into this registry.
    private val headerLayouts = HeaderCustomization.Surface.entries.associateWith {
        register(StringSetting("header_customization_${it.name.lowercase()}_layout"))
    }
    private val pluginLayouts = PluginSurfaceCustomization.Surface.entries.associateWith {
        register(StringSetting("plugin_surface_${it.name.lowercase()}"))
    }
    private val sidebarButtons = ChannelSidebarCustomization.Surface.entries.flatMap { surface ->
        ChannelSidebarCustomization.Button.entries.map { button ->
            (surface to button) to register(BooleanSetting(
                "channel_sidebar_customization_${surface.name.lowercase()}_${button.name.lowercase()}",
                button != ChannelSidebarCustomization.Button.PINS,
            ))
        }
    }.toMap()
    private val dmComponents = DmListCustomization.Component.entries.associateWith {
        register(BooleanSetting("dm_list_customization_${it.name.lowercase()}",
            it != DmListCustomization.Component.MESSAGE && it != DmListCustomization.Component.TIMESTAMP))
    }
    private val headerIdentities = HeaderCustomization.Surface.entries.flatMap { surface ->
        HeaderCustomization.Identity.entries.map { identity ->
            (surface to identity) to register(BooleanSetting(
                "header_customization_${surface.name.lowercase()}_${identity.name.lowercase()}", true,
            ))
        }
    }.toMap()
    private val messageCoreElements = MessageCustomization.CoreElement.entries.associateWith {
        register(BooleanSetting("message_customization_core_${it.name.lowercase()}", true))
    }

    fun headerLayout(surface: HeaderCustomization.Surface) = headerLayouts.getValue(surface)
    fun pluginLayout(surface: PluginSurfaceCustomization.Surface) = pluginLayouts.getValue(surface)
    fun sidebarButton(surface: ChannelSidebarCustomization.Surface, button: ChannelSidebarCustomization.Button) =
        sidebarButtons.getValue(surface to button)
    fun dmComponent(component: DmListCustomization.Component) = dmComponents.getValue(component)
    fun headerIdentity(surface: HeaderCustomization.Surface, identity: HeaderCustomization.Identity) =
        headerIdentities.getValue(surface to identity)
    fun messageCore(element: MessageCustomization.CoreElement) = messageCoreElements.getValue(element)

    private val headerPlugins = ConcurrentHashMap<String, BooleanSetting>()

    fun headerPlugin(surface: HeaderCustomization.Surface, owner: String, contributionId: String): BooleanSetting =
        headerPlugins.getOrPut("${surface.name}:$owner:$contributionId") {
            val digest = MessageDigest.getInstance("SHA-256").digest("$owner:$contributionId".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            BooleanSetting("header_customization_${surface.name.lowercase()}_plugin_$digest", true)
        }

    val syncTextAndImages = register(BooleanSetting("CACHE_KEY_SYNC_TEXT_AND_IMAGES", true))
    val automaticUpdateChecks = register(BooleanSetting("automatic_update_checks", true))

    // ponytail: metadata only for legacy store/native owners; migrate their accessors in their owning slice.
    init {
        listOf(
            BooleanSetting("plugins_loading_enabled", true),
            BooleanSetting("CACHE_KEY_IMAGE_COMPRESSION"),
            BooleanSetting("CACHE_KEY_USE_CHROME_CUSTOM_TABS", true),
            BooleanSetting("CACHE_KEY_MOBILE_OVERLAY"),
            BooleanSetting("CACHE_KEY_SHIFT_ENTER_TO_SEND"),
            BooleanSetting("CACHE_KEY_INLINE_ATTACHMENT_MEDIA", true, backupPolicy = BackupPolicy.WHEN_TEXT_SYNC_DISABLED),
            BooleanSetting("CACHE_KEY_INLINE_EMBED_MEDIA", true, backupPolicy = BackupPolicy.WHEN_TEXT_SYNC_DISABLED),
            BooleanSetting("CACHE_KEY_RENDER_EMBEDS", true, backupPolicy = BackupPolicy.WHEN_TEXT_SYNC_DISABLED),
            BooleanSetting("CACHE_KEY_LOCALE_SYNC", true),
            StringSetting("CACHE_KEY_LOCALE"),
            IntSetting("CACHE_KEY_FONT_SCALE", -1),
            IntSetting("developer_premium_tier_override", -1),
            // No stored override means StoreUserSettingsSystem chooses the current device theme.
            StringSetting("CACHE_KEY_THEME", scope = Scope.DURABLE),
            BooleanSetting("CACHE_KEY_THEME_SYNC", true, Scope.DURABLE),
            BooleanSetting("CACHE_KEY_THEME_FOLLOW_SYSTEM", false, Scope.DURABLE),
            BooleanSetting("CACHE_KEY_VOICE_SETTINGS_IS_MUTED"),
            BooleanSetting("CACHE_KEY_VOICE_SETTINGS_IS_DEAFENED"),
            BooleanSetting("CACHE_KEY_VOICE_SETTINGS_AUTOMATIC_VAD", true),
            BooleanSetting("CACHE_KEY_VOICE_SETTINGS_VAD_USE_KRISP", true),
            BooleanSetting("CACHE_KEY_VOICE_SETTINGS_AUTOMATIC_GAIN_CONTROL", true),
            BooleanSetting("CACHE_KEY_VOICE_SETTINGS_ECHO_CANCELLATION", true),
            BooleanSetting("CACHE_KEY_VOICE_SETTINGS_SIDECHAIN_COMPRESSION", true),
            BooleanSetting("CACHE_KEY_HIDE_VOICE_PARTICIPANTS"),
            BooleanSetting("CACHE_KEY_HIDE_SELF_VIDEO"),
            IntSetting("CACHE_KEY_VOICE_SETTINGS_NOISE_PROCESSING", 2),
            IntSetting("CACHE_KEY_VOICE_SETTINGS_INPUT_MODE", 1),
            FloatSetting("CACHE_KEY_VOICE_SETTINGS_SENSITIVITY", -50f),
            FloatSetting("CACHE_KEY_VOICE_SETTINGS_OUTPUT_VOLUME", 100f),
            StringSetSetting("MUTED_USERS_V2"),
            StringSetSetting("USER_OUTPUT_VOLUMES_V2"),
            StringSetSetting("USER_TRUSTED_DOMAINS_CACHE_KEY"),
            BooleanSetting("developer_verbose_boot", scope = Scope.DURABLE),
            BooleanSetting("developer_floating_shortcut", scope = Scope.DURABLE),
            BooleanSetting("developer_legacy_message_uploads", scope = Scope.DURABLE),
            BooleanSetting("developer_log_gateway_events", scope = Scope.DURABLE),
            BooleanSetting("developer_log_gateway_lifecycle", scope = Scope.DURABLE),
            BooleanSetting("developer_log_gateway_voice_states", scope = Scope.DURABLE),
            BooleanSetting("developer_log_rest_requests", scope = Scope.DURABLE),
            BooleanSetting("DEBUG_OPTION_CANARY", scope = Scope.DURABLE),
            BooleanSetting("CONTACT_SYNC_DISMISS_UPSELL", scope = Scope.DURABLE),
            BooleanSetting("PUSH_NOTIFICATIONS_DISMISS_UPSELL", scope = Scope.DURABLE),
            FloatSetting("developer_floating_shortcut_x", 1f, Scope.DURABLE),
            FloatSetting("developer_floating_shortcut_y", 1f, Scope.DURABLE),
            BooleanSetting("screenshot_detection_enabled"),
            BooleanSetting("grid_mode", scope = Scope.CONTENT),
            BooleanSetting("offloadAdmControls", scope = Scope.ENGINE),
        ).forEach(::register)
    }

    private val headerPluginPattern = Regex("header_customization_(${HeaderCustomization.Surface.entries.joinToString("|") { it.name.lowercase() }})_plugin_[0-9a-f]{64}")
    private val pluginEnabled = Regex("plugin_enabled_[A-Za-z0-9._-]+")
    private val packageEnabled = Regex("plugin_package_enabled_[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")
    private val forumPreference = Regex("forum_(sort_order|layout|tag_setting|tag_filter)_([1-9][0-9]{0,18})")
    private val secretName = Regex("token|password|secret|api[_. -]?key|cookie|credential", RegexOption.IGNORE_CASE)

    /** Exact first-party keys and constrained dynamic identities; never a broad prefix export. */
    fun resolve(scope: String, key: String): Setting<*>? {
        registry["$scope/$key"]?.let { return it }
        if (scope != Scope.DEFAULT.id) return null
        if (headerPluginPattern.matches(key) || pluginEnabled.matches(key)) return BooleanSetting(key, true)
        if (packageEnabled.matches(key)) return BooleanSetting(key)
        val forum = forumPreference.matchEntire(key) ?: return null
        if (forum.groupValues[2].toLongOrNull() == null) return null
        return if (forum.groupValues[1] == "tag_filter") StringSetSetting(key) else IntSetting(key, -1)
    }

    fun backupType(scope: String, key: String): Type? {
        if (scope == Scope.PLUGINS.id) return Type.PLUGIN.takeIf { key.isNotBlank() && !secretName.containsMatchIn(key) }
        return resolve(scope, key)?.takeUnless { it.backupPolicy == BackupPolicy.EXCLUDE }?.type
    }
}
