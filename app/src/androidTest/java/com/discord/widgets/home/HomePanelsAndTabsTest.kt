package com.discord.widgets.home

import android.os.Parcelable
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.panels.OverlappingPanelsLayout
import com.discord.models.user.CoreUser
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.tabs.TabsHostBottomNavigationView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePanelsAndTabsTest {
    @Test
    fun panelsMeasureOpenRestoreAndHideCenterFromAccessibility() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            val panels = HomePanelsLayout(context).apply {
                id = View.generateViewId()
                repeat(3) { addView(FrameLayout(context), FrameLayout.LayoutParams(-1, -1)) }
                measure(exactly(1080), exactly(1920))
                layout(0, 0, 1080, 1920)
                setStartPanelLockState(OverlappingPanelsLayout.LockState.OPEN)
            }
            assertEquals(OverlappingPanelsLayout.Panel.START, panels.getSelectedPanel())
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, panels.getChildAt(1).importantForAccessibility)

            val state = SparseArray<Parcelable>()
            panels.saveHierarchyState(state)
            val restored = HomePanelsLayout(context).apply {
                id = panels.id
                repeat(3) { addView(FrameLayout(context), FrameLayout.LayoutParams(-1, -1)) }
                restoreHierarchyState(state)
                measure(exactly(1080), exactly(1920))
                layout(0, 0, 1080, 1920)
            }
            assertEquals(OverlappingPanelsLayout.Panel.START, restored.getSelectedPanel())
        }
    }

    @Test
    fun tabsApplySelectionBadgesClicksAndHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
            val tabs = TabsHostBottomNavigationView(context)
            var selected: NavigationTab? = null
            var height = 0
            tabs.addHeightChangedListener { height = it }
            tabs.updateView(
                NavigationTab.MENTIONS,
                { selected = it },
                true,
                CoreUser(42L),
                null,
                setOf(NavigationTab.HOME, NavigationTab.MENTIONS, NavigationTab.SETTINGS),
                3,
                0,
                {},
                {},
                {},
            )
            tabs.measure(exactly(1080), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            tabs.layout(0, 0, 1080, tabs.measuredHeight)
            tabs.findViewById<View>(R.id.tabs_host_bottom_nav_home_item).performClick()
            assertEquals(NavigationTab.HOME, selected)
            assertEquals(View.VISIBLE, tabs.findViewById<View>(R.id.tabs_host_bottom_nav_home_notifications_badge).visibility)
            assertEquals(View.GONE, tabs.findViewById<View>(R.id.tabs_host_bottom_nav_friends_notifications_badge).visibility)
            assertEquals(tabs.measuredHeight, height)
        }
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
