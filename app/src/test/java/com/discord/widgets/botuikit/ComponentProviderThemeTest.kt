package com.discord.widgets.botuikit

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentProviderThemeTest {
    @Test
    fun providerDoesNotRetainActivityContext() {
        assertTrue(ComponentProvider::class.java.declaredConstructors.any { it.parameterCount == 0 })
        assertFalse(ComponentProvider::class.java.declaredFields.any { Context::class.java.isAssignableFrom(it.type) })
    }
}
