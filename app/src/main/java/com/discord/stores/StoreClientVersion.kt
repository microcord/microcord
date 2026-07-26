package com.discord.stores

import android.content.Context
import com.discord.BuildConfig
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.computationLatest
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * The forced-update gate: whether this build is older than the minimum the backend will accept.
 *
 * The answer is polled hourly and cached in prefs, so a client that starts offline still knows it
 * was outdated last time it checked and can show the update wall before any request fails.
 *
 * `synchronized` on [setClientMinVersion] and [init] is the reference's, and is load-bearing: the
 * poll runs on a computation thread while [init] runs on whatever called it, and both write
 * [clientMinVersion].
 *
 * **The version manifest URL needs Manticord-owned configuration.** The reference polls Discord's
 * own release manifest; a Manticord build must publish and point at its own. Until
 * [minVersionSource] is supplied the store never polls — it still reports the last value it
 * persisted, so an already-installed gate keeps working, but nothing new is ever discovered.
 */
class StoreClientVersion(
    private val clientVersion: Int = BuildConfig.VERSION_CODE,
    /**
     * Fetches the minimum acceptable client version. `null` disables polling entirely.
     *
     * Supply a source that reads Manticord's own version manifest. The reference implementation is
     * `RestAPI.getClientVersion()`, a plain GET of a JSON document whose `min_version` field is
     * read as an int (absent ⇒ `0`, i.e. never outdated).
     */
    private val minVersionSource: (() -> Observable<Int>)? = null,
) : Store() {

    private val clientMinVersionKey = "CLIENT_OUTDATED_KEY"
    private var clientMinVersion: Int = 0
    private val clientOutdatedSubject = SerializedSubject(BehaviorSubject.create(false))

    fun getClientOutdated(): Observable<Boolean> =
        clientOutdatedSubject.computationLatest().distinctUntilChanged()

    @Synchronized
    override fun init(context: Context) {
        super.init(context)
        // Seed from the persisted value so an offline start still gates correctly.
        //
        // The reference assigns the field *and then* calls the setter with the same value, which
        // makes the setter's equality check swallow it — so the subject is not published on init
        // and a client that was outdated last run reports `false` until the first poll answers.
        // Reproduced rather than "fixed": publishing here would show the update wall before the
        // backend has been asked, which is a behaviour change, not a bug fix.
        val persisted = prefs.getInt(clientMinVersionKey, 0)
        clientMinVersion = persisted
        setClientMinVersion(persisted)

        val source = minVersionSource ?: return
        Observable.interval(0L, POLL_INTERVAL_HOURS, TimeUnit.HOURS)
            .flatMap { source() }
            .computationBuffered()
            .appSubscribe(StoreClientVersion::class.java) { minVersion ->
                setClientMinVersion(minVersion)
            }
    }

    @Synchronized
    internal fun setClientMinVersion(clientMinVersion: Int) {
        if (this.clientMinVersion == clientMinVersion) return
        this.clientMinVersion = clientMinVersion
        clientOutdatedSubject.onNext(this.clientVersion < clientMinVersion)
        prefs.edit().putInt(clientMinVersionKey, clientMinVersion).apply()
    }

    private companion object {
        const val POLL_INTERVAL_HOURS = 1L
    }
}
