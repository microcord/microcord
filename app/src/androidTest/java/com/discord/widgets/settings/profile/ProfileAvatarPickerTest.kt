package com.discord.widgets.settings.profile

import android.Manifest
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.view.View
import androidx.core.content.FileProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
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
import com.discord.stores.StoreStream
import com.yalantis.ucrop.UCropActivity
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
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>()
                .forEach(AppActivity::finish)
        }
    }

    @Test
    fun defaultProfileAvatarUsesFragmentOwnedChooserAndCropAcrossRecreation() {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        seedProfile()

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
            UCropActivity::class.java.name,
            ActivityResult(
                Activity.RESULT_OK,
                Intent().putExtra("com.yalantis.ucrop.OutputUri", imageUri),
            ),
            true,
        )

        val activity = instrumentation.startActivitySync(
            AppScreenRouter.createScreenIntent(context, WidgetEditUserOrGuildMemberProfile::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AppActivity
        await { activity.findViewById<View>(R.id.large_avatar)?.hasOnClickListeners() == true }

        instrumentation.runOnMainSync { activity.findViewById<View>(R.id.large_avatar).performClick() }
        onView(withText(R.string.user_settings_upload_avatar)).perform(click())
        await { chooser.hits == 1 && crop.hits == 1 }
        await { activity.findViewById<View>(R.id.save_fab)?.visibility == View.VISIBLE }

        instrumentation.runOnMainSync(activity::recreate)
        val recreated = awaitResumedActivity(activity)
        await { recreated.findViewById<View>(R.id.save_fab)?.visibility == View.VISIBLE }

        instrumentation.runOnMainSync { recreated.findViewById<View>(R.id.large_avatar).performClick() }
        onView(withText(R.string.user_settings_remove_avatar)).perform(click())
        assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.save_fab).visibility)

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

    private fun awaitResumedActivity(previous: AppActivity): AppActivity {
        var resumed: AppActivity? = null
        await {
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AppActivity>()
                    .firstOrNull { it !== previous }
            }
            resumed != null
        }
        return requireNotNull(resumed)
    }

    private fun await(condition: () -> Boolean) {
        repeat(250) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Timed out waiting for profile media state", condition())
    }

}
