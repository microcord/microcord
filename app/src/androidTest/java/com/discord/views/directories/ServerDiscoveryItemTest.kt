package com.discord.views.directories

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerDiscoveryItemTest {
    @Test
    fun bindsDirectStateAndActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = ServerDiscoveryItem(instrumentation.targetContext, null)
            val description = view.findViewById<TextView>(R.id.server_discovery_item_description)
            val image = view.findViewById<View>(R.id.server_discovery_item_image)
            val join = view.findViewById<View>(R.id.server_discovery_item_button)
            val joined = view.findViewById<View>(R.id.server_discovery_item_button_joined)

            view.setDescription(null)
            assertEquals(View.GONE, description.visibility)
            view.setDescription("A guild")
            assertEquals(View.VISIBLE, description.visibility)
            view.setMembers(12)
            view.setOnline(3)
            assertEquals(View.NO, image.importantForAccessibility)

            var clicked = false
            view.setJoinButtonOnClickListener { clicked = true }
            view.setJoinedGuild(false)
            assertEquals(View.VISIBLE, join.visibility)
            assertEquals(View.GONE, joined.visibility)
            join.performClick()
            assertTrue(clicked)

            view.setJoinedGuild(true)
            assertEquals(View.GONE, join.visibility)
            assertEquals(View.VISIBLE, joined.visibility)
        }
    }
}
