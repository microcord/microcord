package dev.mantikafasi.manticord.plugins.api

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rx.Observable
import java.io.File

interface PluginSetting {
    val key: String?

    interface Section : PluginSetting {
        val title: String
    }

    interface Toggle : PluginSetting {
        override val key: String
        val title: String
        val description: String
        val default: Boolean
    }

    interface Text : PluginSetting {
        override val key: String
        val title: String
        val description: String
        val default: String
    }

    interface Choice : PluginSetting {
        override val key: String
        val title: String
        val description: String
        val default: String
        val options: List<Option>

        interface Option {
            val value: String
            val label: String
        }
    }

    interface Action : PluginSetting {
        override val key: String
        val title: String
        val description: String
        val onClick: () -> Unit
    }
}

interface PluginSettingsSchema {
    val entries: List<PluginSetting>
}

class PluginSettingsBuilder internal constructor() {
    private val entries = mutableListOf<PluginSetting>()

    fun section(title: String) {
        entries += SectionValue(title)
    }

    fun toggle(key: String, title: String, description: String = "", default: Boolean = false) {
        entries += ToggleValue(key, title, description, default)
    }

    fun text(key: String, title: String, description: String = "", default: String = "") {
        entries += TextValue(key, title, description, default)
    }

    fun choice(
        key: String,
        title: String,
        default: String,
        options: List<Pair<String, String>>,
        description: String = "",
    ) {
        require(options.isNotEmpty()) { "choice options must not be empty" }
        require(options.any { it.first == default }) { "choice default must be an option" }
        entries += ChoiceValue(
            key,
            title,
            description,
            default,
            options.map { ChoiceOptionValue(it.first, it.second) },
        )
    }

    fun action(key: String, title: String, description: String = "", onClick: () -> Unit) {
        entries += ActionValue(key, title, description, onClick)
    }

    internal fun build(): PluginSettingsSchema {
        val keys = entries.mapNotNull(PluginSetting::key)
        require(keys.all(String::isNotBlank)) { "setting keys must not be blank" }
        require(keys.distinct().size == keys.size) { "setting keys must be unique" }
        return PluginSettingsSchemaValue(entries.toList())
    }
}

fun pluginSettings(configure: PluginSettingsBuilder.() -> Unit): PluginSettingsSchema =
    PluginSettingsBuilder().apply(configure).build()

interface PluginSettings {
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun getString(key: String, default: String = ""): String
    fun putBoolean(key: String, value: Boolean)
    fun putString(key: String, value: String)
    fun observeChanges(): Observable<String>
}

interface PluginResources {
    val context: Context?
    val inflater: LayoutInflater?
}

interface PluginStorage {
    val filesDir: File
    val cacheDir: File
}

interface PluginDialogs {
    fun alert(title: String, message: String)
}

fun interface PluginRegistration {
    fun unregister()
}

interface PluginChannelContext {
    val channelId: Long
    val guildId: Long?
    val channelType: Int
    val isPrivate: Boolean
}

fun pluginChannelContext(
    channelId: Long,
    guildId: Long?,
    channelType: Int,
    isPrivate: Boolean,
): PluginChannelContext = PluginChannelContextValue(channelId, guildId, channelType, isPrivate)

interface PluginComposerContext {
    val channel: PluginChannelContext
    val isEditing: Boolean
    val canSendMessages: Boolean
    val text: CharSequence
}

fun pluginComposerContext(
    channel: PluginChannelContext,
    isEditing: Boolean,
    canSendMessages: Boolean,
    text: CharSequence,
): PluginComposerContext = PluginComposerContextValue(channel, isEditing, canSendMessages, text)

enum class PluginComposerZone {
    LEFT,
    INSIDE_START,
    INSIDE_END,
    RIGHT,
}

interface PluginHeaderContribution {
    val id: String
    val title: String
    val priority: Int
    val createView: (Context) -> View
    val bind: (View, PluginChannelContext) -> Unit
    val onClick: (PluginChannelContext) -> Unit
    val isVisible: (PluginChannelContext) -> Boolean
}

class PluginHeaderBuilder internal constructor() {
    var priority: Int = 0
    var bind: (View, PluginChannelContext) -> Unit = { _, _ -> }
    var onClick: (PluginChannelContext) -> Unit = {}
    var isVisible: (PluginChannelContext) -> Boolean = { true }
}

fun pluginHeaderContribution(
    id: String,
    title: String,
    createView: (Context) -> View,
    configure: PluginHeaderBuilder.() -> Unit = {},
): PluginHeaderContribution {
    require(id.isNotBlank()) { "header contribution ID must not be blank" }
    val builder = PluginHeaderBuilder().apply(configure)
    return PluginHeaderContributionValue(
        id,
        title,
        builder.priority,
        createView,
        builder.bind,
        builder.onClick,
        builder.isVisible,
    )
}

interface PluginComposerContribution {
    val id: String
    val zone: PluginComposerZone
    val priority: Int
    val createView: (Context) -> View
    val bind: (View, PluginComposerContext) -> Unit
    val isVisible: (PluginComposerContext) -> Boolean
}

class PluginComposerBuilder internal constructor() {
    var priority: Int = 0
    var bind: (View, PluginComposerContext) -> Unit = { _, _ -> }
    var isVisible: (PluginComposerContext) -> Boolean = { true }
}

fun pluginComposerContribution(
    id: String,
    zone: PluginComposerZone,
    createView: (Context) -> View,
    configure: PluginComposerBuilder.() -> Unit = {},
): PluginComposerContribution {
    require(id.isNotBlank()) { "composer contribution ID must not be blank" }
    val builder = PluginComposerBuilder().apply(configure)
    return PluginComposerContributionValue(
        id,
        zone,
        builder.priority,
        createView,
        builder.bind,
        builder.isVisible,
    )
}

interface PluginUi {
    fun registerHeader(contribution: PluginHeaderContribution): PluginRegistration
    fun registerComposer(contribution: PluginComposerContribution): PluginRegistration
}

fun interface PluginSettingsPage {
    fun createView(context: Context, parent: ViewGroup): View
}

private data class SectionValue(override val title: String) : PluginSetting.Section {
    override val key: String? = null
}

private data class ToggleValue(
    override val key: String,
    override val title: String,
    override val description: String,
    override val default: Boolean,
) : PluginSetting.Toggle

private data class TextValue(
    override val key: String,
    override val title: String,
    override val description: String,
    override val default: String,
) : PluginSetting.Text

private data class ChoiceOptionValue(
    override val value: String,
    override val label: String,
) : PluginSetting.Choice.Option

private data class ChoiceValue(
    override val key: String,
    override val title: String,
    override val description: String,
    override val default: String,
    override val options: List<PluginSetting.Choice.Option>,
) : PluginSetting.Choice

private data class ActionValue(
    override val key: String,
    override val title: String,
    override val description: String,
    override val onClick: () -> Unit,
) : PluginSetting.Action

private data class PluginSettingsSchemaValue(
    override val entries: List<PluginSetting>,
) : PluginSettingsSchema

private data class PluginChannelContextValue(
    override val channelId: Long,
    override val guildId: Long?,
    override val channelType: Int,
    override val isPrivate: Boolean,
) : PluginChannelContext

private data class PluginComposerContextValue(
    override val channel: PluginChannelContext,
    override val isEditing: Boolean,
    override val canSendMessages: Boolean,
    override val text: CharSequence,
) : PluginComposerContext

private data class PluginHeaderContributionValue(
    override val id: String,
    override val title: String,
    override val priority: Int,
    override val createView: (Context) -> View,
    override val bind: (View, PluginChannelContext) -> Unit,
    override val onClick: (PluginChannelContext) -> Unit,
    override val isVisible: (PluginChannelContext) -> Boolean,
) : PluginHeaderContribution

private data class PluginComposerContributionValue(
    override val id: String,
    override val zone: PluginComposerZone,
    override val priority: Int,
    override val createView: (Context) -> View,
    override val bind: (View, PluginComposerContext) -> Unit,
    override val isVisible: (PluginComposerContext) -> Boolean,
) : PluginComposerContribution
