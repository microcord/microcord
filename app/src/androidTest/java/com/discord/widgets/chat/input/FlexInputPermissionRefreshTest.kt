package com.discord.widgets.chat.input

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.lytefast.flexinput.adapters.FileListAdapter
import com.lytefast.flexinput.adapters.PhotoCursorAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlexInputPermissionRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: AppActivity? = null

    @After
    fun tearDown() {
        AppActivity.routeFragmentFactory = null
        activity?.let { instrumentation.runOnMainSync(it::finish) }
    }

    @Test
    fun galleryDenialStaysGatedThenGrantRefreshesInPlace() {
        val page = TestMediaFragment()
        val host = launch(page)

        assertFalse(page.granted)
        assertFalse(page.recycler().adapter is PhotoCursorAdapter)
        page.deny()
        assertFalse(page.recycler().adapter is PhotoCursorAdapter)

        clickGate(page.recycler())
        preserveComposerState(host) { page.grant() }

        assertSame(page, host.page)
        assertTrue(page.recycler().adapter is PhotoCursorAdapter)
    }

    @Test
    fun filesDenialStaysGatedThenGrantRefreshesInPlace() {
        val page = TestFilesFragment()
        val host = launch(page)

        clickGate(page.recycler())
        page.deny()
        assertFalse(page.recycler().adapter is FileListAdapter)

        clickGate(page.recycler())
        preserveComposerState(host) { page.grant() }

        assertSame(page, host.page)
        assertTrue(page.recycler().adapter is FileListAdapter)
    }

    @Test
    fun cameraDenialStaysGatedThenGrantReopensExistingCameraPage() {
        val page = TestCameraFragment()
        val host = launch(page)

        page.deny()
        assertEquals(View.VISIBLE, page.permissionsVisibility())
        page.requestAgain()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.CAMERA,
            )
        }
        preserveComposerState(host) { page.grant() }

        assertSame(page, host.page)
        assertEquals(View.GONE, page.permissionsVisibility())
        assertEquals(View.VISIBLE, page.cameraVisibility())
    }

    private fun launch(page: Fragment): PermissionRefreshHost {
        AppActivity.routeFragmentFactory = { PermissionRefreshHost(page) }
        activity = instrumentation.startActivitySync(
            AppScreenRouter.createScreenIntent(instrumentation.targetContext, WidgetChatInput::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity
        instrumentation.waitForIdleSync()
        return activity!!.supportFragmentManager.findFragmentById(R.id.app_root) as PermissionRefreshHost
    }

    private fun preserveComposerState(host: PermissionRefreshHost, grant: () -> Unit) {
        instrumentation.runOnMainSync {
            host.input.setText("unsent draft")
            host.flexInput.addExternalAttachment(
                SourcedAttachment<Any>(
                    id = 7L,
                    uri = Uri.parse("content://manticord/selected"),
                    displayName = "selected.png",
                    source = "media_picker",
                ),
            )
            grant()
            assertEquals("unsent draft", host.input.text.toString())
            assertEquals(1, host.flexInput.selectionAggregator.size)
        }
        instrumentation.waitForIdleSync()
    }

    private fun clickGate(recyclerView: RecyclerView) {
        instrumentation.runOnMainSync {
            val holder = requireNotNull(recyclerView.adapter).onCreateViewHolder(recyclerView, 0)
            assertTrue(holder.itemView.findViewById<View>(R.id.action_btn).performClick())
        }
    }
}

private class PermissionRefreshHost(val page: Fragment) : AppFragment() {
    lateinit var flexInput: AppFlexInputFragment
    lateinit var input: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = View.generateViewId() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        flexInput = AppFlexInputFragment()
        childFragmentManager.beginTransaction().add(view.id, flexInput).commitNow()
        val pageHost = PermissionPageHost(page)
        flexInput.childFragmentManager.beginTransaction()
            .add(com.lytefast.flexinput.R.id.emoji_container, pageHost)
            .commitNow()
        input = flexInput.requireView().findViewById(com.lytefast.flexinput.R.id.text_input)
    }
}

private class PermissionPageHost(private val page: Fragment) : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = View.generateViewId() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction().add(view.id, page).commitNow()
    }
}

private class TestMediaFragment : WidgetChatInputAttachments.DiscordMediaFragment() {
    var granted = false
    private var result: (() -> Unit)? = null

    override fun hasPermissions(vararg requiredPermissionList: String) = granted
    override fun requestGalleryPermission(onGranted: () -> Unit) { result = onGranted }
    fun deny() { result = null }
    fun grant() { granted = true; requireNotNull(result).invoke(); result = null }
    fun recycler(): RecyclerView = requireView().findViewById(com.lytefast.flexinput.R.id.list)
}

private class TestFilesFragment : WidgetChatInputAttachments.DiscordFilesFragment() {
    var granted = false
    private var result: (() -> Unit)? = null

    override fun hasPermissions(vararg requiredPermissionList: String) = granted
    override fun requestGalleryPermission(onGranted: () -> Unit) { result = onGranted }
    fun deny() { result = null }
    fun grant() { granted = true; requireNotNull(result).invoke(); result = null }
    fun recycler(): RecyclerView = requireView().findViewById(com.lytefast.flexinput.R.id.list)
}

private class TestCameraFragment : WidgetChatInputAttachments.DiscordCameraFragment() {
    var granted = false
    private var result: (() -> Unit)? = null

    override fun hasPermissions(vararg requiredPermissionList: String) = granted
    override fun requestCameraPermission(onGranted: () -> Unit) { result = onGranted }
    fun requestAgain() = requestPermissionClick()
    fun deny() { result = null }
    fun grant() { granted = true; requireNotNull(result).invoke(); result = null }
    fun permissionsVisibility() = permissionsContainer.visibility
    fun cameraVisibility() = cameraContainer.visibility
}
