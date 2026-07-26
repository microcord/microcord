package com.discord.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackPressHandlerRegistryTest {

    @Test
    fun `dispatch runs lower priorities first and stops at the first consumer`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("last", priority = 100) { invoked += "last"; false }
        registry.set("first", priority = 0) { invoked += "first"; false }
        registry.set("middle", priority = 50) { invoked += "middle"; true }

        assertTrue(registry.dispatchBackPressed())
        assertEquals(listOf("first", "middle"), invoked)
    }

    @Test
    fun `dispatch returns false when nothing consumes the press`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("a", priority = 1) { invoked += "a"; false }
        registry.set("b", priority = 2) { invoked += "b"; false }

        assertFalse(registry.dispatchBackPressed())
        assertEquals(listOf("a", "b"), invoked)
    }

    @Test
    fun `handlers registered at the same priority run in registration order`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("one", priority = 10) { invoked += "one"; false }
        registry.set("two", priority = 10) { invoked += "two"; false }
        registry.set("three", priority = 10) { invoked += "three"; false }

        assertFalse(registry.dispatchBackPressed())
        assertEquals(listOf("one", "two", "three"), invoked)
    }

    @Test
    fun `re-registering a key replaces the previous handler`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("screen", priority = 5) { invoked += "stale"; true }
        registry.set("screen", priority = 5) { invoked += "fresh"; true }

        assertTrue(registry.dispatchBackPressed())
        assertEquals(listOf("fresh"), invoked)
    }

    @Test
    fun `remove drops the key from every priority bucket`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("screen", priority = 1) { invoked += "low"; true }
        registry.set("screen", priority = 9) { invoked += "high"; true }
        registry.set("other", priority = 5) { invoked += "other"; false }

        registry.remove("screen")

        assertFalse(registry.dispatchBackPressed())
        assertEquals(listOf("other"), invoked)
    }

    @Test
    fun `handlers registered without a priority run last`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("default") { invoked += "default"; false }
        registry.set("explicit", priority = Int.MAX_VALUE - 1) { invoked += "explicit"; false }

        assertFalse(registry.dispatchBackPressed())
        assertEquals(listOf("explicit", "default"), invoked)
    }

    /**
     * Both shell screens register at the default priority, so their relative order is what decides
     * whether a back press on the friends/mentions/settings tab returns to the home tab or drives
     * the hidden home panels. The tabs host has to run first.
     */
    @Test
    fun `the tabs host is offered the press before the home surface`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()
        var selectedTabIsHome = false

        registry.set("com.discord.widgets.tabs.WidgetTabsHost") {
            invoked += "tabs"
            !selectedTabIsHome
        }
        registry.set("com.discord.widgets.home.WidgetHome") {
            invoked += "home"
            true
        }

        // On another tab the tabs host consumes the press and home never sees it.
        assertTrue(registry.dispatchBackPressed())
        assertEquals(listOf("tabs"), invoked)

        // On the home tab the tabs host declines and home takes over.
        invoked.clear()
        selectedTabIsHome = true
        assertTrue(registry.dispatchBackPressed())
        assertEquals(listOf("tabs", "home"), invoked)
    }

    @Test
    fun `a screen that re-registers keeps its place behind an earlier screen`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("com.discord.widgets.tabs.WidgetTabsHost") { invoked += "tabs"; false }
        registry.set("com.discord.widgets.home.WidgetHome") { invoked += "stale"; false }

        // A view recreation drops the handler and registers a fresh one.
        registry.remove("com.discord.widgets.home.WidgetHome")
        registry.set("com.discord.widgets.home.WidgetHome") { invoked += "home"; true }

        assertTrue(registry.dispatchBackPressed())
        assertEquals(listOf("tabs", "home"), invoked)
    }

    @Test
    fun `a handler may register another during dispatch`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("screen", priority = 1) {
            invoked += "screen"
            registry.set("pushed", priority = 0) { invoked += "pushed"; true }
            true
        }

        assertTrue(registry.dispatchBackPressed())
        assertEquals(listOf("screen"), invoked)

        assertTrue(registry.dispatchBackPressed())
        assertEquals(listOf("screen", "pushed"), invoked)
    }

    @Test
    fun `a handler may unregister itself during dispatch`() {
        val registry = BackPressHandlerRegistry()
        val invoked = mutableListOf<String>()

        registry.set("self", priority = 1) {
            invoked += "self"
            registry.remove("self")
            true
        }

        assertTrue(registry.dispatchBackPressed())
        assertFalse(registry.dispatchBackPressed())
        assertEquals(listOf("self"), invoked)
        assertTrue(registry.isEmpty)
    }
}
