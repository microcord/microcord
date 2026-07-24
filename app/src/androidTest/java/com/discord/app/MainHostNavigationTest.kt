package com.discord.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.stores.AuthNavigationState
import com.discord.stores.MainRoute
import com.discord.stores.RequiredAction
import com.discord.stores.StoreStream
import com.discord.utilities.intent.AppIntentContract
import com.discord.utilities.intent.DeepLinkRoute
import com.discord.utilities.intent.LinkParseResult
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.home.BackHandler
import com.discord.widgets.home.HomePanelsLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainHostNavigationTest {
    @After
    fun cleanUp() {
        AppActivity.routeFragmentFactory = null
        AppActivity.authSubmitter = null
        WidgetTabsHost.homeFragmentFactory = null
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(false, false))
        StoreStream.getNavigation().getPendingIntent()?.let(StoreStream.getNavigation()::consumePendingIntent)
    }

    @Test
    fun deepLinkWaitsBehindAuthAndMalformedIntentIsIgnored() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val navigation = StoreStream.getNavigation()
        AppActivity.routeFragmentFactory = { TestRouteFragment.newInstance(it.toString()) }
        navigation.updateAuthState(AuthNavigationState(true, false))
        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://discord.com/channels/10/20"), instrumentation.targetContext, AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main

        assertRoute(activity, MainRoute.LoggedOut.toString())
        assertEquals(
            AppIntentContract.Link(LinkParseResult.Internal(DeepLinkRoute.Channel(10, 20, null))),
            navigation.getPendingIntent(),
        )
        instrumentation.targetContext.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("file:///tmp/token"), instrumentation.targetContext, AppActivity.Main::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        instrumentation.waitForIdleSync()
        assertTrue(navigation.getPendingIntent() is AppIntentContract.Link)
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun manifestRoutesLinksAndAppActionsToTheirSafeEntryPoints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageManager = context.packageManager

        assertEquals(
            AppActivity.Main::class.java.name,
            packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://discord.com/channels/10/20")).setPackage(context.packageName),
                0,
            )?.activityInfo?.name,
        )
        assertEquals(
            AppActionActivity::class.java.name,
            packageManager.resolveActivity(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName), 0)
                ?.activityInfo?.name,
        )
        assertNull(
            packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://discord.com/channels/10/20")).setPackage(context.packageName),
                0,
            ),
        )

        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, false))
        val monitor = instrumentation.addMonitor(AppActivity.Main::class.java.name, null, false)
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setClass(context, AppActionActivity::class.java)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "shared")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val main = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as? AppActivity.Main
        instrumentation.removeMonitor(monitor)
        assertNotNull(main)
        assertEquals("shared", (StoreStream.getNavigation().getPendingIntent() as AppIntentContract.Share).content.text)
        instrumentation.runOnMainSync(main!!::finish)
    }

    @Test
    fun fakeAuthStatesRouteLoggedOutRequiredActionAndLoggedIn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val navigation = StoreStream.getNavigation()
        AppActivity.routeFragmentFactory = { TestRouteFragment.newInstance(it.toString()) }
        navigation.updateAuthState(AuthNavigationState(true, false))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AppActivity.Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main

        assertRoute(activity, MainRoute.LoggedOut.toString())
        navigation.updateAuthState(AuthNavigationState(true, true, true, RequiredAction.UPDATE_PASSWORD))
        assertRoute(activity, MainRoute.Required(RequiredAction.UPDATE_PASSWORD).toString())
        navigation.updateAuthState(AuthNavigationState(true, true, true))
        assertRoute(activity, MainRoute.LoggedIn.toString())
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun loggedInRouteCreatesTabsAndHomeHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WidgetTabsHost.homeFragmentFactory = ::TestHomeFragment
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true, true))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AppActivity.Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main

        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.supportFragmentManager.executePendingTransactions() }
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.app_root) is WidgetTabsHost)
        assertTrue(activity.findViewById<View>(R.id.widget_tabs_host_bottom_navigation_view).isShown)
        assertTrue(activity.findViewById<View>(R.id.overlapping_panels).isShown)
        instrumentation.runOnMainSync(activity::finish)
    }

    @Test
    fun loggedInHostRestoresAfterActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WidgetTabsHost.homeFragmentFactory = ::TestHomeFragment
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true, true))
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AppActivity.Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main

        val monitor = instrumentation.addMonitor(AppActivity.Main::class.java.name, null, false)
        instrumentation.runOnMainSync(activity::recreate)
        val recreated = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as AppActivity.Main
        instrumentation.removeMonitor(monitor)
        instrumentation.runOnMainSync { recreated.supportFragmentManager.executePendingTransactions() }
        assertTrue(recreated.supportFragmentManager.findFragmentById(R.id.app_root) is WidgetTabsHost)
        assertTrue(recreated.findViewById<View>(R.id.overlapping_panels).isShown)
        instrumentation.runOnMainSync(recreated::finish)
    }

    private fun assertRoute(activity: AppActivity, expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { activity.supportFragmentManager.executePendingTransactions() }
        assertEquals(expected, activity.findViewById<TextView>(android.R.id.text1).text.toString())
    }

    class TestRouteFragment : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            TextView(requireContext()).apply {
                id = android.R.id.text1
                text = requireArguments().getString(ARG_ROUTE)
            }

        companion object {
            private const val ARG_ROUTE = "route"
            fun newInstance(route: String) = TestRouteFragment().apply {
                arguments = Bundle().apply { putString(ARG_ROUTE, route) }
            }
        }
    }

    class TestHomeFragment : Fragment(), BackHandler {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            HomePanelsLayout(requireContext()).apply {
                id = R.id.overlapping_panels
                repeat(3) { addView(FrameLayout(context), FrameLayout.LayoutParams(-1, -1)) }
            }

        override fun onBackPressed() = false
    }
}
