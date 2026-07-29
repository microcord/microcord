package com.discord.widgets.guild_role_subscriptions.tier.create

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.LayoutInflater
import com.discord.R
import com.discord.app.AppScreenRouter
import com.discord.views.steps.StepsView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetCreateGuildRoleSubscriptionTierTest {

    @Test
    fun launchCarriesExactIdsAndLayoutInflates() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        var launched: Intent? = null
        val context = object : ContextWrapper(targetContext) {
            override fun startActivity(intent: Intent) {
                launched = intent
            }
        }

        WidgetCreateGuildRoleSubscriptionTier.launch(context, 12L, 34L)

        assertEquals(WidgetCreateGuildRoleSubscriptionTier::class.java, AppScreenRouter.screenOf(launched))
        assertEquals(12L, launched?.getLongExtra(WidgetCreateGuildRoleSubscriptionTier.INTENT_EXTRA_GUILD_ID, 0L))
        assertEquals(
            34L,
            launched?.getLongExtra(WidgetCreateGuildRoleSubscriptionTier.INTENT_EXTRA_GROUP_LISTING_ID, 0L),
        )
        val view = LayoutInflater.from(targetContext)
            .inflate(R.layout.widget_create_guild_role_subscription_tier, null)
        assertNotNull(view.findViewById<StepsView>(R.id.create_guild_subscription_role_tier_steps_view))
    }
}
