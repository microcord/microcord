package com.discord.utilities.updates

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.discord.BuildConfig
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.ClientSettings
import com.discord.utilities.rx.ui
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.notice.NoticePopup
import com.discord.widgets.settings.WidgetSettings
import com.discord.widgets.settings.WidgetSettingsUpdates
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

/** Public update transport is deliberately separate from Discord's authenticated API client. */
object AppUpdater {
    enum class Phase { IDLE, CHECKING, AVAILABLE, CURRENT, LEGACY, DOWNLOADING, VERIFYING, READY, ERROR }
    data class State(val phase: Phase = Phase.IDLE, val update: AppUpdate? = null,
        val downloaded: Long = 0, val paused: Boolean = false, val error: Int? = null)

    private val states = BehaviorSubject.create(State())
    val state: State get() = states.value
    fun observe(): Observable<State> = states.distinctUntilChanged()
    private val gson = Gson()
    private val http by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
    private var restored = false
    private var polling: Subscription? = null
    private var queuedNoticeSha: String? = null
    private const val SIX_HOURS = 6 * 60 * 60 * 1000L
    private const val RETRY_DELAY = 15 * 60 * 1000L

    fun automaticChecksEnabled(): Boolean = SharedPreferencesProvider.getOrNull()
        ?.let(ClientSettings.automaticUpdateChecks::get) ?: true

    fun setAutomaticChecks(enabled: Boolean) {
        ClientSettings.automaticUpdateChecks.set(SharedPreferencesProvider.get(), enabled)
        if (!enabled) queuedNoticeSha?.let { StoreStream.getNotices().markSeen(noticeName(it)) }
    }

    fun onForeground(context: Context) {
        restorePending(context.applicationContext)
        if (automaticChecksEnabled()) check(context, automatic = true)
    }

    fun check(context: Context, automatic: Boolean = false) {
        val app = context.applicationContext
        restorePending(app)
        if (state.phase in setOf(Phase.CHECKING, Phase.DOWNLOADING, Phase.VERIFYING, Phase.READY)) return
        val now = System.currentTimeMillis()
        if (automatic && (!automaticChecksEnabled() || now < preferences(app).getLong("next_check", 0))) return
        preferences(app).edit().putLong("next_check", now + RETRY_DELAY).apply()
        states.onNext(State(Phase.CHECKING, state.update))
        Observable.fromCallable {
            http.newCall(Request.Builder().url(UPDATE_FEED_URL).build()).execute().use { response ->
                readJson(response, AppUpdate::class.java, 16_384).validate()
            }
        }.subscribeOn(Schedulers.io()).ui().subscribe({ update ->
            preferences(app).edit().putLong("next_check", System.currentTimeMillis() + SIX_HOURS).apply()
            val phase = when {
                !update.hasBuildMetadata -> Phase.LEGACY
                isNewer(update) -> Phase.AVAILABLE
                else -> Phase.CURRENT
            }
            states.onNext(State(phase, update))
            if (automatic && phase == Phase.AVAILABLE && automaticChecksEnabled()) notifyUpdate(app, update)
        }, {
            states.onNext(State(Phase.ERROR, state.update, error = R.string.updater_check_failed))
        })
    }

    fun loadChanges(update: AppUpdate): Observable<AppUpdateChanges> = Observable.defer {
        update.validate()
        val from = BuildConfig.UPDATE_COMMIT_SHA
        val url = requireNotNull(update.apkUrl).removeSuffix(".apk") + "/changes.json?from=$from"
        val call = http.newCall(Request.Builder().url(url).build())
        Observable.fromCallable {
            call.execute().use { readJson(it, AppUpdateChanges::class.java, 128 * 1024).validate(update, from) }
        }.doOnUnsubscribe(call::cancel)
    }.subscribeOn(Schedulers.io())

    private fun <T> readJson(response: Response, type: Class<T>, maxBytes: Long): T {
        kotlin.check(response.isSuccessful)
        val source = requireNotNull(response.body).source()
        source.request(maxBytes + 1)
        require(source.buffer.size <= maxBytes)
        return requireNotNull(gson.fromJson(source.readUtf8(), type))
    }

    private fun notifyUpdate(context: Context, update: AppUpdate) {
        val sha = requireNotNull(update.commitSha)
        if (queuedNoticeSha == sha) return
        queuedNoticeSha = sha
        NoticePopup.enqueue(
            noticeName = noticeName(sha),
            noticeTitle = context.getString(R.string.updater_available),
            noticeBody = context.getString(R.string.updater_notice_body, update.release),
            noticeIconResId = R.drawable.ic_file_download_white_24dp,
            noticeIconTopRight = ContextCompat.getDrawable(context, R.drawable.ic_close_24dp),
            noticeAutoDismissPeriodSecs = 10,
            validScreens = listOf(WidgetHome::class, WidgetSettings::class),
            onClick = { WidgetSettingsUpdates.launch(it.context) },
        )
    }

    fun startDownload(context: Context) {
        if (state.phase !in setOf(Phase.AVAILABLE, Phase.ERROR)) return
        val update = state.update?.takeIf(::isNewer) ?: return
        val app = context.applicationContext
        try {
            update.validate()
            clearPending(app)
            val external = requireNotNull(app.getExternalFilesDir("updates"))
            external.listFiles()?.forEach { it.delete() }
            val id = downloads(app).enqueue(DownloadManager.Request(Uri.parse(update.apkUrl))
                .setTitle(app.getString(R.string.updater_download_title, update.release))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(app, "updates", "${update.sha256}.apk"))
            preferences(app).edit().putLong("download_id", id).putString("download_release", gson.toJson(update)).apply()
            states.onNext(State(Phase.DOWNLOADING, update))
            watchDownload(app)
        } catch (_: Exception) {
            states.onNext(State(Phase.ERROR, update, error = R.string.updater_download_failed))
        }
    }

    /** DownloadManager owns background work; only the visible update screen polls progress. */
    fun watchDownload(context: Context) {
        val app = context.applicationContext
        restorePending(app)
        if (state.phase != Phase.DOWNLOADING || polling?.isUnsubscribed == false) return
        val update = requireNotNull(state.update)
        val id = preferences(app).getLong("download_id", -1)
        polling = Observable.interval(0, 1, TimeUnit.SECONDS, Schedulers.io())
            .map { queryDownload(app, id, update) }.ui().subscribe({ progress ->
                states.onNext(progress)
                if (progress.phase != Phase.DOWNLOADING) {
                    polling?.unsubscribe()
                    polling = null
                    if (progress.phase == Phase.VERIFYING) {
                        Observable.fromCallable {
                            val directory = File(app.filesDir, "updates").apply { mkdirs() }
                            directory.listFiles()?.forEach { it.delete() }
                            val file = ParcelFileDescriptor.AutoCloseInputStream(downloads(app).openDownloadedFile(id)).use {
                                copyVerifiedUpdate(it, File(directory, "${update.sha256}.apk"), update)
                            }
                            verifyPackage(app, file, update)
                        }.subscribeOn(Schedulers.io()).ui().subscribe({
                            states.onNext(State(Phase.READY, update))
                        }, {
                            clearPending(app)
                            states.onNext(State(Phase.ERROR, update, error = R.string.updater_verification_failed))
                        })
                    } else clearPending(app)
                }
            }, {
                polling?.unsubscribe()
                polling = null
                clearPending(app)
                states.onNext(State(Phase.ERROR, update, error = R.string.updater_download_failed))
            })
    }

    fun stopWatchingDownload() {
        polling?.unsubscribe()
        polling = null
    }

    fun cancelDownload(context: Context) {
        if (state.phase != Phase.DOWNLOADING) return
        stopWatchingDownload()
        clearPending(context.applicationContext)
        states.onNext(State(Phase.AVAILABLE, state.update))
    }

    fun prepareInstall(context: Context): Observable<File> {
        val app = context.applicationContext
        val update = requireNotNull(state.update)
        kotlin.check(state.phase == Phase.READY && isNewer(update))
        return Observable.fromCallable {
            val file = File(app.filesDir, "updates/${update.sha256}.apk")
            // Recheck private bytes as well as Android package/signers immediately before granting access.
            verifyUpdateFile(file, update)
            verifyPackage(app, file, update)
            file
        }.subscribeOn(Schedulers.io())
    }

    fun invalidateDownload(context: Context) {
        clearPending(context.applicationContext)
        states.onNext(State(Phase.ERROR, state.update, error = R.string.updater_verification_failed))
    }

    private fun restorePending(context: Context) {
        if (restored) return
        restored = true
        val prefs = preferences(context)
        if (prefs.getLong("download_id", -1) == -1L) return
        val update = runCatching {
            gson.fromJson(prefs.getString("download_release", null), AppUpdate::class.java)?.validate()
                ?.takeIf(::isNewer)
        }.getOrNull()
        if (update == null) clearPending(context) else states.onNext(State(Phase.DOWNLOADING, update))
    }

    private fun queryDownload(context: Context, id: Long, update: AppUpdate): State {
        downloads(context).query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return State(Phase.ERROR, update, error = R.string.updater_download_failed)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> State(Phase.VERIFYING, update)
                DownloadManager.STATUS_FAILED -> State(Phase.ERROR, update, error = R.string.updater_download_failed)
                else -> State(Phase.DOWNLOADING, update,
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    paused = status == DownloadManager.STATUS_PAUSED)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, file: File, update: AppUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val archive = requireNotNull(pm.getPackageArchiveInfo(file.path, flags))
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun signers(info: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners
            else info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        val expected = signers(installed)
        require(acceptsUpdatePackage(update, PackageInfoCompat.getLongVersionCode(installed),
            archive.packageName, PackageInfoCompat.getLongVersionCode(archive),
            expected.isNotEmpty() && expected == signers(archive)))
    }

    private fun clearPending(context: Context) {
        val prefs = preferences(context)
        val id = prefs.getLong("download_id", -1)
        prefs.edit().remove("download_id").remove("download_release").apply()
        if (id != -1L) runCatching { downloads(context).remove(id) }
        File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
    }

    private fun isNewer(update: AppUpdate) = update.isNewerThan(BuildConfig.UPDATE_COMMIT_SHA,
        BuildConfig.UPDATE_COMMIT_TIMESTAMP, BuildConfig.VERSION_CODE.toLong())
    private fun noticeName(sha: String) = "InAppNotif-Update-$sha"
    private fun preferences(context: Context) = context.getSharedPreferences("manticord_updater_runtime", Context.MODE_PRIVATE)
    private fun downloads(context: Context) = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
}
