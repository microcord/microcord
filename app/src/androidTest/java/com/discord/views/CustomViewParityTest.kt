package com.discord.views

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.api.premium.PremiumTier
import com.discord.rlottie.RLottieDrawable
import com.discord.views.guildboost.GuildBoostMarketingView
import com.discord.views.guilds.ServerMemberCount
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomViewParityTest {
    @Test
    fun memberCountSubstitutesNamedPlural() = onMain {
        val view = ServerMemberCount(themedContext(), null)

        view.setMembers(42)

        val text = view.findViewById<TextView>(R.id.item_invite_total_member_text).text.toString()
        assertEquals("42 Members", text)
        assertFalse(text.contains("{members}"))
    }

    @Test
    fun guildSubscriptionOnlyUsesNonPremiumHeading() = onMain {
        val context = themedContext()
        val view = GuildBoostMarketingView(context)

        view.configure(PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY) {}

        assertEquals(
            context.getString(R.string.guild_settings_premium_upsell_heading_secondary),
            view.findViewById<TextView>(R.id.view_premium_marketing_marketing_title).text,
        )
    }

    @Test
    fun rlottieDestroyIsSafeDuringInitialDecode() {
        lateinit var drawable: RLottieDrawable
        onMain {
            val context = themedContext()
            drawable = RLottieDrawable(
                context,
                R.raw.anim_friends_add_nearby_looking,
                "lifecycle-test",
                32,
                32,
                60f,
                true,
                null,
            )
            drawable.destroy()
        }
        Thread.sleep(250)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse(drawable.isRunning())
    }

    private fun themedContext(): ContextThemeWrapper {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration).apply { setLocale(Locale.US) }
        return ContextThemeWrapper(
            target.createConfigurationContext(configuration),
            R.style.AppTheme_Dark,
        )
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }
}
