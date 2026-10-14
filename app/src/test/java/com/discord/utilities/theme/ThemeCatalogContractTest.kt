package com.discord.utilities.theme

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The theme mapping tables name resources by string; this pins every name to the real resources. */
class ThemeCatalogContractTest {
    private val res = File("src/main/res")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val colorNames by lazy { elements("values/colors.xml", "color").map { it.getAttribute("name") }.toSet() }

    /** Every item the dark theme chain defines, which is where an overlay attribute has to land. */
    private val darkThemeAttrs by lazy {
        val styles = elements("values/styles.xml", "style").associateBy { it.getAttribute("name") }
        val attrs = mutableSetOf<String>()
        var name: String? = "AppTheme.Dark"
        while (name != null) {
            val style = styles[name] ?: break
            val items = style.getElementsByTagName("item")
            for (i in 0 until items.length) attrs += (items.item(i) as Element).getAttribute("name")
            name = style.getAttribute("parent").removePrefix("@style/").takeIf { it.isNotEmpty() }
        }
        attrs
    }

    @Test
    fun simpleRulesNameRealResources() {
        AliucordMapping.SIMPLE.forEach { (key, rule) ->
            rule.attrs.forEach { assertTrue("$key -> attr ${it.name}", it.name in darkThemeAttrs) }
            rule.colors.forEach {
                val name = AliucordMapping.SELECTOR_LEAVES[it.name] ?: it.name
                assertTrue("$key -> color $name", name in colorNames)
            }
        }
    }

    @Test
    fun tintedDrawablesExist() {
        val drawables = res.listFiles().orEmpty().filter { it.name.startsWith("drawable") }
            .flatMap { it.listFiles().orEmpty().toList() }.map { it.nameWithoutExtension }.toSet()
        (AliucordMapping.SIMPLE.values.flatMap { it.tints } + AliucordMapping.COLOR_TINTS.values.flatten())
            .forEach { assertTrue("drawable $it", it in drawables) }
    }

    @Test
    fun colorGroupsMatchThisApp() {
        val (names, attrs) = AliucordMapping.repaintedNames
        names.forEach { assertTrue("color $it", it in colorNames) }
        attrs.forEach { assertTrue("attr $it", it in darkThemeAttrs) }
    }

    @Test
    fun selectorLeavesAreTheEnabledOrCheckedColor() {
        AliucordMapping.SELECTOR_LEAVES.forEach { (selector, leaf) ->
            val items = elements("color/$selector.xml", "item")
            val active = items.firstOrNull {
                it.getAttributeNS(androidNs, "state_enabled") == "true" || it.getAttributeNS(androidNs, "state_checked") == "true"
            } ?: items.last()
            assertEquals(selector, "@color/$leaf", active.getAttributeNS(androidNs, "color"))
        }
    }

    private fun elements(path: String, tag: String): List<Element> {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(res, path))
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
