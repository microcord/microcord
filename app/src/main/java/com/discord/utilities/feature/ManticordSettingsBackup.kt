package com.discord.utilities.feature

import android.content.Context
import androidx.core.util.AtomicFile
import com.discord.models.domain.UserSettingsProtoResponse
import com.discord.models.domain.UserSettingsProtoPatch
import com.discord.restapi.RestAPIInterface
import com.discord.stores.authentication.AuthStateCache
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.ManticordSettingsBackupCodec.Payload
import com.discord.utilities.persister.Persister
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import rx.Observable
import rx.schedulers.Schedulers

/** Explicit, account-guarded backups; no background uploads or implicit restores. */
class ManticordSettingsBackup(
    private val api: RestAPIInterface,
    private val permitted: () -> Boolean,
) {
    fun save(snapshot: Observable<Payload>): Observable<Unit> = Observable.defer {
        checkPermitted()
        snapshot.single().observeOn(Schedulers.io()).flatMap { payload ->
            checkPermitted()
            validate(payload)
            update({ ManticordSettingsBackupCodec.encodeForUpload(it, payload) }, payload)
        }
    }

    fun clear(): Observable<Unit> = update(ManticordSettingsBackupCodec::clearForUpload, null)

    private fun update(patch: (String) -> UserSettingsProtoPatch, expected: Payload?): Observable<Unit> = Observable.defer {
        checkPermitted()
        api.getUserSettingsProto(3).single().flatMap { current ->
            checkPermitted()
            api.patchUserSettingsProto(3, patch(current.settings))
        }.single().flatMap { saved ->
            checkPermitted()
            check(!saved.outOfDate) { "Cloud backup changed on another client. Try again." }
            check(ManticordSettingsBackupCodec.decode(saved.settings) == expected) { "Cloud backup update was not preserved." }
            api.getUserSettingsProto(3).single()
        }.map { confirmed ->
            checkPermitted()
            check(ManticordSettingsBackupCodec.decode(confirmed.settings) == expected) { "Cloud backup verification failed." }
            Unit
        }
    }

    fun load(): Observable<Payload> = Observable.defer {
        checkPermitted()
        api.getUserSettingsProto(3).single().map { response: UserSettingsProtoResponse ->
            checkPermitted()
            requireNotNull(ManticordSettingsBackupCodec.decode(response.settings)) { "No Manticord cloud backup exists." }
                .also(::validate)
        }
    }

    fun checkPermitted() {
        if (!permitted()) throw CancellationException("Cloud backup disabled or account changed")
    }

    companion object {
        private const val PENDING = "pending-client-settings-restore"
        private const val ORIGINAL = "original-client-settings-restore"
        private const val LOCK = "client-settings-restore.lock"
        const val RESTORE_RESULT = "client_settings_restore_result"

        fun validate(payload: Payload) {
            require((payload.booleans.keys + payload.strings.keys).all {
                it.startsWith("prefs:") || it.startsWith("local:")
            }) { "Unknown client settings section" }
            ClientPreferenceSnapshot.validate(payload)
            LocalSettingsSnapshot.validate(payload)
        }

        fun stageRestore(context: Context, payload: Payload, owner: String) {
            check(FeatureFlags.cloudSettingsBackupEnabled()) { "Cloud backup is disabled" }
            check(owner == authFingerprint()) { "Restore account changed" }
            validate(payload)
            val encoded = ManticordSettingsBackupCodec.encodeForUpload("", payload).settings
            withRestoreLock(context) {
                check(!File(context.filesDir, ORIGINAL).exists()) { "An interrupted restore needs recovery" }
                writeVerified(AtomicFile(File(context.filesDir, PENDING)), "$owner\n$encoded".toByteArray(Charsets.UTF_8))
            }
        }

        internal fun hasPendingRestore(context: Context): Boolean = runCatching {
            val expected = "${authFingerprint()}\n".toByteArray(Charsets.UTF_8)
            AtomicFile(File(context.filesDir, PENDING)).openRead().use { input ->
                val actual = ByteArray(expected.size)
                java.io.DataInputStream(input).readFully(actual)
                actual.contentEquals(expected)
            }
        }.getOrDefault(false)

        /** Called before preference caches are opened in any normal application process. */
        fun awaitRestore(context: Context): Boolean = runCatching {
            if (!File(context.filesDir, PENDING).exists() && !File(context.filesDir, ORIGINAL).exists()) true
            else withRestoreLock(context) { !File(context.filesDir, ORIGINAL).exists() }
        }.getOrDefault(false)

        /** Only the isolated restore activity calls this, before creating any store owners. */
        fun restorePending(context: Context): Boolean {
            val original = AtomicFile(File(context.filesDir, ORIGINAL))
            if (original.baseFile.exists()) {
                applySnapshot(context, readStaged(original))
                original.delete()
                check(!original.baseFile.exists()) { "Could not finish settings recovery" }
                return false
            }
            val file = AtomicFile(File(context.filesDir, PENDING))
            val payload = readStaged(file)
            check(FeatureFlags.cloudSettingsBackupEnabled()) { "Cloud backup is disabled" }
            validate(payload)
            val preferences = ClientPreferenceSnapshot.capture(context)
            val local = LocalSettingsSnapshot.capture(preferences)
            val rollback = Payload(preferences.booleans + local.booleans, preferences.strings + local.strings)
            val encoded = ManticordSettingsBackupCodec.encodeForUpload("", rollback).settings
            writeVerified(original, "${authFingerprint()}\n$encoded".toByteArray(Charsets.UTF_8))
            val restored = restoreOrRollback(
                restore = { applySnapshot(context, payload) },
                rollback = { applySnapshot(context, rollback) },
            )
            original.delete()
            check(!original.baseFile.exists()) { "Could not finish settings recovery" }
            if (restored) {
                file.delete()
                check(!file.baseFile.exists()) { "Could not finish settings restore" }
            }
            return restored
        }

        private fun readStaged(file: AtomicFile): Payload {
            val text = file.openRead().use { input ->
                require(input.available() <= 5_243_000) { "Pending backup is too large" }
                input.readBytes().toString(Charsets.UTF_8)
            }
            require(text.substringBefore('\n') == authFingerprint()) { "Restore account changed" }
            return requireNotNull(ManticordSettingsBackupCodec.decode(text.substringAfter('\n'))).also(::validate)
        }

        private fun applySnapshot(context: Context, payload: Payload) {
            validate(payload)
            ClientPreferenceSnapshot.restore(context, payload)
            LocalSettingsSnapshot.restore(payload)
            // Rebuild only local toggles in the FCM-process record; its credential never enters a backup.
            val notifications = Persister("STORE_NOTIFICATIONS_SETTINGS_V2", com.discord.models.notification.NotificationSettingsV2(), preload = false).get()
            val key = com.discord.utilities.fcm.NotificationClient.SETTINGS_CACHE_KEY
            val push = Persister(key, com.discord.utilities.fcm.NotificationClient.SettingsV2(), preload = false)
            push.set(push.get().copy(
                isEnabled = notifications.isEnabled, isWake = notifications.isWake,
                isDisableBlink = notifications.isDisableBlink, isDisableSound = notifications.isDisableSound,
                isDisableVibrate = notifications.isDisableVibrate,
            ), persist = true)
            check(push.persistNow()) { "Could not persist notification settings" }
        }

        internal fun restoreOrRollback(restore: () -> Unit, rollback: () -> Unit): Boolean = try {
            restore()
            true
        } catch (_: Exception) {
            rollback() // Failure here keeps the original journal and prevents normal startup.
            false
        }

        internal fun writeVerified(file: AtomicFile, bytes: ByteArray) {
            val output = file.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                file.finishWrite(output)
                check(file.openRead().use { it.readBytes() }.contentEquals(bytes)) { "Could not publish settings restore" }
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        }

        fun <T> withRestoreLock(context: Context, action: () -> T): T =
            RandomAccessFile(File(context.filesDir, LOCK), "rw").use { file ->
                file.channel.lock().use { action() }
            }

        // Local staging is tied to this sign-in, without storing or transmitting another token copy.
        internal fun authFingerprint(): String {
            val token = checkNotNull(AuthStateCache(SharedPreferencesProvider.get()).getCachedAuthState()?.token)
            return MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
