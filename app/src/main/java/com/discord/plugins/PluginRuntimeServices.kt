package com.discord.plugins

import android.content.Context
import dev.mantikafasi.manticord.plugins.api.PluginComposerContribution
import dev.mantikafasi.manticord.plugins.api.PluginHeaderContribution
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

internal data class OwnedHeaderContribution(
    val owner: String,
    val contribution: PluginHeaderContribution,
) {
    val key: String = "$owner:${contribution.id}"
}

internal data class OwnedComposerContribution(
    val owner: String,
    val contribution: PluginComposerContribution,
) {
    val key: String = "$owner:${contribution.id}"
}

internal object PluginUiRegistry {
    private val lock = Any()
    private val headerEntries = mutableListOf<OwnedHeaderContribution>()
    private val composerEntries = mutableListOf<OwnedComposerContribution>()
    private val headerListeners = CopyOnWriteArraySet<() -> Unit>()
    private val composerListeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var headerSnapshot: List<OwnedHeaderContribution> = emptyList()
    @Volatile private var composerSnapshot: List<OwnedComposerContribution> = emptyList()

    fun headers(): List<OwnedHeaderContribution> = headerSnapshot

    fun composers(): List<OwnedComposerContribution> = composerSnapshot

    fun addHeaderListener(listener: () -> Unit): PluginRegistration = listenerRegistration(headerListeners, listener)

    fun addComposerListener(listener: () -> Unit): PluginRegistration = listenerRegistration(composerListeners, listener)

    fun notifyHeaderChanged() {
        headerListeners.forEach { it() }
    }

    fun registerHeader(owner: String, contribution: PluginHeaderContribution): PluginRegistration {
        require(contribution.id.isNotBlank()) { "header contribution ID must not be blank" }
        val owned = OwnedHeaderContribution(owner, contribution)
        synchronized(lock) {
            require(headerEntries.none { it.key == owned.key }) { "duplicate header contribution ${owned.key}" }
            headerEntries += owned
            headerSnapshot = headerEntries.sortedWith(HEADER_ORDER)
        }
        notifyHeaderChanged()
        return registration {
            synchronized(lock) {
                headerEntries -= owned
                headerSnapshot = headerEntries.sortedWith(HEADER_ORDER)
            }
            notifyHeaderChanged()
        }
    }

    fun registerComposer(owner: String, contribution: PluginComposerContribution): PluginRegistration {
        require(contribution.id.isNotBlank()) { "composer contribution ID must not be blank" }
        val owned = OwnedComposerContribution(owner, contribution)
        synchronized(lock) {
            require(composerEntries.none { it.key == owned.key }) { "duplicate composer contribution ${owned.key}" }
            composerEntries += owned
            composerSnapshot = composerEntries.sortedWith(COMPOSER_ORDER)
        }
        composerListeners.forEach { it() }
        return registration {
            synchronized(lock) {
                composerEntries -= owned
                composerSnapshot = composerEntries.sortedWith(COMPOSER_ORDER)
            }
            composerListeners.forEach { it() }
        }
    }

    fun removeOwner(owner: String) {
        synchronized(lock) {
            headerEntries.removeAll { it.owner == owner }
            composerEntries.removeAll { it.owner == owner }
            headerSnapshot = headerEntries.sortedWith(HEADER_ORDER)
            composerSnapshot = composerEntries.sortedWith(COMPOSER_ORDER)
        }
        notifyHeaderChanged()
        composerListeners.forEach { it() }
    }

    private fun registration(remove: () -> Unit): PluginRegistration {
        val registered = AtomicBoolean(true)
        return PluginRegistration { if (registered.compareAndSet(true, false)) remove() }
    }

    private fun listenerRegistration(
        listeners: MutableSet<() -> Unit>,
        listener: () -> Unit,
    ): PluginRegistration {
        listeners += listener
        return registration { listeners -= listener }
    }

    private val HEADER_ORDER = compareByDescending<OwnedHeaderContribution> { it.contribution.priority }
        .thenBy(OwnedHeaderContribution::key)
    private val COMPOSER_ORDER = compareByDescending<OwnedComposerContribution> { it.contribution.priority }
        .thenBy(OwnedComposerContribution::key)
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
