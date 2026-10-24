package com.discord.stores

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.discord.stores.authentication.AccountTokens
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rx.schedulers.Schedulers

class StoreMultiAccountNavigationTest {

    @Test
    fun `navigation survives a restart and is dropped with the account`() {
        val preferences = memoryPreferences()
        val saved = StoreMultiAccount.Navigation(setOf(-5L, 7L), mapOf(10L to 11L, 0L to 12L), listOf(0L, 10L))
        store(preferences).saveNavigation(1L, saved)

        val restarted = store(preferences)
        assertEquals(saved, restarted.getNavigation(1L))

        restarted.removeAccountInternal(1L)
        assertNull(store(preferences).getNavigation(1L))
    }

    private fun store(preferences: SharedPreferences) =
        StoreMultiAccount(Dispatcher(Schedulers.immediate()), AccountTokens(preferences), preferences)
            .apply { init(ContextWrapper(null)) }

    private fun memoryPreferences(): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        lateinit var editor: Any
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> editor.also { values[args[0] as String] = args[1] }
                "remove" -> editor.also { values.remove(args[0] as String) }
                "commit" -> true
                "apply" -> null
                else -> editor
            }
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
}
