package com.discord.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mantikafasi.manticord.plugins.api.ManticordPlugin
import dev.mantikafasi.manticord.plugins.api.PluginComposerContribution
import dev.mantikafasi.manticord.plugins.api.PluginComposerZone
import dev.mantikafasi.manticord.plugins.api.PluginManifest
import dev.mantikafasi.manticord.plugins.api.PluginScope
import dev.mantikafasi.manticord.plugins.api.pluginComposerContribution
import dev.mantikafasi.manticord.plugins.api.pluginManifest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rx.Completable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PluginManagerTest {
    private val pluginId = "test.runtime"

    @After
    fun cleanRegistry() {
        PluginUiRegistry.removeOwner(pluginId)
    }

    @Test
    fun asyncPluginStartsAndShutdownRemovesContributions() {
        val started = CountDownLatch(1)
        val manifest = pluginManifest(pluginId, "Runtime Test", "1", 1)
        val plugin = object : ManticordPlugin {
            override fun start(scope: PluginScope): Completable = Completable.fromAction {
                scope.ui.registerComposer(
                    pluginComposerContribution(
                        "button",
                        PluginComposerZone.LEFT,
                        { android.view.View(it) },
                    ),
                )
                started.countDown()
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PluginManager(BundledPluginSource(listOf(BundledPlugin(plugin, manifest))), loadingEnabled = { true })

        manager.initialize(context)

        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("button"), PluginUiRegistry.composers().map { it.contribution.id })
        manager.shutdown()
        assertTrue(PluginUiRegistry.composers().isEmpty())
    }
}
