package com.discord.resources

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ButtonColorResourceContractTest {
    @Test
    fun `shared material buttons keep their semantic state tints`() {
        val root = sequenceOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val styles = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "app/src/main/res/values/styles.xml"))
        val expected = mapOf(
            "UiKit.Material.Button" to "@color/uikit_btn_bg_color_selector_brand",
            "UiKit.Material.Button.Green" to "@color/uikit_btn_bg_color_selector_green",
            "UiKit.Material.Button.Red" to "@color/uikit_btn_bg_color_selector_red",
            "UiKit.Material.Button.Secondary" to "?attr/colorButtonSecondary",
            "UiKit.Material.Button.Tertiary" to "?attr/colorBackgroundTertiary",
            "UiKit.Material.Button.White" to "@color/uikit_btn_bg_color_selector_white",
            "BlockedUsersList.Item.UnblockButton" to "?attr/colorBackgroundAccent",
            "UiKit.GiftEmbedDisabledButton" to "?attr/primary_600",
        )

        expected.forEach { (styleName, tint) ->
            val style = (0 until styles.getElementsByTagName("style").length)
                .map { styles.getElementsByTagName("style").item(it) as Element }
                .single { it.getAttribute("name") == styleName }
            val items = (0 until style.getElementsByTagName("item").length)
                .map { style.getElementsByTagName("item").item(it) as Element }
            assertEquals(tint, items.single { it.getAttribute("name") == "backgroundTint" }.textContent)
            assertFalse(items.any { it.getAttribute("name") == "android:backgroundTint" })
        }

        listOf("brand", "green", "red", "white").forEach { variant ->
            val selector = File(
                root,
                "app/src/main/res/color/uikit_btn_bg_color_selector_$variant.xml",
            ).readText()
            assertTrue(selector.contains("android:state_enabled=\"true\""))
            assertTrue(selector.contains("android:state_enabled=\"false\""))
        }
    }
}
