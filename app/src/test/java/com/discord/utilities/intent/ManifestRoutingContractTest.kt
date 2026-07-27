package com.discord.utilities.intent

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Node

class ManifestRoutingContractTest {
    @Test
    fun exportedEntriesExposeOnlyTlsAppLinksAndExpectedActions() {
        val manifest = File("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        val xml = manifest.readText()

        assertTrue(xml.contains("com.discord.app.AppActionActivity"))
        assertTrue(xml.contains("android.intent.action.SEND_MULTIPLE"))
        assertTrue(xml.contains("com.discord.intent.action.SDK"))
        assertTrue(xml.contains("android:scheme=\"manticord\""))

        // Scoped to activity intent-filters, not the whole file. `<queries>` declares an
        // `http` VIEW intent purely for API 30+ package visibility — without it `UriHandler`
        // cannot resolve a browser — and it exposes nothing. A raw text scan conflated the two.
        val activitySchemes = (0 until activities.length)
            .map(activities::item)
            .flatMap { activity -> activity.childElements("intent-filter") }
            .flatMap { filter -> filter.childElements("data") }
            .mapNotNull { data -> data.attributes.getNamedItemNS(ANDROID_NS, "scheme")?.nodeValue }
            .toSet()
        assertFalse("http" in activitySchemes)
        assertFalse("file" in activitySchemes)

        assertEquals(2, (0 until activities.length).count {
            activities.item(it).attributes.getNamedItemNS(ANDROID_NS, "exported")?.nodeValue == "true"
        })
    }

    private fun Node.childElements(name: String): List<Node> =
        (0 until childNodes.length).map(childNodes::item).filter { it.nodeName == name }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
