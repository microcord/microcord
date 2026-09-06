package com.discord.widgets.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentController
import androidx.fragment.app.FragmentHostCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.discord.BuildConfig
import com.discord.R
import com.discord.app.AppActivity
import com.discord.app.AppScreenRouter
import com.discord.databinding.ViewSettingSwitchBinding
import com.discord.databinding.WidgetSettingsUpdatesBinding
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.ClientSettings
import com.discord.utilities.updates.AppUpdate
import com.discord.utilities.updates.AppUpdater
import com.discord.utilities.updates.AppUpdater.Phase
import com.discord.utilities.updates.AppUpdater.State
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rx.Subscription
import rx.subjects.BehaviorSubject

@RunWith(AndroidJUnit4::class)
class WidgetSettingsUpdatesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun generatedScreenRestoresToggleAndRendersSyntheticUpdaterStates() {
        val statesField = AppUpdater::class.java.getDeclaredField("states").apply { isAccessible = true }
        val restoredField = AppUpdater::class.java.getDeclaredField("restored").apply { isAccessible = true }
        val pollingField = AppUpdater::class.java.getDeclaredField("polling").apply { isAccessible = true }
        val noticeField = AppUpdater::class.java.getDeclaredField("queuedNoticeSha").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val states = statesField.get(AppUpdater) as BehaviorSubject<State>
        val (originalState, originalRestored) = onMain {
            assumeTrue("Do not replace an active updater screen", !states.hasObservers())
            assumeTrue("Do not interrupt updater work", AppUpdater.state.phase in setOf(
                Phase.IDLE, Phase.CURRENT, Phase.AVAILABLE, Phase.LEGACY, Phase.ERROR,
            ))
            assumeTrue("Do not interrupt download polling",
                (pollingField.get(AppUpdater) as Subscription?)?.isUnsubscribed != false)
            // Disabling checks must not mark an existing notice seen in StoreStream.
            assumeTrue("Do not mutate an existing update notice", noticeField.get(AppUpdater) == null)
            AppUpdater.state to restoredField.getBoolean(AppUpdater)
        }
        val preferences = SharedPreferencesProvider.get()
        val setting = ClientSettings.automaticUpdateChecks
        val originalAutomatic = if (preferences.contains(setting.key)) preferences.getBoolean(setting.key, true) else null
        val runtime = instrumentation.targetContext.getSharedPreferences("manticord_updater_runtime", Context.MODE_PRIVATE)
        val originalNextCheck = if (runtime.contains("next_check")) runtime.getLong("next_check", 0) else null
        val versionCode = maxOf(
            BuildConfig.VERSION_CODE.toLong() + 1,
            BuildConfig.UPDATE_COMMIT_TIMESTAMP + 86_400 - 1_577_836_800L,
            System.currentTimeMillis() / 1000 + 86_400 - 1_577_836_800L,
        )
        val update = AppUpdate(
            release = "build-2147483647",
            apkUrl = "https://microcord.mantikafasi.dev/releases/build-2147483647.apk",
            sha256 = "a".repeat(64),
            size = 8_388_608L,
            commitSha = if (BuildConfig.UPDATE_COMMIT_SHA.equals("a".repeat(40), ignoreCase = true))
                "b".repeat(40) else "a".repeat(40),
            commitTimestamp = versionCode + 1_577_836_800L,
            versionCode = versionCode,
            versionName = "instrumentation-fixture",
            packageName = "dev.mantikafasi.manticord",
        ).validate()
        val available = State(Phase.AVAILABLE, update)
        val checkedNetwork = AtomicBoolean(false)
        var observation: Subscription? = null
        var controller: FragmentController? = null
        lateinit var fragment: WidgetSettingsUpdates
        lateinit var binding: WidgetSettingsUpdatesBinding

        fun assertRendered(state: State, status: Int, action: Int? = null,
            checkEnabled: Boolean = true, progress: Int? = null, indeterminate: Boolean = false) {
            onMain { states.onNext(state) }
            instrumentation.waitForIdleSync()
            onMain {
                val context = binding.root.context
                assertEquals(context.getString(status), binding.updaterStatus.text.toString())
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, binding.updaterStatus.accessibilityLiveRegion)
                assertEquals(checkEnabled, binding.updaterCheck.isEnabled)
                assertEquals(if (action == null) View.GONE else View.VISIBLE, binding.updaterAction.visibility)
                if (action != null) {
                    assertEquals(context.getString(action), binding.updaterAction.text.toString())
                    assertTrue(binding.updaterAction.isEnabled)
                }
                assertEquals(if (progress != null || indeterminate) View.VISIBLE else View.GONE,
                    binding.updaterProgress.visibility)
                if (progress != null) {
                    assertFalse(binding.updaterProgress.isIndeterminate)
                    assertEquals(progress.toInt(), binding.updaterProgress.progress)
                }
                if (indeterminate) assertTrue(binding.updaterProgress.isIndeterminate)
                assertEquals(if (progress != null) View.VISIBLE else View.GONE, binding.updaterBytes.visibility)
                assertEquals(if (progress != null) View.VISIBLE else View.GONE, binding.updaterCancel.visibility)
                if (progress != null) assertEquals(context.getString(R.string.updater_download_progress,
                    Formatter.formatShortFileSize(context, state.downloaded),
                    Formatter.formatShortFileSize(context, requireNotNull(state.update?.size))),
                    binding.updaterBytes.text.toString())
                assertEquals(if (state.update == null) View.GONE else View.VISIBLE, binding.updaterRelease.visibility)
                state.update?.let {
                    assertEquals(context.getString(R.string.updater_release_details, it.release,
                        it.commitSha?.take(8) ?: context.getString(R.string.updater_unknown_commit),
                        Formatter.formatShortFileSize(context, requireNotNull(it.size))),
                        binding.updaterRelease.text.toString())
                }
                assertEquals(Long.MAX_VALUE, runtime.getLong("next_check", 0))
                assertFalse("The UI must not start a feed request", checkedNetwork.get())
            }
        }

        try {
            onMain {
                assertTrue(runtime.edit().putLong("next_check", Long.MAX_VALUE).commit())
                // Leave real download_id/download_release untouched and bypass their restoration.
                restoredField.setBoolean(AppUpdater, true)
                states.onNext(available)
                observation = states.subscribe { if (it.phase == Phase.CHECKING) checkedNetwork.set(true) }
                assertTrue(setting.default)
                assertSame(setting, ClientSettings.registered.single { it.key == setting.key && it.scope == setting.scope })
                assertTrue(preferences.edit().remove(setting.key).commit())
                assertTrue(AppUpdater.automaticChecksEnabled())

                // ponytail: use a real fragment without AppActivity's account/notice observers;
                // activity recreation and toolbar Back need a separate, approved device check.
                val context = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_Dark)
                val container = FrameLayout(context).apply { id = View.generateViewId() }
                val host = object : FragmentHostCallback<Context>(context, Handler(Looper.getMainLooper()), 0) {
                    override fun onGetHost(): Context = context
                    override fun onGetLayoutInflater(): LayoutInflater = LayoutInflater.from(context).cloneInContext(context)
                    override fun onFindViewById(id: Int): View? = container.findViewById(id)
                    override fun onHasView(): Boolean = true
                }
                val fragments = FragmentController.createController(host).also { controller = it }
                fragments.attachHost(null)
                fragments.dispatchCreate()
                fragments.dispatchActivityCreated()
                fragments.dispatchStart()
                fragments.dispatchResume()
                fragment = WidgetSettingsUpdates()
                fragments.supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
                binding = WidgetSettingsUpdatesBinding.bind(fragment.requireView())
                assertTrue(binding.updaterAutomatic.isChecked)
                assertEquals(context.getString(R.string.updater_current_build,
                    BuildConfig.UPDATE_BUILD_ID, BuildConfig.UPDATE_COMMIT_SHA.take(8)),
                    binding.updaterCurrent.text.toString())

                var launched: Intent? = null
                WidgetSettingsUpdates.launch(object : ContextWrapper(context) {
                    override fun startActivity(intent: Intent) { launched = intent }
                })
                assertEquals(WidgetSettingsUpdates::class.java, AppScreenRouter.screenOf(launched))
                assertEquals(AppActivity::class.java.name, launched?.component?.className)
            }
            assertRendered(available, R.string.updater_available, R.string.updater_download)

            for (enabled in listOf(false, true)) {
                onMain {
                    assertTrue(ViewSettingSwitchBinding.bind(binding.updaterAutomatic).settingContainer.performClick())
                    assertEquals(enabled, binding.updaterAutomatic.isChecked)
                    assertTrue(preferences.contains(setting.key))
                    assertEquals(enabled, preferences.getBoolean(setting.key, !enabled))
                    assertEquals(enabled, AppUpdater.automaticChecksEnabled())
                    assertSame(available, AppUpdater.state)
                    assertEquals(Long.MAX_VALUE, runtime.getLong("next_check", 0))

                    val manager = requireNotNull(controller).supportFragmentManager
                    val savedState = requireNotNull(manager.saveFragmentInstanceState(fragment))
                    val oldView = fragment.requireView()
                    val containerId = fragment.id
                    manager.beginTransaction().remove(fragment).commitNow()
                    fragment = WidgetSettingsUpdates().apply { setInitialSavedState(savedState) }
                    manager.beginTransaction().add(containerId, fragment).commitNow()
                    binding = WidgetSettingsUpdatesBinding.bind(fragment.requireView())
                    assertNotSame(oldView, binding.root)
                    assertTrue(fragment.isRecreated)
                    assertEquals(enabled, binding.updaterAutomatic.isChecked)
                    assertEquals(enabled, AppUpdater.automaticChecksEnabled())
                }
                assertRendered(available, R.string.updater_available, R.string.updater_download)
            }

            // Inject only after resume: resuming DOWNLOADING would poll the real DownloadManager.
            assertRendered(State(Phase.DOWNLOADING, update, downloaded = 2_097_152), R.string.updater_downloading,
                checkEnabled = false, progress = 25)
            assertRendered(State(Phase.DOWNLOADING, update, downloaded = 2_097_152, paused = true), R.string.updater_waiting,
                checkEnabled = false, progress = 25)
            assertRendered(State(Phase.VERIFYING, update), R.string.updater_verifying,
                checkEnabled = false, indeterminate = true)
            val installLabel = onMain {
                if (Build.VERSION.SDK_INT < 26 || binding.root.context.packageManager.canRequestPackageInstalls())
                    R.string.updater_install else R.string.updater_allow_install
            }
            assertRendered(State(Phase.READY, update), R.string.updater_ready, installLabel, checkEnabled = false)
            val legacy = update.copy(commitSha = null, commitTimestamp = null, versionCode = null,
                versionName = null, packageName = null).validate()
            assertRendered(State(Phase.LEGACY, legacy), R.string.updater_legacy)
            assertRendered(State(Phase.ERROR, error = R.string.updater_check_failed), R.string.updater_check_failed)
            for (error in listOf(R.string.updater_download_failed, R.string.updater_verification_failed)) {
                assertRendered(State(Phase.ERROR, update, error = error), error, R.string.updater_download)
            }
        } finally {
            onMain {
                try {
                    controller?.dispatchDestroy()
                } finally {
                    observation?.unsubscribe()
                    states.onNext(originalState)
                    restoredField.setBoolean(AppUpdater, originalRestored)
                    val restoredSetting = preferences.edit().apply {
                        if (originalAutomatic == null) remove(setting.key) else putBoolean(setting.key, originalAutomatic)
                    }.commit()
                    val restoredRuntime = runtime.edit().apply {
                        if (originalNextCheck == null) remove("next_check") else putLong("next_check", originalNextCheck)
                    }.commit()
                    assertTrue("Restore updater preferences, including absent keys", restoredSetting && restoredRuntime)
                }
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
}
