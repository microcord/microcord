package com.discord.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.discord.dialogs.WidgetMaskedLinksDialog
import com.discord.screenshot_detection.ScreenshotDetector
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsDeviceResourceUsageMonitor
import com.discord.utilities.analytics.AdvertisingIdProvider
import com.discord.utilities.billing.GooglePlayBillingManager
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.error.RestApiError
import com.discord.utilities.captcha.ActivityCaptchaSolver
import com.discord.utilities.captcha.CaptchaSolver
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.utilities.lifecycle.ApplicationProvider
import com.discord.utilities.lifecycle.Backgrounded
import com.discord.utilities.images.MGImagesConfig
import com.discord.utilities.logging.BootLog
import com.discord.utilities.logging.BootLogEvent
import com.discord.utilities.notices.NoticeBuilders
import com.discord.utilities.persister.PersisterConfig
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.voice.VoiceEngineServiceController
import java.util.concurrent.atomic.AtomicBoolean

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!initialized.compareAndSet(false, true)) return

        BootLog.record(BootLogEvent.PROCESS_START)
        AppLog.init(this)
        // Without this every REST failure nobody classified is swallowed silently.
        RestApiError.init { tag, throwable, metadata ->
            AppLog.e(tag, "unhandled REST failure", throwable, metadata)
        }
        SharedPreferencesProvider.init(this)
        ApplicationProvider.init(this)
        ActivityProvider.init(this)
        // Must precede StoreStream.initialize, which builds the REST clients.
        CaptchaSolver.install(ActivityCaptchaSolver())
        ClockFactory.init()
        ScreenshotDetector.instance = ScreenshotDetector(this, AppLog, SharedPreferencesProvider.get())
        Backgrounded.init(this)
        PersisterConfig.init(this, ClockFactory.get())
        MGImagesConfig.init(this)
        // The reference's UriHandler enqueues the masked-link notice itself; ours has no dependency
        // on the widget tree, so the confirmation dialog installs itself here instead. Without this
        // an untrusted masked link is dropped with no user-visible confirmation.
        WidgetMaskedLinksDialog.installUntrustedLinkHandler()
        // Without this `StoreNotices.dialogPresenter` stays null, every dialog-backed notice
        // reports "did not show", and it stays queued forever with nothing user-visible.
        NoticeBuilders.install()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        BootLog.record(BootLogEvent.PLATFORM_READY)
        System.loadLibrary("dsti")
        BootLog.record(BootLogEvent.NATIVE_READY)
        AnalyticsDeviceResourceUsageMonitor.start()
        AdvertisingIdProvider.initialize(this)
        StoreStream.initialize(this)
        GooglePlayBillingManager.init(this)
        VoiceEngineServiceController.INSTANCE.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MGImagesConfig.onTrimMemory(level)
    }

    companion object {
        private val initialized = AtomicBoolean()
    }
}
