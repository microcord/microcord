package com.discord.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.Handler
import android.os.Looper
import androidx.core.view.isVisible
import com.discord.databinding.WidgetClientSettingsRecoveryBinding
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.ManticordSettingsBackup
import com.discord.utilities.persister.Persister
import com.discord.utilities.time.ClockFactory
import rx.Observable
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** A private, short-lived process prevents hot stores or plugins from undoing a restore. */
class ClientSettingsRestoreActivity : Activity() {
    private lateinit var binding: WidgetClientSettingsRecoveryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = WidgetClientSettingsRecoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        current = WeakReference(this)
        binding.recoveryRetry.setOnClickListener { failed = false; restore() }
        binding.recoveryClose.setOnClickListener { finishAndRemoveTask(); Process.killProcess(Process.myPid()) }
        if (completed) launchMain() else if (failed || running.get()) render() else restore()
    }

    private fun restore() {
        if (!running.compareAndSet(false, true)) return
        render()
        val app = applicationContext
        Thread({
            val mayStart = runCatching {
                ManticordSettingsBackup.withRestoreLock(app) {
                    val manager = app.getSystemService(ACTIVITY_SERVICE) as ActivityManager
                    manager.runningAppProcesses.orEmpty().filter {
                        it.uid == Process.myUid() && it.pid != Process.myPid() &&
                            (it.processName == app.packageName || it.processName.startsWith("${app.packageName}:"))
                    }.forEach { Process.killProcess(it.pid) }
                    SharedPreferencesProvider.init(app)
                    ClockFactory.init()
                    Persister.init(app, ClockFactory.get(), Observable.never())
                    val restored = ManticordSettingsBackup.restorePending(app)
                    SharedPreferencesProvider.getPrefsSessionDurable().edit()
                        .putBoolean(ManticordSettingsBackup.RESTORE_RESULT, restored).commit()
                }
            }.isSuccess
            Handler(Looper.getMainLooper()).post {
                completed = mayStart
                failed = !mayStart
                running.set(false)
                current.get()?.let { if (mayStart) it.launchMain() else it.render() }
            }
        }, "ClientSettingsRestore").start()
    }

    private fun render() {
        binding.recoveryProgress.isVisible = running.get()
        binding.recoveryError.isVisible = failed
        binding.recoveryRetry.isVisible = failed
        binding.recoveryClose.isVisible = failed
    }

    private fun launchMain() {
        startActivity(Intent(this, AppActivity.Main::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    private companion object {
        val running = AtomicBoolean()
        var current = WeakReference<ClientSettingsRestoreActivity>(null)
        var failed = false
        var completed = false
    }
}
