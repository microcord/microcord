package com.discord.plugins

import android.content.Context
import dev.mantikafasi.manticord.plugins.api.PluginComposerContribution
import dev.mantikafasi.manticord.plugins.api.PluginBottomTabContribution
import dev.mantikafasi.manticord.plugins.api.PluginContribution
import dev.mantikafasi.manticord.plugins.api.PluginHeaderContribution
import dev.mantikafasi.manticord.plugins.api.PluginProfileContribution
import dev.mantikafasi.manticord.plugins.api.PluginProfileTabContribution
import dev.mantikafasi.manticord.plugins.api.PluginSlashCommand
import dev.mantikafasi.manticord.plugins.api.PluginRegistration
import dev.mantikafasi.manticord.plugins.api.PluginResources
import dev.mantikafasi.manticord.plugins.api.PluginScope
import dev.mantikafasi.manticord.plugins.api.PluginSettings
import dev.mantikafasi.manticord.plugins.api.PluginUi
import dev.mantikafasi.manticord.plugins.api.PluginStorage
import dev.mantikafasi.manticord.plugins.api.PluginDialogs
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
import com.discord.app.AppActivity
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.widgets.notice.WidgetNoticeDialog

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

    fun headers() = headers.snapshot()
    fun composers() = composers.snapshot()
    fun bottomTabs() = bottomTabs.snapshot()
    fun profiles() = profiles.snapshot()
    fun profileTabs() = profileTabs.snapshot()
    fun slashCommands() = slashCommands.snapshot()

    fun addHeaderListener(listener: () -> Unit) = headers.addListener(listener)
    fun addComposerListener(listener: () -> Unit) = composers.addListener(listener)
    fun addBottomTabListener(listener: () -> Unit) = bottomTabs.addListener(listener)
    fun addProfileListener(listener: () -> Unit) = profiles.addListener(listener)
    fun addProfileTabListener(listener: () -> Unit) = profileTabs.addListener(listener)
    fun addSlashCommandListener(listener: () -> Unit) = slashCommands.addListener(listener)

    fun notifyHeaderChanged() = headers.notifyChanged()
    fun notifyComposersChanged() = composers.notifyChanged()
    fun notifyBottomTabsChanged() = bottomTabs.notifyChanged()
    fun notifyProfilesChanged() = profiles.notifyChanged()
    fun notifyProfileTabsChanged() = profileTabs.notifyChanged()

    fun registerHeader(owner: String, contribution: PluginHeaderContribution) = headers.register(owner, contribution)
    fun registerComposer(owner: String, contribution: PluginComposerContribution) = composers.register(owner, contribution)
    fun registerBottomTab(owner: String, contribution: PluginBottomTabContribution) = bottomTabs.register(owner, contribution)
    fun registerProfile(owner: String, contribution: PluginProfileContribution) = profiles.register(owner, contribution)
    fun registerProfileTab(owner: String, contribution: PluginProfileTabContribution) = profileTabs.register(owner, contribution)
    fun registerSlashCommand(owner: String, command: PluginSlashCommand) = slashCommands.register(owner, command)

    fun removeOwner(owner: String) {
        headers.removeOwner(owner)
        composers.removeOwner(owner)
        bottomTabs.removeOwner(owner)
        profiles.removeOwner(owner)
        profileTabs.removeOwner(owner)
        slashCommands.removeOwner(owner)
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
