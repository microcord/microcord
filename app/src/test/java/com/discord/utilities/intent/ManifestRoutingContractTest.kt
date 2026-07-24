package com.discord.utilities.intent

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertFalse(xml.contains("android:scheme=\"http\""))
        assertFalse(xml.contains("android:scheme=\"file\""))
        assertEquals(2, (0 until activities.length).count {
            activities.item(it).attributes.getNamedItemNS(ANDROID_NS, "exported")?.nodeValue == "true"
        })
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
