package com.discord.widgets.servers

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerSettingsOverviewLayoutTest {
    @Test
    fun overviewIncludesExposeRequiredBindingRootsAndControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            val view = LayoutInflater.from(context).inflate(R.layout.widget_server_settings_overview, null)
            val headerRoot = view.findViewById<View>(R.id.header)
            val systemChannelRoot = view.findViewById<View>(R.id.systemChannel)
            val bannerRoot = view.findViewById<View>(R.id.uploadBanner)
            val animatedUpsellRoot = view.findViewById<View>(R.id.animated_banner_upsell)

            assertNotNull(headerRoot)
            assertSame(headerRoot, view.findViewById<View>(R.id.server_settings_overview_icon).parent)
            assertNotNull(view.findViewById<View>(R.id.server_settings_overview_icon_text))
            assertNotNull(view.findViewById<View>(R.id.icon_label))
            assertNotNull(view.findViewById<View>(R.id.server_settings_overview_icon_remove))
            assertNotNull(view.findViewById<View>(R.id.overview_name))
            assertNotNull(systemChannelRoot)
            assertSame(systemChannelRoot, view.findViewById<View>(R.id.system_channel).parent)
            assertNotNull(view.findViewById<View>(R.id.system_channel_join))
            assertNotNull(view.findViewById<View>(R.id.system_channel_join_replies))
            assertNotNull(view.findViewById<View>(R.id.system_channel_boost))
            assertNotNull(bannerRoot)
            assertNotNull(animatedUpsellRoot)
            assertSame(bannerRoot, animatedUpsellRoot.parent)
            assertSame(animatedUpsellRoot, view.findViewById<View>(R.id.animated_banner_upsell_text).parent)
            assertNotNull(view.findViewById<View>(R.id.animated_banner_upsell_button))
            assertNotNull(view.findViewById<View>(R.id.server_settings_overview_upload_banner_container))
            assertNotNull(view.findViewById<View>(R.id.server_settings_overview_upload_banner_remove))
            assertNotNull(view.findViewById<View>(R.id.upload_banner))
            assertNotNull(view.findViewById<View>(R.id.upload_banner_unlock))
            assertNotNull(view.findViewById<View>(R.id.upload_banner_nitro_tier))
        }
    }

    @Test
    fun animatedBannerUpsellRootIsOverriddenOnlyWhenIncluded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            val inflater = LayoutInflater.from(context)
            val direct = inflater.inflate(R.layout.view_server_settings_upload_banner_animated_upsell, null)
            val overview = inflater.inflate(R.layout.widget_server_settings_overview, null)

            assertEquals(R.id.animated_banner_upsell_container, direct.id)
            assertNotNull(direct.findViewById<View>(R.id.animated_banner_upsell_text))
            assertNotNull(direct.findViewById<View>(R.id.animated_banner_upsell_button))
            assertEquals(R.id.animated_banner_upsell, overview.findViewById<View>(R.id.animated_banner_upsell).id)
            assertNull(overview.findViewById<View>(R.id.animated_banner_upsell_container))
        }
    }
}
