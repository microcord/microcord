package com.discord.widgets.applicationdirectory

import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationIntegrationTypeConfig
import com.discord.api.applicationdirectory.ApplicationInstallParams
import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.utilities.intent.DeepLinkParser
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.ExternalUriPolicy
import com.discord.utilities.intent.LinkParseResult
import com.discord.widgets.auth.WidgetOauth2Authorize
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.TestScheduler

class ApplicationDirectoryFlowTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }

    @Test
    fun searchDebouncesAndOnlySubmitsTheLatestQuery() {
        val scheduler = TestScheduler()
        val calls = mutableListOf<Pair<String, Int?>>()
        val debouncer = ApplicationDirectorySearchDebouncer(scheduler) { query, category ->
            calls += query to category
        }

        debouncer.submit("a", null)
        debouncer.submit("apps", 5)
        scheduler.advanceTimeBy(299, TimeUnit.MILLISECONDS)
        assertTrue(calls.isEmpty())
        scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        assertEquals(listOf("apps" to 5), calls)
        debouncer.clear()
    }

    @Test
    fun installRequestCarriesScopesPermissionsContextAndLockedGuild() {
        val request = ApplicationInstallCoordinator.InstallRequest(
            applicationId = 42,
            scopes = listOf("applications.commands", "bot"),
            permissions = "32",
            integrationType = 0,
            guildId = 99,
        ).authorizeParameters()

        assertEquals("applications.commands bot", request["scope"])
        assertEquals("32", request["permissions"])
        assertEquals("0", request["integration_type"])
        assertEquals("99", request["guild_id"])
        assertEquals("true", request["disable_guild_select"])
    }

    @Test
    fun integrationTypesComeFromConfigurationWithoutBotOrGuildInference() {
        val user = app(mapOf(1 to ApplicationIntegrationTypeConfig()))
        val guild = app(mapOf(0 to config("32")))
        val both = app(mapOf(0 to config("32"), 1 to config("0")))
        val neither = app(emptyMap())

        assertEquals(listOf(1), ApplicationInstallCoordinator.installTargets(user))
        assertEquals(listOf(0), ApplicationInstallCoordinator.installTargets(guild))
        assertEquals(listOf(1, 0), ApplicationInstallCoordinator.installTargets(both))
        assertTrue(ApplicationInstallCoordinator.installTargets(neither).isEmpty())
        assertNull(user.installParams(1))
    }

    @Test
    fun legacyInstallParamsRemainGuildOnly() {
        val legacy = ApplicationDirectoryApplication(
            id = 42,
            name = "Legacy",
            installParams = ApplicationInstallParams(listOf("applications.commands"), "8"),
        )

        assertEquals(setOf(0), legacy.supportedIntegrationTypes)
        assertEquals("8", legacy.installParams(0)?.permissions)
        assertNull(legacy.installParams(1))
    }

    @Test
    fun userOauthCanUseServerConfigurationWithoutGuildSelection() {
        val params = ApplicationInstallCoordinator.InstallRequest(
            applicationId = 42,
            scopes = null,
            permissions = null,
            integrationType = 1,
        ).authorizeParameters()

        assertEquals("1", params["integration_type"])
        assertFalse(params.containsKey("scope"))
        assertFalse(params.containsKey("permissions"))
        assertFalse(params.containsKey("guild_id"))
        assertFalse(params.containsKey("disable_guild_select"))
        assertFalse(WidgetOauth2Authorize.parse(params).isUnsupported)
    }

    @Test
    fun guildEligibilityRequiresOwnershipOrManageGuild() {
        val owned = Guild(id = 1, name = "Owned", ownerId = 7)
        val managed = Guild(id = 2, name = "Managed", ownerId = 8)
        val denied = Guild(id = 3, name = "Denied", ownerId = 8)
        val eligible = ApplicationInstallCoordinator.eligibleGuilds(
            listOf(denied, managed, owned),
            mapOf(2L to Permission.MANAGE_GUILD, 3L to Permission.VIEW_CHANNEL),
            userId = 7,
        )

        assertEquals(listOf("Managed", "Owned"), eligible.map { it.name })
        assertTrue(ApplicationInstallCoordinator.eligibleGuilds(listOf(denied), emptyMap(), 7).isEmpty())
    }

    @Test
    fun customInstallUrlDoesNotChangeDeclaredContexts() {
        val application = app(
            mapOf(0 to config("32"), 1 to config("0")),
            customInstallUrl = "https://example.com/install",
        )

        assertTrue(ExternalUriPolicy.isAllowed(requireNotNull(application.customInstallUrl)))
        assertEquals(listOf(1, 0), ApplicationInstallCoordinator.installTargets(application))
    }

    @Test
    fun directoryUsesOnlyFirstPartyInteractiveAndFeedbackViews() {
        val coordinator = source("java/com/discord/widgets/applicationdirectory/ApplicationInstallCoordinator.kt")
        listOf("AlertDialog", "MaterialAlertDialogBuilder", "ArrayAdapter", "Spinner", "ProgressBar").forEach {
            assertFalse("install runtime must not use $it", coordinator.contains(it))
        }
        assertTrue(coordinator.contains("SelectorDialog.show"))
        assertTrue(coordinator.contains("WidgetNoticeDialog.Builder"))

        listOf("widget_application_directory.xml", "widget_application_directory_detail.xml").forEach { name ->
            val layout = source("res/layout/$name")
            listOf("<Spinner", "<ProgressBar", "<Button", "<EditText", "MaterialButton").forEach {
                assertFalse("$name must not contain $it", layout.contains(it))
            }
            assertTrue("$name must use first-party loading UI", layout.contains("com.discord.views.typing.TypingDots"))
            assertTrue("$name must use first-party buttons", layout.contains("com.discord.views.LoadingButton"))
        }
    }

    @Test
    fun modernOauthParamsParseAndCancellationGuardsRemainClosed() {
        val request = WidgetOauth2Authorize.parse(
            mapOf(
                "client_id" to "42",
                "scope" to "applications.commands bot",
                "permissions" to "32",
                "integration_type" to "0",
                "guild_id" to "99",
                "disable_guild_select" to "true",
            ),
        )
        assertFalse(request.isUnsupported)
        assertEquals(0, request.integrationType)
        assertEquals(99L, request.guildId)
        assertTrue(request.disableGuildSelect)
        assertTrue(WidgetOauth2Authorize.parse(mapOf("client_id" to "42", "scope" to "bot")).isUnsupported)
        assertFalse(ExternalUriPolicy.isAllowed("http://example.com/install"))
    }

    @Test
    fun userAndGuildInstallsCompleteDiscordLocationInApp() {
        val location = "https://discord.com/oauth2/authorized"
        val user = WidgetOauth2Authorize.parse(
            mapOf("client_id" to "42", "integration_type" to "1", "internal_referrer" to "true"),
        )
        val guild = WidgetOauth2Authorize.parse(
            mapOf(
                "client_id" to "42",
                "scope" to "applications.commands bot",
                "integration_type" to "0",
                "guild_id" to "99",
                "internal_referrer" to "true",
            ),
        )

        assertEquals(WidgetOauth2Authorize.CompletionRoute.Internal, WidgetOauth2Authorize.classifyCompletion(user, location))
        assertEquals(WidgetOauth2Authorize.CompletionRoute.Internal, WidgetOauth2Authorize.classifyCompletion(guild, location))
    }

    @Test
    fun onlyExplicitMatchingExternalOauthCallbackLeavesTheApp() {
        val request = WidgetOauth2Authorize.parse(
            mapOf(
                "client_id" to "42",
                "scope" to "identify",
                "redirect_uri" to "https://example.com/oauth/callback",
            ),
        )

        assertEquals(
            WidgetOauth2Authorize.CompletionRoute.External("https://example.com/oauth/callback?code=ok"),
            WidgetOauth2Authorize.classifyCompletion(request, "https://example.com/oauth/callback?code=ok"),
        )
        assertEquals(
            WidgetOauth2Authorize.CompletionRoute.Invalid,
            WidgetOauth2Authorize.classifyCompletion(request, "https://evil.example/oauth/callback?code=ok"),
        )
    }

    @Test
    fun malformedUnsafeAndUnexpectedInstallLocationsFailClosed() {
        val install = WidgetOauth2Authorize.parse(
            mapOf("client_id" to "42", "integration_type" to "1", "internal_referrer" to "true"),
        )
        val callback = WidgetOauth2Authorize.parse(
            mapOf("client_id" to "42", "scope" to "identify", "redirect_uri" to "https://example.com/callback"),
        )

        assertEquals(WidgetOauth2Authorize.CompletionRoute.Invalid, WidgetOauth2Authorize.classifyCompletion(install, "://bad"))
        assertEquals(
            WidgetOauth2Authorize.CompletionRoute.Invalid,
            WidgetOauth2Authorize.classifyCompletion(callback, "javascript:alert(1)"),
        )
        assertEquals(
            WidgetOauth2Authorize.CompletionRoute.Invalid,
            WidgetOauth2Authorize.classifyCompletion(install, "https://example.com/callback"),
        )
    }

    @Test
    fun cancellationIsTypedAndInternalSuccessHasNoBrowserBranch() {
        assertEquals(
            WidgetOauth2Authorize.CompletionResult.CANCELLED,
            WidgetOauth2Authorize.completionResult(null),
        )
        val oauth = source("java/com/discord/widgets/auth/WidgetOauth2Authorize.kt")
        assertTrue(oauth.contains("CompletionRoute.Internal -> complete(CompletionResult.SUCCESS)"))
        assertFalse(oauth.contains("CompletionRoute.Internal -> UriHandler"))
    }

    @Test
    fun onlyDiscordApplicationDirectoryRoutesAreAccepted() {
        val index = DeepLinkParser.parse("https://discord.com/discovery/applications/search?q=music")
        val detail = DeepLinkParser.parse("https://discord.com/discovery/applications/42")
        val foreign = DeepLinkParser.parse("https://example.com/discovery/applications/42")

        assertEquals(DeepLinkRoute.ApplicationDirectory(), (index as LinkParseResult.Internal).route)
        assertEquals(DeepLinkRoute.ApplicationDirectory(42), (detail as LinkParseResult.Internal).route)
        assertTrue(foreign is LinkParseResult.External)
    }

    @Test
    fun discordOauthCallbackIsConsumedAsInternalNavigation() {
        val web = DeepLinkParser.parse("https://discord.com/oauth2/authorized?code=redacted")
        val app = DeepLinkParser.parse("manticord://action/oauth2/callback?code=redacted")

        assertTrue((web as LinkParseResult.Internal).route is DeepLinkRoute.OAuthCallback)
        assertTrue((app as LinkParseResult.Internal).route is DeepLinkRoute.OAuthCallback)
    }

    private fun app(
        configs: Map<Int, ApplicationIntegrationTypeConfig>,
        customInstallUrl: String? = null,
    ) = ApplicationDirectoryApplication(
        id = 42,
        name = "App",
        integrationTypesConfig = configs,
        customInstallUrl = customInstallUrl,
    )

    private fun config(permissions: String) = ApplicationIntegrationTypeConfig(
        ApplicationInstallParams(listOf("applications.commands"), permissions),
    )

    private fun source(path: String) = File(root, "app/src/main/$path").readText()
}
