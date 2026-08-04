package com.discord.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class ManifestPlatformContractTest {
    private val manifestFile = File("src/main/AndroidManifest.xml")
    private val manifest by lazy { parse(manifestFile) }

    @Test
    fun shortcutsActionsAndPackageVisibilityAreWiredWithoutCredentials() {
        assertEquals("@xml/shortcuts", manifest.named("meta-data", "android.app.shortcuts").android("resource"))
        assertEquals("@xml/actions", manifest.named("meta-data", "com.google.android.actions").android("resource"))

        val shortcuts = parse(File("src/main/res/xml/shortcuts.xml"))
        assertEquals("com.discord.app.AppActionActivity", shortcuts.elements("share-target").single().android("targetClass"))
        assertEquals("*/*", shortcuts.elements("data").single().android("mimeType"))
        assertEquals("com.discord.intent.category.DIRECT_SHARE_TARGET", shortcuts.elements("category").single().android("name"))

        val actions = parse(File("src/main/res/xml/actions.xml"))
        assertEquals(
            setOf(
                "custom.actions.intent.JOIN_SERVER",
                "custom.actions.intent.SELECT_SERVER",
                "custom.actions.intent.SELECT_CHANNEL",
                "custom.actions.intent.SELECT_USER",
                "actions.intent.CREATE_MESSAGE",
                "actions.intent.GET_MESSAGE",
                "actions.intent.OPEN_APP_FEATURE",
            ),
            actions.elements("action").map { it.getAttribute("intentName") }.toSet(),
        )

        assertEquals(
            setOf(
                "com.spotify.music",
                "com.authy.authy",
                "com.google.android.apps.authenticator2",
            ),
            manifest.elements("queries").single().descendants("package").map { it.android("name") }.toSet(),
        )
        val queryActions = manifest.elements("queries").single().descendants("action").map { it.android("name") }.toSet()
        assertTrue("android.intent.action.CHOOSER" in queryActions)
        assertTrue("android.media.action.IMAGE_CAPTURE" in queryActions)
        assertTrue("android.intent.action.PICK" in queryActions)
        assertTrue("android.intent.action.GET_CONTENT" in queryActions)
        assertTrue("android.support.customtabs.action.CustomTabsService" in queryActions)

        val ownedXml = listOf(manifestFile, File("src/main/res/xml/shortcuts.xml"), File("src/main/res/xml/actions.xml"))
            .joinToString("\n") { it.readText() }
        assertFalse(Regex("AIza[0-9A-Za-z_-]{20,}").containsMatchIn(ownedXml))
        assertFalse("com.google.android.nearby.messages.API_KEY" in ownedXml)
        assertFalse(manifest.elements("intent-filter").any { it.android("autoVerify") == "true" })
        assertEquals("social", manifest.elements("application").single().android("appCategory"))
    }

    @Test
    fun firstPartyComponentsExposeOnlyReviewedEntryPoints() {
        val expected = mapOf(
            "com.discord.app.AppActivity" to false,
            "com.discord.app.AppActivity\$Main" to true,
            "com.discord.app.AppActivity\$Call" to false,
            "com.discord.app.AppActivity\$IncomingCall" to false,
            "com.discord.app.AppActionActivity" to true,
            "com.discord.utilities.notifications.NotificationActionReceiver" to false,
            "com.discord.utilities.io.UserDownloadCompleteReceiver" to true,
            "com.discord.utilities.voice.DiscordOverlayService" to false,
            "com.discord.utilities.voice.VoiceEngineForegroundService" to false,
            "com.discord.app.DiscordConnectService" to false,
        )
        expected.forEach { (name, exported) ->
            assertEquals(exported.toString(), manifest.component(name).android("exported"))
        }

        manifest.elements("activity", "service", "receiver", "provider")
            .filter { it.android("name").startsWith("com.discord.") }
            .forEach { assertTrue("Missing exported on ${it.android("name")}", it.hasAndroid("exported")) }

        val connectService = manifest.component("com.discord.app.DiscordConnectService")
        assertFalse(connectService.descendants("intent-filter").any())
        assertNotNull(manifest.component("com.yalantis.ucrop.UCropActivity"))
    }

    @Test
    fun everyPendingIntentDeclaresMutabilityAndOnlyRemoteInputIsMutable() {
        val calls = File("src/main/java").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> pendingIntentCalls(file.readText()).map { file to it } }
            .toList()

        assertTrue(calls.isNotEmpty())
        calls.forEach { (file, call) ->
            assertTrue(
                "PendingIntent mutability missing in ${file.relativeTo(File("src/main"))}: $call",
                MUTABILITY_MARKERS.any(call::contains),
            )
        }
        val mutable = calls.filter { (_, call) -> "mutableReplyFlags" in call || "FLAG_MUTABLE" in call }
        assertEquals(1, mutable.size)
        assertEquals("NotificationRenderer.kt", mutable.single().first.name)
        assertEquals("false", manifest.component("com.discord.utilities.notifications.NotificationActionReceiver").android("exported"))
    }

    private fun pendingIntentCalls(source: String): Sequence<String> = sequence {
        val startPattern = Regex("PendingIntent\\.get(?:Activity|Broadcast|Service|ForegroundService)\\s*\\(")
        startPattern.findAll(source).forEach { match ->
            var depth = 0
            for (index in match.range.last until source.length) {
                when (source[index]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) {
                        yield(source.substring(maxOf(0, match.range.first - 500), index + 1))
                        break
                    }
                }
            }
        }
    }

    private fun parse(file: File): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file)

    private fun Document.elements(vararg tags: String): List<Element> = tags.flatMap { tag ->
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    }

    private fun Document.named(tag: String, name: String): Element =
        elements(tag).single { it.android("name") == name }

    private fun Document.component(name: String): Element =
        elements("activity", "service", "receiver", "provider").single { it.android("name") == name }

    private fun Element.descendants(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)
    private fun Element.hasAndroid(name: String): Boolean = hasAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val MUTABILITY_MARKERS = setOf(
            "FLAG_IMMUTABLE",
            "FLAG_MUTABLE",
            "immutablePendingIntentFlag",
            "mutablePendingIntentFlag",
            "immutableFlags",
            "mutableReplyFlags",
        )
    }
}
