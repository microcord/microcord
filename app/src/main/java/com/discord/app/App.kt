package com.discord.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.discord.stores.StoreStream
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.lifecycle.ActivityProvider
import com.discord.utilities.lifecycle.ApplicationProvider
import com.discord.utilities.lifecycle.Backgrounded
import com.discord.utilities.persister.PersisterConfig
import com.discord.utilities.time.ClockFactory
import com.facebook.drawee.backends.pipeline.Fresco
import java.util.concurrent.atomic.AtomicBoolean

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!initialized.compareAndSet(false, true)) return

        SharedPreferencesProvider.init(this)
        ApplicationProvider.init(this)
        ActivityProvider.init(this)
        ClockFactory.init()
        Backgrounded.init(this)
        PersisterConfig.init(this, ClockFactory.get())
        Fresco.initialize(this)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        System.loadLibrary("dsti")
        StoreStream.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level in intArrayOf(5, 10, 15, 40, 60, 80) && Fresco.hasBeenInitialized()) {
            Fresco.getImagePipeline().clearMemoryCaches()
        }
    }

    companion object {
        private val initialized = AtomicBoolean()
    }
}
