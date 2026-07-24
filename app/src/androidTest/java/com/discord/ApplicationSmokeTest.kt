package com.discord

import android.content.Intent
import android.os.Build
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.app.App
import com.discord.app.AppActivity
import com.discord.stores.StoreStream
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.utilities.lifecycle.ApplicationProvider
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.ClockFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationSmokeTest {
    @Test
    fun applicationAndXmlActivityLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        assertEquals("dev.mantikafasi.manticord", context.packageName)
        assertTrue(context.applicationContext is App)
        assertSame(context.applicationContext, ApplicationProvider.get())
        assertNotNull(SharedPreferencesProvider.get())
        assertNotNull(SharedPreferencesProvider.getPrefsSessionDurable())
        assertTrue(ClockFactory.get().currentTimeMillis() > 0)
        assertSame(StoreStream.initialize(context), StoreStream.initialize(context))
        assertTrue(Persister.isPreloaded().toBlocking().first())

        instrumentation.runOnMainSync { (context.applicationContext as App).onCreate() }
        val settings = StoreStream.getUserSettingsSystem()
        settings.setTheme("light", apiSync = false)
        settings.setLocale("tr", apiSync = false)
        settings.observeSettings(false)
            .filter { it.theme == "light" && it.locale == "tr" }
            .toBlocking()
            .first()

        val activity = instrumentation.startActivitySync(
            Intent(context, AppActivity.Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertNotNull(activity.findViewById(R.id.app_root))
        assertSame(activity, ActivityProvider.getActivity())
        val themeName = TypedValue()
        assertTrue(activity.theme.resolveAttribute(R.attr.theme_name, themeName, true))
        assertEquals("light", themeName.string.toString())
        val locale = if (Build.VERSION.SDK_INT >= 24) {
            activity.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            activity.resources.configuration.locale
        }
        assertEquals("tr", locale.language)
        instrumentation.runOnMainSync(activity::finish)
        settings.setTheme("dark", apiSync = false)
        settings.setLocale(null, apiSync = false)
    }
}
