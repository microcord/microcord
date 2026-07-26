package com.discord.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.discord.dialogs.WidgetMaskedLinksDialog
import com.discord.stores.StoreStream
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.utilities.lifecycle.ApplicationProvider
import com.discord.utilities.lifecycle.Backgrounded
import com.discord.utilities.images.MGImagesConfig
import com.discord.utilities.logging.BootLog
import com.discord.utilities.logging.BootLogEvent
import com.discord.utilities.persister.PersisterConfig
import com.discord.utilities.time.ClockFactory
import java.util.concurrent.atomic.AtomicBoolean

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!initialized.compareAndSet(false, true)) return

        BootLog.record(BootLogEvent.PROCESS_START)
        AppLog.init(this)
        SharedPreferencesProvider.init(this)
        ApplicationProvider.init(this)
        ActivityProvider.init(this)
        ClockFactory.init()
        Backgrounded.init(this)
        PersisterConfig.init(this, ClockFactory.get())
        MGImagesConfig.init(this)
        // The reference's UriHandler enqueues the masked-link notice itself; ours has no dependency
        // on the widget tree, so the confirmation dialog installs itself here instead. Without this
        // an untrusted masked link is dropped with no user-visible confirmation.
        WidgetMaskedLinksDialog.installUntrustedLinkHandler()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        BootLog.record(BootLogEvent.PLATFORM_READY)
        System.loadLibrary("dsti")
        BootLog.record(BootLogEvent.NATIVE_READY)
        StoreStream.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MGImagesConfig.onTrimMemory(level)
    }

    companion object {
        private val initialized = AtomicBoolean()
    }
}
