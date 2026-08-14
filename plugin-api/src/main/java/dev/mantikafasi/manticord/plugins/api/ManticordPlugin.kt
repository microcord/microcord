package dev.mantikafasi.manticord.plugins.api

import android.content.Context
import rx.Completable

interface PluginRequirement {
    val pluginId: String
    val minimumVersionCode: Int
    val maximumVersionCodeExclusive: Int?

    fun accepts(versionCode: Int): Boolean = versionCode >= minimumVersionCode &&
        (maximumVersionCodeExclusive == null || versionCode < requireNotNull(maximumVersionCodeExclusive))
}

fun pluginRequirement(
    pluginId: String,
    minimumVersionCode: Int = 0,
    maximumVersionCodeExclusive: Int? = null,
): PluginRequirement {
    require(PLUGIN_ID.matches(pluginId)) { "required plugin ID is invalid" }
    require(minimumVersionCode >= 0) { "minimum plugin version must not be negative" }
    require(maximumVersionCodeExclusive == null || maximumVersionCodeExclusive > minimumVersionCode) {
        "maximum plugin version must be greater than minimum"
    }
    return PluginRequirementValue(pluginId, minimumVersionCode, maximumVersionCodeExclusive)
}

enum class PluginLoadMode {
    ASYNC,
    BLOCKING,
}

interface PluginManifest {
    val id: String
    val name: String
    val description: String
    val versionName: String
    val versionCode: Int
    val manticordVersionMinimum: Int
    val manticordVersionMaximumExclusive: Int?
    val requirements: List<PluginRequirement>
    val priority: Int
    val loadMode: PluginLoadMode
    val usesResources: Boolean
}

class PluginManifestBuilder internal constructor() {
    var description: String = ""
    var priority: Int = 0
    var loadMode: PluginLoadMode = PluginLoadMode.ASYNC
    var usesResources: Boolean = false
    var manticordVersionMinimum: Int = 1
    var manticordVersionMaximumExclusive: Int? = 2
    private val requirements = mutableListOf<PluginRequirement>()

    fun requires(requirement: PluginRequirement) {
        require(requirements.none { it.pluginId == requirement.pluginId }) {
            "plugin requirements must be unique"
        }
        requirements += requirement
    }

    internal fun requirements(): List<PluginRequirement> = requirements.toList()
}

fun pluginManifest(
    id: String,
    name: String,
    versionName: String,
    versionCode: Int,
    configure: PluginManifestBuilder.() -> Unit = {},
): PluginManifest {
    require(PLUGIN_ID.matches(id)) { "plugin ID is invalid" }
    require(name.isNotBlank()) { "plugin name must not be blank" }
    require(versionName.isNotBlank()) { "plugin version name must not be blank" }
    require(versionCode >= 0) { "plugin version code must not be negative" }
    val builder = PluginManifestBuilder().apply(configure)
    require(builder.manticordVersionMinimum >= 1) { "minimum Manticord version must be positive" }
    require(builder.manticordVersionMaximumExclusive == null ||
        requireNotNull(builder.manticordVersionMaximumExclusive) > builder.manticordVersionMinimum) {
        "maximum Manticord version must be greater than minimum"
    }
    return PluginManifestValue(
        id,
        name,
        builder.description,
        versionName,
        versionCode,
        builder.manticordVersionMinimum,
        builder.manticordVersionMaximumExclusive,
        builder.requirements(),
        builder.priority,
        builder.loadMode,
        builder.usesResources,
    )
}

interface PluginScope {
    val applicationContext: Context
    val manifest: PluginManifest
    val pluginId: String
    val resources: PluginResources
    val settings: PluginSettings
    val ui: PluginUi
    val storage: PluginStorage
    val dialogs: PluginDialogs
}

interface ManticordPlugin {
    val settingsSchema: PluginSettingsSchema? get() = null
    val settingsPage: PluginSettingsPage? get() = null

    fun start(scope: PluginScope): Completable = Completable.complete()

    fun stop(): Completable = Completable.complete()
}

private data class PluginRequirementValue(
    override val pluginId: String,
    override val minimumVersionCode: Int,
    override val maximumVersionCodeExclusive: Int?,
) : PluginRequirement

private data class PluginManifestValue(
    override val id: String,
    override val name: String,
    override val description: String,
    override val versionName: String,
    override val versionCode: Int,
    override val manticordVersionMinimum: Int,
    override val manticordVersionMaximumExclusive: Int?,
    override val requirements: List<PluginRequirement>,
    override val priority: Int,
    override val loadMode: PluginLoadMode,
    override val usesResources: Boolean,
) : PluginManifest

private val PLUGIN_ID = Regex("[A-Za-z0-9._-]+")
