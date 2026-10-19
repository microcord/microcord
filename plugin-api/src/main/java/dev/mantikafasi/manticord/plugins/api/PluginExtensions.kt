package dev.mantikafasi.manticord.plugins.api

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
    fun confirm(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit)
    fun page(title: String, subtitle: String = "", createView: (Context) -> View)
}

interface PluginOAuth {
    /** Opens Manticord's OAuth consent screen and receives the approved external redirect. */
    fun authorize(requestUrl: String, onRedirect: (String) -> Unit)
}

interface PluginText {
    fun renderCodeBlock(content: String, language: String? = null): CharSequence
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
    val arguments: Map<String, Any?>
}

fun pluginSlashCommandContext(
    channel: PluginChannelContext,
    arguments: Map<String, Any?>,
): PluginSlashCommandContext = PluginSlashCommandContextValue(channel, arguments)

enum class PluginSlashCommandOptionType { STRING, INTEGER, BOOLEAN }

interface PluginSlashCommandOption {
    val name: String
    val description: String
    val type: PluginSlashCommandOptionType
    val required: Boolean
    val choices: List<String>
}

interface PluginSlashCommand : PluginContribution {
    val name: String
    val description: String
    val options: List<PluginSlashCommandOption>
    val execute: (PluginSlashCommandContext) -> String
    val isVisible: (PluginChannelContext) -> Boolean
}

class PluginSlashCommandBuilder internal constructor() {
    var priority: Int = 0
    var isVisible: (PluginChannelContext) -> Boolean = { true }
    private val options = mutableListOf<PluginSlashCommandOption>()

    fun string(name: String, description: String, required: Boolean = false, choices: List<String> = emptyList()) =
        option(name, description, PluginSlashCommandOptionType.STRING, required, choices)

    fun integer(name: String, description: String, required: Boolean = false) =
        option(name, description, PluginSlashCommandOptionType.INTEGER, required)

    fun boolean(name: String, description: String, required: Boolean = false) =
        option(name, description, PluginSlashCommandOptionType.BOOLEAN, required)

    private fun option(
        name: String,
        description: String,
        type: PluginSlashCommandOptionType,
        required: Boolean,
        choices: List<String> = emptyList(),
    ) {
        require(name.matches(Regex("^[a-z0-9_-]{1,32}$"))) { "invalid slash command option name" }
        require(options.none { it.name == name }) { "duplicate slash command option $name" }
        options += PluginSlashCommandOptionValue(name, description, type, required, choices.toList())
    }

    internal fun options() = options.toList()
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
    return PluginSlashCommandValue(
        id, name, description, builder.options(), builder.priority, execute, builder.isVisible,
    )
}

enum class PluginContextMenuSurface {
    MESSAGE, CHANNEL, THREAD, USER, GUILD, FOLDER, EMOJI, STICKER, INVITE, MEMBER, EVENT, VOICE,
}

interface PluginContextMenuContext {
    val surface: PluginContextMenuSurface
    val guildId: Long?
    val channelId: Long?
    val messageId: Long?
    val userId: Long?
    val targetId: Long?
}

fun pluginContextMenuContext(
    surface: PluginContextMenuSurface,
    guildId: Long? = null,
    channelId: Long? = null,
    messageId: Long? = null,
    userId: Long? = null,
    targetId: Long? = null,
): PluginContextMenuContext = PluginContextMenuContextValue(
    surface, guildId, channelId, messageId, userId, targetId,
)

interface PluginContextMenuItem : PluginContribution {
    val title: String
    val surfaces: Set<PluginContextMenuSurface>
    val destructive: Boolean
    val createIcon: ((Context) -> Drawable?)?
    val onClick: (PluginContextMenuContext) -> Unit
    val isVisible: (PluginContextMenuContext) -> Boolean
}

enum class PluginMessagePlacement { HEADER, CONTENT, STANDALONE }

interface PluginMessageContext {
    val messageId: Long
    val channelId: Long
    val guildId: Long?
    val authorId: Long?
    val content: String
    val isAuthorBot: Boolean
}

fun pluginMessageContext(
    messageId: Long,
    channelId: Long,
    guildId: Long?,
    authorId: Long?,
    content: String,
    isAuthorBot: Boolean,
): PluginMessageContext = PluginMessageContextValue(
    messageId, channelId, guildId, authorId, content, isAuthorBot,
)

interface PluginMessageContribution : PluginContribution {
    val title: String
    val placement: PluginMessagePlacement
    val createView: (Context) -> View
    val bind: (View, PluginMessageContext) -> Unit
    val isVisible: (PluginMessageContext) -> Boolean
}

enum class PluginMessageInterceptionType { SEND, EDIT, RECEIVE }

interface PluginMessageInterceptor : PluginContribution {
    val types: Set<PluginMessageInterceptionType>
}

class PluginMessageInterceptorBuilder {
    var priority: Int = 0
}

class PluginMessageBuilder internal constructor() {
    var priority: Int = 0
    var bind: (View, PluginMessageContext) -> Unit = { _, _ -> }
    var isVisible: (PluginMessageContext) -> Boolean = { true }
}

fun pluginMessageContribution(
    id: String,
    title: String,
    placement: PluginMessagePlacement,
    createView: (Context) -> View,
    configure: PluginMessageBuilder.() -> Unit = {},
): PluginMessageContribution {
    require(id.isNotBlank()) { "message contribution ID must not be blank" }
    require(title.isNotBlank()) { "message contribution title must not be blank" }
    val builder = PluginMessageBuilder().apply(configure)
    return PluginMessageContributionValue(
        id, title, placement, builder.priority, createView, builder.bind, builder.isVisible,
    )
}

/**
 * The native views of one message row. A message's attachments, embeds and stickers are separate
 * rows and are not included.
 */
interface PluginMessageViews {
    val root: View
    val content: TextView
    val author: TextView?
    val timestamp: TextView?
}

/**
 * Restyles the native message row. [decorate] runs on the main thread on every bind of every message
 * row while registered, so keep it cheap: no allocation, I/O or lookups beyond a hash map.
 *
 * Rows are recycled. Before each bind the host restores what decorators usually change: `alpha` and
 * `background` on [PluginMessageViews.root], and `alpha`, text colors and paint flags (such as
 * strike-through) on every text view. Undo any other change yourself when a row shows a message you
 * do not decorate.
 */
interface PluginMessageDecorator : PluginContribution {
    val decorate: (PluginMessageViews, PluginMessageContext) -> Unit
}

class PluginMessageDecoratorBuilder internal constructor() {
    var priority: Int = 0
}

fun pluginMessageDecorator(
    id: String,
    decorate: (PluginMessageViews, PluginMessageContext) -> Unit,
    configure: PluginMessageDecoratorBuilder.() -> Unit = {},
): PluginMessageDecorator {
    require(id.isNotBlank()) { "message decorator ID must not be blank" }
    val builder = PluginMessageDecoratorBuilder().apply(configure)
    return PluginMessageDecoratorValue(id, builder.priority, decorate)
}

class PluginContextMenuItemBuilder internal constructor() {
    var priority: Int = 0
    var destructive: Boolean = false
    var createIcon: ((Context) -> Drawable?)? = null
    var isVisible: (PluginContextMenuContext) -> Boolean = { true }
}

fun pluginContextMenuItem(
    id: String,
    title: String,
    vararg surfaces: PluginContextMenuSurface,
    onClick: (PluginContextMenuContext) -> Unit,
    configure: PluginContextMenuItemBuilder.() -> Unit = {},
): PluginContextMenuItem {
    require(id.isNotBlank()) { "context menu item ID must not be blank" }
    require(surfaces.isNotEmpty()) { "context menu item must target at least one surface" }
    val builder = PluginContextMenuItemBuilder().apply(configure)
    return PluginContextMenuItemValue(
        id, title, surfaces.toSet(), builder.priority, builder.destructive, builder.createIcon,
        onClick, builder.isVisible,
    )
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
    fun registerContextMenuItem(item: PluginContextMenuItem): PluginRegistration
    fun registerMessage(contribution: PluginMessageContribution): PluginRegistration
    fun registerMessageInterceptor(interceptor: PluginMessageInterceptor): PluginRegistration
    fun registerMessageDecorator(decorator: PluginMessageDecorator): PluginRegistration

    /** Rebinds the visible message rows, after plugin state their contributions or decorators read changed. */
    fun refreshMessages()
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
    override val arguments: Map<String, Any?>,
) : PluginSlashCommandContext

private data class PluginSlashCommandOptionValue(
    override val name: String,
    override val description: String,
    override val type: PluginSlashCommandOptionType,
    override val required: Boolean,
    override val choices: List<String>,
) : PluginSlashCommandOption

private data class PluginSlashCommandValue(
    override val id: String,
    override val name: String,
    override val description: String,
    override val options: List<PluginSlashCommandOption>,
    override val priority: Int,
    override val execute: (PluginSlashCommandContext) -> String,
    override val isVisible: (PluginChannelContext) -> Boolean,
) : PluginSlashCommand

private data class PluginContextMenuContextValue(
    override val surface: PluginContextMenuSurface,
    override val guildId: Long?,
    override val channelId: Long?,
    override val messageId: Long?,
    override val userId: Long?,
    override val targetId: Long?,
) : PluginContextMenuContext

private data class PluginContextMenuItemValue(
    override val id: String,
    override val title: String,
    override val surfaces: Set<PluginContextMenuSurface>,
    override val priority: Int,
    override val destructive: Boolean,
    override val createIcon: ((Context) -> Drawable?)?,
    override val onClick: (PluginContextMenuContext) -> Unit,
    override val isVisible: (PluginContextMenuContext) -> Boolean,
) : PluginContextMenuItem

private data class PluginMessageContextValue(
    override val messageId: Long,
    override val channelId: Long,
    override val guildId: Long?,
    override val authorId: Long?,
    override val content: String,
    override val isAuthorBot: Boolean,
) : PluginMessageContext

private data class PluginMessageContributionValue(
    override val id: String,
    override val title: String,
    override val placement: PluginMessagePlacement,
    override val priority: Int,
    override val createView: (Context) -> View,
    override val bind: (View, PluginMessageContext) -> Unit,
    override val isVisible: (PluginMessageContext) -> Boolean,
) : PluginMessageContribution

private data class PluginMessageDecoratorValue(
    override val id: String,
    override val priority: Int,
    override val decorate: (PluginMessageViews, PluginMessageContext) -> Unit,
) : PluginMessageDecorator
