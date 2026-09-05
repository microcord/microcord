package com.discord.app

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import com.discord.dialogs.WidgetMaskedLinksDialog
import com.discord.stores.StoreStream
import com.discord.utilities.billing.GooglePlayBillingManager
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.captcha.ActivityCaptchaSolver
import com.discord.utilities.captcha.CaptchaSolver
import com.discord.utilities.mfa.ActivityMfaSolver
import com.discord.utilities.mfa.MfaSolver
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.utilities.lifecycle.ApplicationProvider
import com.discord.utilities.lifecycle.Backgrounded
import com.discord.utilities.images.CollectibleAssets
import com.discord.utilities.images.MGImagesConfig
import com.discord.utilities.logging.BootLog
import com.discord.utilities.logging.BootLogEvent
import com.discord.utilities.notices.NoticeBuilders
import com.discord.widgets.guilds.list.GuildsListStoreHooks
import com.discord.widgets.notice.NoticePopupChannel
import com.discord.utilities.systemlog.SystemLogUtils
import com.discord.utilities.persister.PersisterConfig
import com.discord.utilities.time.ClockFactory
import com.discord.rtcconnection.mediaengine.KrispAssetPack
import com.discord.plugins.PluginRuntime
import com.discord.utilities.voice.VoiceEngineServiceController
import com.google.firebase.FirebaseApp
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (currentProcessName() == "$packageName:settings_restore") return
        if (!initialized.compareAndSet(false, true)) return

        if (!com.discord.utilities.feature.ManticordSettingsBackup.awaitRestore(this)) {
            settingsRecoveryRequired = true
            if (isFcmProcess(currentProcessName(), packageName)) android.os.Process.killProcess(android.os.Process.myPid())
            return
        }

        BootLog.record(BootLogEvent.PROCESS_START)
        AppLog.init(this)
        // Without this every REST failure nobody classified is swallowed silently.
        RestApiError.init { tag, throwable, metadata ->
            AppLog.e(tag, "unhandled REST failure", throwable, metadata)
        }
        SharedPreferencesProvider.init(this)
        ApplicationProvider.init(this)
        KrispAssetPack.install(this)
        ActivityProvider.init(this)
        // Must precede StoreStream.initialize, which builds the REST clients.
        CaptchaSolver.install(ActivityCaptchaSolver())
        MfaSolver.install(ActivityMfaSolver())
        ClockFactory.init()
        Backgrounded.init(this)
        PersisterConfig.init(this, ClockFactory.get())
        MGImagesConfig.init(this)
        CollectibleAssets.init(this)
        if (isFcmProcess(currentProcessName(), packageName)) {
            FirebaseApp.initializeApp(this)
            return
        }
        SystemLogUtils.initSystemLogCapture(filesDir)
        installCrashLogHandler()
        // The reference's UriHandler enqueues the masked-link notice itself; ours has no dependency
        // on the widget tree, so the confirmation dialog installs itself here instead. Without this
        // an untrusted masked link is dropped with no user-visible confirmation.
        WidgetMaskedLinksDialog.installUntrustedLinkHandler()
        // Without this `StoreNotices.dialogPresenter` stays null, every dialog-backed notice
        // reports "did not show", and it stays queued forever with nothing user-visible.
        NoticeBuilders.install()
        // Without this a foreground push is dropped instead of becoming the in-app banner.
        NoticePopupChannel.install()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        BootLog.record(BootLogEvent.PLATFORM_READY)
        BootLog.record(BootLogEvent.NATIVE_READY)
        StoreStream.initialize(this)
        PluginRuntime.initialize(this)
        val restorePreferences = SharedPreferencesProvider.getPrefsSessionDurable()
        val restoreKey = com.discord.utilities.feature.ManticordSettingsBackup.RESTORE_RESULT
        if (restorePreferences.contains(restoreKey)) {
            val restored = restorePreferences.getBoolean(restoreKey, false)
            restorePreferences.edit().remove(restoreKey).apply()
            Handler(Looper.getMainLooper()).post {
                AppToast.show(this, if (restored) com.discord.R.string.cloud_settings_restored else com.discord.R.string.cloud_settings_restore_failed)
            }
        }
        // Without this the sidebar has no pending-applications folder and a guild the user has
        // applied to cannot be tapped at all.
        GuildsListStoreHooks.install()
        GooglePlayBillingManager.init(this)
        Handler(Looper.getMainLooper()).postDelayed(
            { VoiceEngineServiceController.INSTANCE.init(this) },
            STARTUP_DEFER_MS,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (settingsRecoveryRequired || currentProcessName() == "$packageName:settings_restore") return
        MGImagesConfig.onTrimMemory(level)
    }

    private fun currentProcessName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getProcessName()
    } else {
        runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()
    }

    private fun installCrashLogHandler() {
        val delegate = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { SystemLogUtils.recordCrash(throwable) }
            delegate?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        @Volatile internal var settingsRecoveryRequired = false
            private set
        private const val STARTUP_DEFER_MS = 500L
        private val initialized = AtomicBoolean()
    }
}

internal fun isFcmProcess(processName: String?, packageName: String) = processName == "$packageName:fcm"
