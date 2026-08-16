package com.discord.plugins

import android.content.Context
import dev.mantikafasi.manticord.plugins.api.PluginComposerContribution
import dev.mantikafasi.manticord.plugins.api.PluginBottomTabContribution
import dev.mantikafasi.manticord.plugins.api.PluginContribution
import dev.mantikafasi.manticord.plugins.api.PluginHeaderContribution
import dev.mantikafasi.manticord.plugins.api.PluginProfileContribution
import dev.mantikafasi.manticord.plugins.api.PluginProfileTabContribution
import dev.mantikafasi.manticord.plugins.api.PluginSlashCommand
import dev.mantikafasi.manticord.plugins.api.PluginContextMenuItem
import dev.mantikafasi.manticord.plugins.api.PluginMessageContribution
import dev.mantikafasi.manticord.plugins.api.PluginMessageInterceptionType
import dev.mantikafasi.manticord.plugins.api.PluginMessageInterceptor
import dev.mantikafasi.manticord.plugins.api.HostMessageInterceptor
import dev.mantikafasi.manticord.plugins.api.PluginRegistration
import dev.mantikafasi.manticord.plugins.api.PluginResources
import dev.mantikafasi.manticord.plugins.api.PluginScope
import dev.mantikafasi.manticord.plugins.api.PluginSettings
import dev.mantikafasi.manticord.plugins.api.PluginUi
import dev.mantikafasi.manticord.plugins.api.PluginStorage
import dev.mantikafasi.manticord.plugins.api.PluginDialogs
import dev.mantikafasi.manticord.plugins.api.PluginText
import dev.mantikafasi.manticord.plugins.api.PluginManifest
import rx.Observable
import rx.subjects.PublishSubject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.security.MessageDigest
import android.os.Handler
import android.os.Looper
import com.discord.R
import com.discord.models.message.Message
import com.discord.app.AppActivity
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.PluginPageHost
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageRenderContext

internal data class OwnedContribution<T : PluginContribution>(
    val owner: String,
    val contribution: T,
) {
    val key: String = "$owner:${contribution.id}"
}

internal typealias OwnedHeaderContribution = OwnedContribution<PluginHeaderContribution>
internal typealias OwnedComposerContribution = OwnedContribution<PluginComposerContribution>
internal typealias OwnedBottomTabContribution = OwnedContribution<PluginBottomTabContribution>
internal typealias OwnedProfileContribution = OwnedContribution<PluginProfileContribution>
internal typealias OwnedProfileTabContribution = OwnedContribution<PluginProfileTabContribution>
internal typealias OwnedSlashCommand = OwnedContribution<PluginSlashCommand>
internal typealias OwnedContextMenuItem = OwnedContribution<PluginContextMenuItem>
internal typealias OwnedMessageContribution = OwnedContribution<PluginMessageContribution>
internal typealias OwnedMessageInterceptor = OwnedContribution<PluginMessageInterceptor>

internal data class PluginContentInfo(
    val type: String,
    val title: String,
    val detail: String = "",
)

private class ContributionRegistry<T : PluginContribution>(private val label: String) {
    private val lock = Any()
    private val entries = mutableListOf<OwnedContribution<T>>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var snapshot: List<OwnedContribution<T>> = emptyList()

    fun snapshot() = snapshot

    fun addListener(listener: () -> Unit): PluginRegistration {
        listeners += listener
        return registration { listeners -= listener }
    }

    fun notifyChanged() = listeners.forEach { it() }

    fun register(owner: String, contribution: T): PluginRegistration {
        require(contribution.id.isNotBlank()) { "$label contribution ID must not be blank" }
        val owned = OwnedContribution(owner, contribution)
        synchronized(lock) {
            require(entries.none { it.key == owned.key }) { "duplicate $label contribution ${owned.key}" }
            entries += owned
            publish()
        }
        notifyChanged()
        return registration {
            synchronized(lock) {
                entries -= owned
                publish()
            }
            notifyChanged()
        }
    }

    fun removeOwner(owner: String) {
        synchronized(lock) {
            entries.removeAll { it.owner == owner }
            publish()
        }
        notifyChanged()
    }

    private fun publish() {
        snapshot = entries.sortedWith(
            compareByDescending<OwnedContribution<T>> { it.contribution.priority }
                .thenBy(OwnedContribution<T>::key),
        )
    }
}

internal object PluginUiRegistry {
    private val headers = ContributionRegistry<PluginHeaderContribution>("header")
    private val composers = ContributionRegistry<PluginComposerContribution>("composer")
    private val bottomTabs = ContributionRegistry<PluginBottomTabContribution>("bottom tab")
    private val profiles = ContributionRegistry<PluginProfileContribution>("profile")
    private val profileTabs = ContributionRegistry<PluginProfileTabContribution>("profile tab")
    private val slashCommands = ContributionRegistry<PluginSlashCommand>("slash command")
    private val contextMenuItems = ContributionRegistry<PluginContextMenuItem>("context menu item")
    private val messages = ContributionRegistry<PluginMessageContribution>("message")
    private val messageInterceptors = ContributionRegistry<PluginMessageInterceptor>("message interceptor")

    fun headers() = headers.snapshot()
    fun composers() = composers.snapshot()
    fun bottomTabs() = bottomTabs.snapshot()
    fun profiles() = profiles.snapshot()
    fun profileTabs() = profileTabs.snapshot()
    fun slashCommands() = slashCommands.snapshot()
    fun contextMenuItems() = contextMenuItems.snapshot()
    fun messages() = messages.snapshot()
    fun messageInterceptors() = messageInterceptors.snapshot()

    fun hasMessageInterceptors(type: PluginMessageInterceptionType): Boolean =
        messageInterceptors.snapshot().any { type in it.contribution.types && it.contribution is HostMessageInterceptor }

    fun interceptMessage(
        type: PluginMessageInterceptionType,
        message: Message,
    ): Message {
        val interceptors = messageInterceptors.snapshot()
        if (interceptors.isEmpty()) return message
        return interceptors.fold(message) { current, owned ->
            val interceptor = owned.contribution as? HostMessageInterceptor
                ?: return@fold current
            if (type !in interceptor.types) return@fold current
            runCatching { interceptor.transform(current) }.getOrDefault(current)
        }
    }

    fun content(owner: String): List<PluginContentInfo> = buildList {
        headers().filter { it.owner == owner }.forEach {
            add(PluginContentInfo("Header action", it.contribution.title))
        }
        composers().filter { it.owner == owner }.forEach {
            add(PluginContentInfo("Composer action", it.contribution.title, it.contribution.zone.name.lowercase()))
        }
        bottomTabs().filter { it.owner == owner }.forEach {
            add(PluginContentInfo("Bottom tab", it.contribution.title))
        }
        profiles().filter { it.owner == owner }.forEach {
            add(PluginContentInfo("Profile section", it.contribution.title))
        }
        profileTabs().filter { it.owner == owner }.forEach {
            add(PluginContentInfo("Profile tab", it.contribution.title))
        }
        slashCommands().filter { it.owner == owner }.forEach {
            val options = it.contribution.options.joinToString { option -> option.name }
            add(PluginContentInfo("Slash command", "/${it.contribution.name}", options))
        }
        contextMenuItems().filter { it.owner == owner }.forEach {
            val surfaces = it.contribution.surfaces.joinToString { surface -> surface.name.lowercase() }
            add(PluginContentInfo("Context menu", it.contribution.title, surfaces))
        }
        messages().filter { it.owner == owner }.forEach {
            add(PluginContentInfo("Message ${it.contribution.placement.name.lowercase()}", it.contribution.title))
        }
        messageInterceptors().filter { it.owner == owner }.forEach {
            add(PluginContentInfo("Message interceptor", it.contribution.types.joinToString { type -> type.name.lowercase() }))
        }
    }

    fun addHeaderListener(listener: () -> Unit) = headers.addListener(listener)
    fun addComposerListener(listener: () -> Unit) = composers.addListener(listener)
    fun addBottomTabListener(listener: () -> Unit) = bottomTabs.addListener(listener)
    fun addProfileListener(listener: () -> Unit) = profiles.addListener(listener)
    fun addProfileTabListener(listener: () -> Unit) = profileTabs.addListener(listener)
    fun addSlashCommandListener(listener: () -> Unit) = slashCommands.addListener(listener)
    fun addMessageListener(listener: () -> Unit) = messages.addListener(listener)

    fun notifyHeaderChanged() = headers.notifyChanged()
    fun notifyComposersChanged() = composers.notifyChanged()
    fun notifyBottomTabsChanged() = bottomTabs.notifyChanged()
    fun notifyProfilesChanged() = profiles.notifyChanged()
    fun notifyProfileTabsChanged() = profileTabs.notifyChanged()
    fun notifyMessagesChanged() = messages.notifyChanged()

    fun registerHeader(owner: String, contribution: PluginHeaderContribution) = headers.register(owner, contribution)
    fun registerComposer(owner: String, contribution: PluginComposerContribution) = composers.register(owner, contribution)
    fun registerBottomTab(owner: String, contribution: PluginBottomTabContribution) = bottomTabs.register(owner, contribution)
    fun registerProfile(owner: String, contribution: PluginProfileContribution) = profiles.register(owner, contribution)
    fun registerProfileTab(owner: String, contribution: PluginProfileTabContribution) = profileTabs.register(owner, contribution)
    fun registerSlashCommand(owner: String, command: PluginSlashCommand) = slashCommands.register(owner, command)
    fun registerContextMenuItem(owner: String, item: PluginContextMenuItem) = contextMenuItems.register(owner, item)
    fun registerMessage(owner: String, contribution: PluginMessageContribution) =
        messages.register(owner, contribution)
    fun registerMessageInterceptor(owner: String, interceptor: PluginMessageInterceptor) =
        messageInterceptors.register(owner, interceptor)

    fun removeOwner(owner: String) {
        headers.removeOwner(owner)
        composers.removeOwner(owner)
        bottomTabs.removeOwner(owner)
        profiles.removeOwner(owner)
        profileTabs.removeOwner(owner)
        slashCommands.removeOwner(owner)
        contextMenuItems.removeOwner(owner)
        messages.removeOwner(owner)
        messageInterceptors.removeOwner(owner)
    }
}

private fun registration(remove: () -> Unit): PluginRegistration {
    val registered = AtomicBoolean(true)
    return PluginRegistration { if (registered.compareAndSet(true, false)) remove() }
}

internal class PluginSettingsImpl(
    context: Context,
    private val pluginId: String,
) : PluginSettings {
    private val preferences = context.getSharedPreferences("plugin_settings", Context.MODE_PRIVATE)
    private val changes = PublishSubject.create<String>().toSerialized()

    override fun getBoolean(key: String, default: Boolean): Boolean =
        preferences.getBoolean(namespaced(key), default)

    override fun getString(key: String, default: String): String =
        preferences.getString(namespaced(key), default) ?: default

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(namespaced(key), value).apply()
        changes.onNext(key)
    }

    override fun putString(key: String, value: String) {
        preferences.edit().putString(namespaced(key), value).apply()
        changes.onNext(key)
    }

    override fun observeChanges(): Observable<String> = changes.asObservable()

    private fun namespaced(key: String): String {
        require(key.isNotBlank()) { "setting key must not be blank" }
        return "$pluginId.$key"
    }

}

internal class PluginScopeImpl(
    override val applicationContext: Context,
    override val manifest: PluginManifest,
    override val resources: PluginResources,
    override val settings: PluginSettings,
) : PluginScope {
    override val pluginId: String = manifest.id
    private val registrations = CopyOnWriteArrayList<PluginRegistration>()
    private val closed = AtomicBoolean()

    override fun runOnMainThread(action: (Context) -> Unit) {
        check(!closed.get()) { "plugin scope is closed" }
        Handler(Looper.getMainLooper()).post {
            if (!closed.get()) action(ActivityProvider.getActivity() ?: applicationContext)
        }
    }

    override val ui: PluginUi = object : PluginUi {
        override fun registerHeader(contribution: PluginHeaderContribution): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerHeader(pluginId, contribution))
        }

        override fun registerComposer(contribution: PluginComposerContribution): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerComposer(pluginId, contribution))
        }

        override fun registerBottomTab(contribution: PluginBottomTabContribution): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerBottomTab(pluginId, contribution))
        }

        override fun registerProfile(contribution: PluginProfileContribution): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerProfile(pluginId, contribution))
        }

        override fun registerProfileTab(contribution: PluginProfileTabContribution): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerProfileTab(pluginId, contribution))
        }

        override fun registerSlashCommand(command: PluginSlashCommand): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerSlashCommand(pluginId, command))
        }

        override fun registerContextMenuItem(item: PluginContextMenuItem): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerContextMenuItem(pluginId, item))
        }

        override fun registerMessage(contribution: PluginMessageContribution): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerMessage(pluginId, contribution))
        }

        override fun registerMessageInterceptor(interceptor: PluginMessageInterceptor): PluginRegistration {
            check(!closed.get()) { "plugin scope is closed" }
            return track(PluginUiRegistry.registerMessageInterceptor(pluginId, interceptor))
        }
    }
    override val storage: PluginStorage = object : PluginStorage {
        private val directory = MessageDigest.getInstance("SHA-256")
            .digest(pluginId.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        override val filesDir = File(applicationContext.filesDir, "plugins/$directory").apply { mkdirs() }
        override val cacheDir = File(applicationContext.cacheDir, "plugins/$directory").apply { mkdirs() }
    }
    override val dialogs: PluginDialogs = object : PluginDialogs {
        override fun alert(title: String, message: String) {
            Handler(Looper.getMainLooper()).post {
                val activity = ActivityProvider.getActivity() as? AppActivity ?: return@post
                WidgetNoticeDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.okay)
                    .show(activity.supportFragmentManager)
            }
        }

        override fun page(title: String, subtitle: String, createView: (Context) -> android.view.View) {
            Handler(Looper.getMainLooper()).post {
                val activity = ActivityProvider.getActivity() as? AppActivity ?: return@post
                PluginPageHost.open(activity, title, subtitle, createView)
            }
        }

    }
    override val text: PluginText = object : PluginText {
        override fun renderCodeBlock(content: String, language: String?): CharSequence {
            val context = ActivityProvider.getActivity() ?: applicationContext
            val label = language?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }.orEmpty()
            return AstRenderer.render(
                DiscordParser.parse("```$label\n$content\n```"),
                MessageRenderContext(context, myId = 0L, isAnimationEnabled = false),
            )
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        registrations.forEach(PluginRegistration::unregister)
        registrations.clear()
    }

    private fun track(registration: PluginRegistration): PluginRegistration {
        check(!closed.get()) { "plugin scope is closed" }
        registrations += registration
        if (closed.get() && registrations.remove(registration)) {
            registration.unregister()
            error("plugin scope is closed")
        }
        return registration
    }
}
