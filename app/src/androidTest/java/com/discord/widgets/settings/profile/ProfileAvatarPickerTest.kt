package com.discord.widgets.settings.profile

import android.Manifest
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.discord.R
import com.discord.api.user.User
import com.discord.api.user.UserProfile
import com.discord.app.AppActivity
import com.discord.app.AppScreenRouter
import com.discord.models.domain.ModelPayload
import com.discord.stores.AuthNavigationState
import com.discord.stores.StoreStream
import com.discord.media_picker.ProfileCropActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileAvatarPickerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun cleanUp() {
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(false, false))
        instrumentation.runOnMainSync {
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED, Stage.STARTED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .filterIsInstance<AppActivity>()
                .forEach(AppActivity::finish)
        }
    }

    @Test
    fun defaultProfileAvatarUsesFragmentOwnedChooserAndCropAcrossRecreation() {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            )
        }
        seedProfile()
        StoreStream.getNavigation().updateAuthState(AuthNavigationState(true, true))
        val main = instrumentation.startActivitySync(
            Intent(context, AppActivity.Main::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity.Main
        instrumentation.runOnMainSync {
            AppScreenRouter.launch(main, WidgetEditUserOrGuildMemberProfile::class.java)
        }
        val activity = awaitResumedActivity(main)
        instrumentation.runOnMainSync { activity.supportFragmentManager.executePendingTransactions() }

        val image = File(context.filesDir, "image_provider/profile-avatar-picker.png").also { file ->
            file.parentFile?.mkdirs()
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val imageUri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", image)
        val chooser = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CHOOSER),
            ActivityResult(Activity.RESULT_OK, Intent().setData(imageUri)),
            true,
        )
        val crop = instrumentation.addMonitor(
            ProfileCropActivity::class.java.name,
            ActivityResult(
                Activity.RESULT_OK,
                Intent()
                    .setData(imageUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra("com.yalantis.ucrop.OutputUri", imageUri),
            ),
            true,
        )

        await { activity.findViewById<View>(R.id.large_avatar)?.hasOnClickListeners() == true }

        instrumentation.runOnMainSync { activity.findViewById<View>(R.id.large_avatar).performClick() }
        clickAvatarOption(activity, 0, activity.getString(R.string.user_settings_upload_avatar))
        await { activity.findViewById<View>(R.id.save_fab)?.visibility == View.VISIBLE }

        instrumentation.runOnMainSync(activity::recreate)
        val recreated = awaitResumedActivity(activity)
        await { recreated.findViewById<View>(R.id.save_fab)?.visibility == View.VISIBLE }

        instrumentation.runOnMainSync { recreated.findViewById<View>(R.id.large_avatar).performClick() }
        clickAvatarOption(recreated, 1, recreated.getString(R.string.user_settings_remove_avatar))
        assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.save_fab).visibility)

        instrumentation.runOnMainSync { recreated.onBackPressed() }
        await { recreated.hasFragment(ProfileDiscardChangesDialog::class.java) }
        clickFragmentView(recreated, ProfileDiscardChangesDialog::class.java, R.id.view_dialog_confirmation_cancel)
        assertTrue(!recreated.isFinishing)
        await { !recreated.hasFragment(ProfileDiscardChangesDialog::class.java) }

        await { recreated.findViewById<View>(R.id.banner)?.hasOnClickListeners() == true }
        instrumentation.runOnMainSync { recreated.findViewById<View>(R.id.banner).performClick() }
        await { recreated.hasFragment(WidgetEditProfileBannerSheet::class.java) }
        clickFragmentView(recreated, WidgetEditProfileBannerSheet::class.java, R.id.banner_color_item_container)
        await { recreated.hasFragment(ProfileColorPickerDialog::class.java) }
        clickFragmentView(recreated, ProfileColorPickerDialog::class.java, R.id.profile_color_select)
        await { !recreated.hasFragment(ProfileColorPickerDialog::class.java) }

        instrumentation.removeMonitor(chooser)
        instrumentation.removeMonitor(crop)
    }

    private fun seedProfile() {
        val user = User(1L, "profile-user", verified = true)
        val payload = ModelPayload().also {
            ModelPayload::class.java.getDeclaredField("me").apply { isAccessible = true }.set(it, user)
        }
        val stream = StoreStream::class.java.getDeclaredField("instance").run {
            isAccessible = true
            get(null) as StoreStream
        }
        stream.dispatcher.schedule {
            StoreStream.getUsers().handleConnectionOpen(payload)
            StoreStream.getUserProfiles().handleUserProfile(UserProfile(user = user))
        }
        await { StoreStream.getUsers().getMe().id == user.id }

        StoreStream.getUserProfiles().javaClass.getDeclaredField("profilesLoading").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(StoreStream.getUserProfiles()) as MutableSet<Long>).add(user.id)
        }
    }

    private fun awaitResumedActivity(previous: AppActivity? = null): AppActivity {
        repeat(250) {
            var activity: AppActivity? = null
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .asSequence()
                    .filterIsInstance<AppActivity>()
                    .firstOrNull { it !== previous }
            }
            if (activity != null) return requireNotNull(activity)
            Thread.sleep(20)
        }
        error("Timed out waiting for profile activity")
    }

    private fun clickAvatarOption(activity: AppActivity, position: Int, expectedText: String) {
        repeat(250) {
            var clicked = false
            instrumentation.runOnMainSync {
                val sheet = activity.fragment(ProfileAvatarSelectorSheet::class.java)
                val recycler = sheet?.view?.findViewById<RecyclerView>(R.id.widget_simple_bottom_sheet_selector_recycler)
                val row = recycler?.findViewHolderForAdapterPosition(position)?.itemView
                if (row != null) {
                    assertEquals(
                        expectedText,
                        row.findViewById<TextView>(R.id.select_component_sheet_item_title).text.toString(),
                    )
                    clicked = row.performClick()
                }
            }
            if (clicked) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for profile avatar selector row", false)
    }

    private fun <T : Fragment> clickFragmentView(activity: AppActivity, type: Class<T>, viewId: Int) {
        instrumentation.runOnMainSync {
            val fragment = requireNotNull(activity.fragment(type))
            assertTrue(fragment.requireView().findViewById<View>(viewId).performClick())
        }
    }

    private fun AppActivity.hasFragment(type: Class<out Fragment>): Boolean {
        var found = false
        instrumentation.runOnMainSync { found = fragments().any { type.isInstance(it) && it.view != null } }
        return found
    }

    private fun <T : Fragment> AppActivity.fragment(type: Class<T>): T? =
        fragments().firstOrNull { type.isInstance(it) && it.view != null }?.let(type::cast)

    private fun AppActivity.fragments(): Sequence<Fragment> =
        supportFragmentManager.fragments.asSequence().flatMap { it.withChildren() }

    private fun Fragment.withChildren(): Sequence<Fragment> =
        sequenceOf(this) + childFragmentManager.fragments.asSequence().flatMap { it.withChildren() }

    private fun await(condition: () -> Boolean) {
        repeat(250) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for profile media state", condition())
    }

}
