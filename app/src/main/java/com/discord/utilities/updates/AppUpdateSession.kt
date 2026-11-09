package com.discord.utilities.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.File

/**
 * Installs a verified update through a PackageInstaller session, so its baseline profile goes in as
 * `base.dm` beside `base.apk` and ART compiles the update with it at install time instead of leaving
 * it uncompiled until background dexopt. The system installer still asks the user to confirm.
 * Only used for Android 9+ (API 28), where installers accept `.dm` files; see [AppUpdate.dexMetadataFor].
 */
internal object AppUpdateSession {
    private const val ACTION_STATUS = "com.discord.utilities.updates.INSTALL_STATUS"
    @Volatile private var sessionId = PackageInstaller.SessionInfo.INVALID_ID
    private var registered = false

    /** Throws, leaving no session behind, unless both files were written exactly as verified and committed. */
    fun commit(context: Context, apk: File, update: AppUpdate, dmFile: File, dm: AppUpdate.DexMetadata) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        // A session still waiting from an earlier attempt would otherwise linger until the system expires it.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length() + dmFile.length())
        })
        installer.openSession(id).use { session ->
            try {
                // The digests cover the exact bytes handed to the installer, not a file that could change after checking.
                write(session, "base.apk", apk, requireNotNull(update.size), requireNotNull(update.sha256))
                // Android pairs a profile with the APK of the same name.
                write(session, "base.dm", dmFile, requireNotNull(dm.size), requireNotNull(dm.sha256))
                register(app)
                sessionId = id
                session.commit(statusSender(app, id))
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    private fun write(session: PackageInstaller.Session, name: String, file: File, size: Long, sha256: String) {
        file.inputStream().use { input ->
            session.openWrite(name, 0, size).use { output ->
                copyVerified(input, output, size, sha256)
                session.fsync(output)
            }
        }
    }

    // Explicit, so the installer's fill-in extras (which need the mutable flag) can only reach this app.
    private fun statusSender(context: Context, id: Int) = requireNotNull(PendingIntentCompat.getBroadcast(context, id,
        Intent(ACTION_STATUS).setPackage(context.packageName).putExtra(PackageInstaller.EXTRA_SESSION_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT, true)).intentSender

    @Synchronized private fun register(context: Context) {
        if (registered) return
        // Not exported: the confirmation intent it launches must come from this app's own installer session.
        ContextCompat.registerReceiver(context, StatusReceiver(), IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    private class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, PackageInstaller.SessionInfo.INVALID_ID) != sessionId) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> try {
                    val confirm = requireNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    AppUpdater.onInstallSessionFailed(context)
                }
                // Android replaces this process with the update.
                PackageInstaller.STATUS_SUCCESS -> Unit
                // The user declined; the update stays ready to install.
                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
                else -> AppUpdater.onInstallSessionFailed(context)
            }
        }
    }
}
