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

interface PluginContribution {
    val id: String
    val priority: Int
}

interface PluginHeaderContribution : PluginContribution {
    val title: String
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

interface PluginComposerContribution : PluginContribution {
    val title: String
    val zone: PluginComposerZone
    val createView: (Context) -> View
    val bind: (View, PluginComposerContext) -> Unit
    val isVisible: (PluginComposerContext) -> Boolean
}

interface PluginBottomTabContext {
    val selectedTab: String
    val userId: Long
    val buttonsEnabled: Boolean
}

fun pluginBottomTabContext(
    selectedTab: String,
    userId: Long,
    buttonsEnabled: Boolean,
): PluginBottomTabContext = PluginBottomTabContextValue(selectedTab, userId, buttonsEnabled)

interface PluginBottomTabContribution : PluginContribution {
    val title: String
    val createView: (Context) -> View
    val bind: (View, PluginBottomTabContext) -> Unit
    val onClick: (PluginBottomTabContext) -> Unit
    val isVisible: (PluginBottomTabContext) -> Boolean
}

class PluginBottomTabBuilder internal constructor() {
    var priority: Int = 0
    var bind: (View, PluginBottomTabContext) -> Unit = { _, _ -> }
    var onClick: (PluginBottomTabContext) -> Unit = {}
    var isVisible: (PluginBottomTabContext) -> Boolean = { true }
}

fun pluginBottomTabContribution(
    id: String,
    title: String,
    createView: (Context) -> View,
    configure: PluginBottomTabBuilder.() -> Unit = {},
): PluginBottomTabContribution {
    require(id.isNotBlank()) { "bottom tab contribution ID must not be blank" }
    val builder = PluginBottomTabBuilder().apply(configure)
    return PluginBottomTabContributionValue(
        id, title, builder.priority, createView, builder.bind, builder.onClick, builder.isVisible,
    )
}

interface PluginProfileContext {
    val userId: Long
    val guildId: Long?
    val isCurrentUser: Boolean
    val isBot: Boolean
    val surfaceColor: Int
    val borderColor: Int
    val primaryTextColor: Int
    val secondaryTextColor: Int
    val interactiveColor: Int
}

fun pluginProfileContext(
    userId: Long,
    guildId: Long?,
    isCurrentUser: Boolean,
    isBot: Boolean,
    surfaceColor: Int,
    borderColor: Int,
    primaryTextColor: Int,
    secondaryTextColor: Int,
    interactiveColor: Int,
): PluginProfileContext = PluginProfileContextValue(
    userId, guildId, isCurrentUser, isBot, surfaceColor, borderColor,
    primaryTextColor, secondaryTextColor, interactiveColor,
)

interface PluginProfileContribution : PluginContribution {
    val title: String
    val createView: (Context) -> View
    val bind: (View, PluginProfileContext) -> Unit
    val isVisible: (PluginProfileContext) -> Boolean
}

interface PluginProfileTabContribution : PluginContribution {
    val title: String
    val createView: (Context) -> View
    val bind: (View, PluginProfileContext) -> Unit
    val isVisible: (PluginProfileContext) -> Boolean
}

class PluginProfileBuilder internal constructor() {
    var priority: Int = 0
    var bind: (View, PluginProfileContext) -> Unit = { _, _ -> }
    var isVisible: (PluginProfileContext) -> Boolean = { true }
}

fun pluginProfileContribution(
    id: String,
    title: String,
    createView: (Context) -> View,
    configure: PluginProfileBuilder.() -> Unit = {},
): PluginProfileContribution {
    require(id.isNotBlank()) { "profile contribution ID must not be blank" }
    val builder = PluginProfileBuilder().apply(configure)
    return PluginProfileContributionValue(
        id, title, builder.priority, createView, builder.bind, builder.isVisible,
    )
}

fun pluginProfileTabContribution(
    id: String,
    title: String,
    createView: (Context) -> View,
    configure: PluginProfileBuilder.() -> Unit = {},
): PluginProfileTabContribution {
    require(id.isNotBlank()) { "profile tab contribution ID must not be blank" }
    val builder = PluginProfileBuilder().apply(configure)
    return PluginProfileTabContributionValue(
        id, title, builder.priority, createView, builder.bind, builder.isVisible,
    )
}

interface PluginSlashCommandContext {
    val channel: PluginChannelContext
    val arguments: String
}

fun pluginSlashCommandContext(
    channel: PluginChannelContext,
    arguments: String,
): PluginSlashCommandContext = PluginSlashCommandContextValue(channel, arguments)

interface PluginSlashCommand : PluginContribution {
    val name: String
    val description: String
    val execute: (PluginSlashCommandContext) -> String
    val isVisible: (PluginChannelContext) -> Boolean
}

class PluginSlashCommandBuilder internal constructor() {
    var priority: Int = 0
    var isVisible: (PluginChannelContext) -> Boolean = { true }
}

fun pluginSlashCommand(
    id: String,
    name: String,
    description: String,
    execute: (PluginSlashCommandContext) -> String,
    configure: PluginSlashCommandBuilder.() -> Unit = {},
): PluginSlashCommand {
    require(id.isNotBlank()) { "slash command ID must not be blank" }
    require(name.matches(Regex("^[a-z0-9_-]{1,32}$"))) { "invalid slash command name" }
    val builder = PluginSlashCommandBuilder().apply(configure)
    return PluginSlashCommandValue(id, name, description, builder.priority, execute, builder.isVisible)
}

class PluginComposerBuilder internal constructor() {
    var title: String = ""
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
        builder.title.ifBlank { id },
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
    fun registerBottomTab(contribution: PluginBottomTabContribution): PluginRegistration
    fun registerProfile(contribution: PluginProfileContribution): PluginRegistration
    fun registerProfileTab(contribution: PluginProfileTabContribution): PluginRegistration
    fun registerSlashCommand(command: PluginSlashCommand): PluginRegistration
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
    override val title: String,
    override val zone: PluginComposerZone,
    override val priority: Int,
    override val createView: (Context) -> View,
    override val bind: (View, PluginComposerContext) -> Unit,
    override val isVisible: (PluginComposerContext) -> Boolean,
) : PluginComposerContribution

private data class PluginBottomTabContextValue(
    override val selectedTab: String,
    override val userId: Long,
    override val buttonsEnabled: Boolean,
) : PluginBottomTabContext

private data class PluginBottomTabContributionValue(
    override val id: String,
    override val title: String,
    override val priority: Int,
    override val createView: (Context) -> View,
    override val bind: (View, PluginBottomTabContext) -> Unit,
    override val onClick: (PluginBottomTabContext) -> Unit,
    override val isVisible: (PluginBottomTabContext) -> Boolean,
) : PluginBottomTabContribution

private data class PluginProfileContextValue(
    override val userId: Long,
    override val guildId: Long?,
    override val isCurrentUser: Boolean,
    override val isBot: Boolean,
    override val surfaceColor: Int,
    override val borderColor: Int,
    override val primaryTextColor: Int,
    override val secondaryTextColor: Int,
    override val interactiveColor: Int,
) : PluginProfileContext

private data class PluginProfileContributionValue(
    override val id: String,
    override val title: String,
    override val priority: Int,
    override val createView: (Context) -> View,
    override val bind: (View, PluginProfileContext) -> Unit,
    override val isVisible: (PluginProfileContext) -> Boolean,
) : PluginProfileContribution

private data class PluginProfileTabContributionValue(
    override val id: String,
    override val title: String,
    override val priority: Int,
    override val createView: (Context) -> View,
    override val bind: (View, PluginProfileContext) -> Unit,
    override val isVisible: (PluginProfileContext) -> Boolean,
) : PluginProfileTabContribution

private data class PluginSlashCommandContextValue(
    override val channel: PluginChannelContext,
    override val arguments: String,
) : PluginSlashCommandContext

private data class PluginSlashCommandValue(
    override val id: String,
    override val name: String,
    override val description: String,
    override val priority: Int,
    override val execute: (PluginSlashCommandContext) -> String,
    override val isVisible: (PluginChannelContext) -> Boolean,
) : PluginSlashCommand
