package dev.mantikafasi.manticord.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records app startup. With a `profileToken` instrumentation argument (see this module's
 * build file) the first run logs in through "Log in with token", so every later run starts
 * authenticated and the profile covers store hydration and the main screen, not only the
 * login page. The profiled build has its own package, so the installed app is never touched.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.pkg(PACKAGE_NAME)), 10_000))
        if (!PROFILE_TOKEN.isNullOrBlank() && device.wait(Until.hasObject(id("auth_landing_login")), 5_000)) {
            device.logIn(PROFILE_TOKEN)
        }
        if (!PROFILE_TOKEN.isNullOrBlank()) {
            // The bottom nav only shows with the channel list open; a restored chat hides it.
            check(device.wait(Until.hasObject(id("widget_tabs_host_container")), 180_000)) {
                "Main screen never appeared; is profileToken valid?"
            }
            device.wait(Until.gone(id("loading")), 60_000)
        }
        device.waitForIdle()
    }

    private fun UiDevice.logIn(token: String) {
        findObject(id("auth_landing_login")).click()
        check(wait(Until.hasObject(id("auth_login_token_link")), 10_000))
        findObject(id("auth_login_token_link")).click()
        check(wait(Until.hasObject(id("view_input")), 10_000))
        findObject(id("view_input")).findObject(By.clazz("android.widget.EditText")).text = token
        findObject(id("notice_ok")).click()
    }

    private companion object {
        private val ARGUMENTS = InstrumentationRegistry.getArguments()
        val PACKAGE_NAME: String = ARGUMENTS.getString("targetAppId") ?: "dev.mantikafasi.manticord.benchmark"
        val PROFILE_TOKEN: String? = ARGUMENTS.getString("profileToken")

        /** The profiled build's resource package differs from its namespace, so match the entry name only. */
        fun id(name: String): BySelector = By.res(Pattern.compile(".*:id/$name"))
    }
}
