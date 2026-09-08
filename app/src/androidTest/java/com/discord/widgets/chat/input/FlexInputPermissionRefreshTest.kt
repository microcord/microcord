package com.discord.widgets.chat.input

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.lytefast.flexinput.adapters.EmptyListAdapter
import java.io.File
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
        activity?.let { instrumentation.runOnMainSync(it::finish) }
    }

    @Test
    fun galleryDenialStaysGatedThenGrantRefreshesInPlace() {
        val page = TestMediaFragment()
        val host = launch(page)

        assertFalse(page.granted)
        assertTrue(page.recycler().adapter is EmptyListAdapter)
        assertEquals(0, page.permissionRequests)
        instrumentation.runOnMainSync {
            page.parentFragmentManager.beginTransaction().detach(page).commitNow()
            page.parentFragmentManager.beginTransaction().attach(page).commitNow()
        }
        instrumentation.waitForIdleSync()
        assertEquals(0, page.permissionRequests)

        clickGate(page.recycler())
        assertEquals(1, page.permissionRequests)
        page.deny()
        assertTrue(page.recycler().adapter is EmptyListAdapter)

        clickGate(page.recycler())
        assertEquals(2, page.permissionRequests)
        preserveComposerState(host) { page.grant() }

        assertSame(page, host.page)
        assertFalse(page.recycler().adapter is EmptyListAdapter)
    }

    @Test
    fun systemPhotoPickerNeedsNoGalleryPermissionAndRespectsRemainingSlots() {
        val page = TestMediaFragment()
        val host = launch(page)
        val launches = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                launches.add(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            preserveComposerState(host) {}
            clickGate(page.recycler(), R.id.pick_media_btn)
            instrumentation.waitForIdleSync()
            val request = PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)
            val expected = PickMultipleVisualMedia(9).createIntent(page.requireContext(), request)
            assertEquals(1, launches.size)
            assertEquals(expected.action, launches.last().action)
            assertEquals(expected.type, launches.last().type)
            if (Build.VERSION.SDK_INT >= 33 && expected.action == MediaStore.ACTION_PICK_IMAGES) {
                assertEquals(9, launches.last().getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 0))
            }
            assertEquals(0, page.permissionRequests)
            assertEquals("unsent draft", host.input.text.toString())
            assertEquals(1, host.flexInput.selectionAggregator.size)
            assertTrue(page.recycler().adapter is EmptyListAdapter)

            instrumentation.runOnMainSync {
                repeat(8) { index ->
                    host.flexInput.addExternalAttachment(
                        SourcedAttachment<Any>(
                            id = 100L + index,
                            uri = Uri.parse("content://manticord/selected/$index"),
                            displayName = "selected.png",
                            source = "media_picker",
                        ),
                    )
                }
            }
            clickGate(page.recycler(), R.id.pick_media_btn)
            instrumentation.waitForIdleSync()
            assertEquals(2, launches.size)
            assertEquals(PickVisualMedia().createIntent(page.requireContext(), request).action, launches.last().action)
            assertFalse(launches.last().getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            if (Build.VERSION.SDK_INT >= 33) assertFalse(launches.last().hasExtra(MediaStore.EXTRA_PICK_IMAGES_MAX))

            instrumentation.runOnMainSync {
                host.flexInput.addExternalAttachment(
                    SourcedAttachment<Any>(99L, Uri.parse("content://manticord/last"), "last.png", source = "media_picker"),
                )
            }
            clickGate(page.recycler(), R.id.pick_media_btn)
            instrumentation.waitForIdleSync()
            assertEquals(2, launches.size)
            assertEquals(10, host.flexInput.selectionAggregator.size)
            assertEquals(0, page.permissionRequests)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun pickedMediaKeepsReadAccessAndRejectsOversizedProviderResults() {
        val page = TestMediaFragment()
        val host = launch(page)
        val context = instrumentation.targetContext
        val file = File.createTempFile("picker-fixture-", ".png", File(context.filesDir, "image_provider").apply { mkdirs() })
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        var picked = Intent().apply {
            clipData = ClipData.newRawUri("fixture", uri).apply { addItem(ClipData.Item(uri)) }
        }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent) = Instrumentation.ActivityResult(Activity.RESULT_OK, picked)
        }
        instrumentation.addMonitor(monitor)
        try {
            context.grantUriPermission(context.packageName, uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            clickGate(page.recycler(), R.id.pick_media_btn)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (host.flexInput.selectionAggregator.size == 0 && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20)
                instrumentation.waitForIdleSync()
            }
            assertEquals(1, host.flexInput.selectionAggregator.size)
            assertEquals(uri, host.flexInput.selectionAggregator[0].uri)
            assertEquals("media_picker", (host.flexInput.selectionAggregator[0] as SourcedAttachment<*>).source)
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
            context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }

            picked = Intent().apply {
                clipData = ClipData.newRawUri("fixture", uri).apply {
                    repeat(10) { addItem(ClipData.Item(Uri.parse("content://manticord/overflow/$it"))) }
                }
            }
            clickGate(page.recycler(), R.id.pick_media_btn)
            instrumentation.waitForIdleSync()
            assertEquals(1, host.flexInput.selectionAggregator.size)
            assertEquals(0, page.permissionRequests)
        } finally {
            instrumentation.removeMonitor(monitor)
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete()
        }
    }

    @Test
    fun cameraDenialStaysGatedThenGrantReopensExistingCameraPage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.CAMERA,
            )
        }
        val page = WidgetChatInputAttachments.DiscordCameraFragment()
        val host = launch(page)

        assertSame(page, host.page)
        assertEquals(
            View.GONE,
            page.requireView().findViewById<View>(R.id.permissions_container).visibility,
        )
        assertEquals(
            View.VISIBLE,
            page.requireView().findViewById<View>(R.id.camera_container).visibility,
        )
    }

    private fun launch(page: Fragment): PermissionRefreshHost {
        activity = instrumentation.startActivitySync(
            AppScreenRouter.createScreenIntent(instrumentation.targetContext, Fragment::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity
        val host = PermissionRefreshHost(page)
        instrumentation.runOnMainSync {
            activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity!!.supportFragmentManager.beginTransaction().replace(R.id.app_root, host).commitNow()
        }
        instrumentation.waitForIdleSync()
        return host
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

    private fun clickGate(recyclerView: RecyclerView, buttonId: Int = R.id.action_btn) {
        instrumentation.runOnMainSync {
            @Suppress("UNCHECKED_CAST")
            val adapter = requireNotNull(recyclerView.adapter) as RecyclerView.Adapter<RecyclerView.ViewHolder>
            val holder = adapter.createViewHolder(recyclerView, 0)
            adapter.bindViewHolder(holder, 0)
            assertTrue(holder.itemView.findViewById<View>(buttonId).performClick())
        }
    }
}

class PermissionRefreshHost() : AppFragment() {
    lateinit var page: Fragment
    lateinit var flexInput: AppFlexInputFragment
    lateinit var input: TextView

    constructor(page: Fragment) : this() {
        this.page = page
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.widget_chat_input, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.post {
            flexInput = childFragmentManager.findFragmentById(R.id.chat_input_widget) as AppFlexInputFragment
            flexInput.requireView().findViewById<View>(com.lytefast.flexinput.R.id.emoji_container).visibility =
                View.VISIBLE
            val pageHost = PermissionPageHost(page)
            flexInput.childFragmentManager.beginTransaction()
                .add(com.lytefast.flexinput.R.id.emoji_container, pageHost)
                .commitNow()
            input = flexInput.requireView().findViewById(com.lytefast.flexinput.R.id.text_input)
        }
    }
}

class PermissionPageHost() : Fragment() {
    lateinit var page: Fragment

    constructor(page: Fragment) : this() {
        this.page = page
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = View.generateViewId() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction().add(view.id, page).commitNow()
    }
}

class TestMediaFragment : WidgetChatInputAttachments.DiscordMediaFragment() {
    var granted = false
    var permissionRequests = 0
    private var result: (() -> Unit)? = null

    override fun hasPermissions(vararg requiredPermissionList: String) = granted
    override fun requestGalleryPermission(onGranted: () -> Unit) {
        permissionRequests++
        result = onGranted
    }
    fun deny() { result = null }
    fun grant() { granted = true; requireNotNull(result).invoke(); result = null }
    fun recycler(): RecyclerView = requireView().findViewById(com.lytefast.flexinput.R.id.list)
}
