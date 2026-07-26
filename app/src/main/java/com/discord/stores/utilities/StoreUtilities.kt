package com.discord.stores.utilities

import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import java.util.concurrent.TimeUnit

/**
 * Emits the first value of a burst immediately, then at most one value per [windowDuration].
 *
 * @see LeadingEdgeThrottle
 */
fun <T> Observable<T>.leadingEdgeThrottle(
    windowDuration: Long,
    timeUnit: TimeUnit,
    scheduler: Scheduler = Schedulers.computation(),
): Observable<T> = lift(LeadingEdgeThrottle<T>(windowDuration, timeUnit, scheduler))

/**
 * Rebuilds a store snapshot by only re-deriving the entries whose keys changed during the
 * dispatch, reusing the previous [snapshot] value for everything else.
 *
 * Returns [snapshot] unchanged when nothing is dirty, so an untouched store costs nothing.
 */
inline fun <K, V> Map<K, V>.snapshotDirtyKeys(
    snapshot: Map<K, V>,
    dirtyKeys: Set<K>,
    snapshotDirtyValue: (K, V) -> V,
): Map<K, V> {
    if (dirtyKeys.isEmpty()) return snapshot
    val updated = HashMap<K, V>(size)
    for ((key, value) in this) {
        if (key in dirtyKeys) {
            updated[key] = snapshotDirtyValue(key, value)
        } else {
            snapshot[key]?.let { updated[key] = it }
        }
    }
    return updated
}
