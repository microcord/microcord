package com.discord.plugins

import com.discord.api.channel.Channel
import com.discord.stores.BuiltInCommands
import dev.mantikafasi.manticord.plugins.api.PluginLoadMode
import dev.mantikafasi.manticord.plugins.api.PluginManifest
import dev.mantikafasi.manticord.plugins.api.PluginRequirement
import dev.mantikafasi.manticord.plugins.api.PluginComposerContribution
import dev.mantikafasi.manticord.plugins.api.PluginComposerZone
import dev.mantikafasi.manticord.plugins.api.PluginHeaderContribution
import dev.mantikafasi.manticord.plugins.api.PluginSettingsSchema
import dev.mantikafasi.manticord.plugins.api.PluginSetting
import dev.mantikafasi.manticord.plugins.api.pluginComposerContribution
import dev.mantikafasi.manticord.plugins.api.pluginBottomTabContribution
import dev.mantikafasi.manticord.plugins.api.pluginHeaderContribution
import dev.mantikafasi.manticord.plugins.api.pluginProfileContribution
import dev.mantikafasi.manticord.plugins.api.pluginProfileTabContribution
import dev.mantikafasi.manticord.plugins.api.pluginSlashCommand
import dev.mantikafasi.manticord.plugins.api.pluginManifest
import dev.mantikafasi.manticord.plugins.api.pluginRequirement
import dev.mantikafasi.manticord.plugins.api.pluginSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class PluginDependencyResolverTest {
    @Test
    fun `registry listeners are independently lifecycle owned`() {
        var first = 0
        var second = 0
        val firstRegistration = PluginUiRegistry.addHeaderListener { first++ }
        val secondRegistration = PluginUiRegistry.addHeaderListener { second++ }

        PluginUiRegistry.notifyHeaderChanged()
        firstRegistration.unregister()
        PluginUiRegistry.notifyHeaderChanged()
        secondRegistration.unregister()

        assertEquals(1, first)
        assertEquals(2, second)
    }

    @Test
    fun `ui registrations are ordered and removed by owner`() {
        PluginUiRegistry.removeOwner("first")
        PluginUiRegistry.removeOwner("second")
        PluginUiRegistry.registerHeader(
            "first",
            pluginHeaderContribution("low", "Low", { error("not rendered") }) { priority = 1 },
        )
        PluginUiRegistry.registerHeader(
            "second",
            pluginHeaderContribution("high", "High", { error("not rendered") }) { priority = 10 },
        )
        PluginUiRegistry.registerComposer(
            "first",
            pluginComposerContribution(
                "composer",
                PluginComposerZone.LEFT,
                { error("not rendered") },
            ),
        )
        PluginUiRegistry.registerBottomTab(
            "first",
            pluginBottomTabContribution("bottom-low", "Bottom Low", { error("not rendered") }) { priority = 1 },
        )
        PluginUiRegistry.registerBottomTab(
            "second",
            pluginBottomTabContribution("bottom-high", "Bottom High", { error("not rendered") }) { priority = 10 },
        )
        PluginUiRegistry.registerProfile(
            "first",
            pluginProfileContribution("profile", "Profile", { error("not rendered") }),
        )
        PluginUiRegistry.registerProfileTab(
            "second",
            pluginProfileTabContribution("profile-tab", "Profile Tab", { error("not rendered") }),
        )
        PluginUiRegistry.registerSlashCommand(
            "first",
            pluginSlashCommand("echo", "echo", "Echo text", { it.arguments["message"]?.toString().orEmpty() }) {
                priority = 5
                string("message", "Message")
            },
        )

        assertEquals(listOf("high", "low"), PluginUiRegistry.headers().map { it.contribution.id })
        assertEquals(listOf("composer"), PluginUiRegistry.composers().map { it.contribution.id })
        assertEquals(listOf("bottom-high", "bottom-low"), PluginUiRegistry.bottomTabs().map { it.contribution.id })
        assertEquals(listOf("profile"), PluginUiRegistry.profiles().map { it.contribution.id })
        assertEquals(listOf("profile-tab"), PluginUiRegistry.profileTabs().map { it.contribution.id })
        assertEquals(listOf("echo"), PluginUiRegistry.slashCommands().map { it.contribution.id })
        val slash = BuiltInCommands().getBuiltInCommands(Channel.DM).first { it.name == "echo" }
        assertEquals("hello", slash.execute?.invoke(mapOf("message" to "hello")))
        assertSame(PluginUiRegistry.headers(), PluginUiRegistry.headers())
        assertSame(PluginUiRegistry.composers(), PluginUiRegistry.composers())

        PluginUiRegistry.removeOwner("first")
        PluginUiRegistry.removeOwner("second")
        assertTrue(PluginUiRegistry.headers().isEmpty())
        assertTrue(PluginUiRegistry.composers().isEmpty())
        assertTrue(PluginUiRegistry.bottomTabs().isEmpty())
        assertTrue(PluginUiRegistry.profiles().isEmpty())
        assertTrue(PluginUiRegistry.profileTabs().isEmpty())
        assertTrue(PluginUiRegistry.slashCommands().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `settings schema rejects duplicate keys`() {
        pluginSettings {
            toggle("same", "One")
            text("same", "Two")
        }
    }

    @Test
    fun `global disable skips discovery entirely`() {
        var discoveryCalls = 0
        val manager = PluginManager(
            source = PluginSource { _ ->
                discoveryCalls++
                emptyList()
            },
            loadingEnabled = { false },
        )

        val plan = manager.initialize(null)

        assertEquals(0, discoveryCalls)
        assertTrue(plan.plugins.isEmpty())
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun `requirements load first and priority orders ready plugins`() {
        val plan = PluginDependencyResolver.resolve(
            listOf(
                candidate("feature", priority = 100, requirements = listOf(requirement("library"))),
                candidate("library", priority = -100),
                candidate("independent", priority = 50),
            ),
        )

        assertEquals(listOf("independent", "library", "feature"), plan.plugins.map { it.manifest.id })
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun `blocking plugins promote only their requirement chain`() {
        val plan = PluginDependencyResolver.resolve(
            listOf(
                candidate("base"),
                candidate("library", requirements = listOf(requirement("base"))),
                candidate(
                    "blocking-feature",
                    loadMode = PluginLoadMode.BLOCKING,
                    requirements = listOf(requirement("library")),
                ),
                candidate("unrelated"),
            ),
        )

        assertEquals(
            mapOf(
                "base" to PluginLoadMode.BLOCKING,
                "library" to PluginLoadMode.BLOCKING,
                "blocking-feature" to PluginLoadMode.BLOCKING,
                "unrelated" to PluginLoadMode.ASYNC,
            ),
            plan.plugins.associate { it.manifest.id to it.loadMode },
        )
    }

    @Test
    fun `missing disabled and incompatible requirements block dependents`() {
        val plan = PluginDependencyResolver.resolve(
            listOf(
                candidate("disabled", enabled = false),
                candidate("needs-disabled", requirements = listOf(requirement("disabled"))),
                candidate("needs-missing", requirements = listOf(requirement("missing"))),
                candidate("old-library", versionCode = 1),
                candidate("needs-new-library", requirements = listOf(requirement("old-library", minimum = 2))),
                candidate("wrong-app", manticordMinimum = 2),
            ),
        )

        assertEquals(PluginBlockReason.Disabled, plan.blocked["disabled"])
        assertEquals(PluginBlockReason.RequirementBlocked("disabled"), plan.blocked["needs-disabled"])
        assertEquals(PluginBlockReason.MissingRequirement("missing"), plan.blocked["needs-missing"])
        assertEquals(
            PluginBlockReason.IncompatibleRequirement("old-library", 1),
            plan.blocked["needs-new-library"],
        )
        assertEquals(PluginBlockReason.IncompatibleManticord(1), plan.blocked["wrong-app"])
        assertEquals(listOf("old-library"), plan.plugins.map { it.manifest.id })
    }

    @Test
    fun `cycles block their members and then their dependents`() {
        val plan = PluginDependencyResolver.resolve(
            listOf(
                candidate("a", requirements = listOf(requirement("b"))),
                candidate("b", requirements = listOf(requirement("a"))),
                candidate("dependent", requirements = listOf(requirement("a"))),
                candidate("healthy"),
            ),
        )

        assertEquals(PluginBlockReason.DependencyCycle, plan.blocked["a"])
        assertEquals(PluginBlockReason.DependencyCycle, plan.blocked["b"])
        assertEquals(PluginBlockReason.RequirementBlocked("a"), plan.blocked["dependent"])
        assertEquals(listOf("healthy"), plan.plugins.map { it.manifest.id })
    }

    private fun candidate(
        id: String,
        enabled: Boolean = true,
        versionCode: Int = 1,
        priority: Int = 0,
        loadMode: PluginLoadMode = PluginLoadMode.ASYNC,
        requirements: List<PluginRequirement> = emptyList(),
        manticordMinimum: Int = 1,
    ) = PluginCandidate(
        manifest = pluginManifest(id, id, versionCode.toString(), versionCode) {
            this.priority = priority
            this.loadMode = loadMode
            manticordVersionMinimum = manticordMinimum
            manticordVersionMaximumExclusive = manticordMinimum + 1
            requirements.forEach(::requires)
        },
        enabled = enabled,
    )

    private fun requirement(id: String, minimum: Int = 0) = pluginRequirement(id, minimum)
}
