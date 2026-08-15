package com.discord.rpc

import com.discord.utilities.rest.RestAPI
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import rx.Observable

/**
 * Turns the image fields of an RPC activity into ids the client can render.
 *
 * An application names its images, or points at one off Discord; neither is something a presence
 * can carry. The reference resolves both before the activity is published — names against the
 * application's registered assets, urls through the media proxy — and drops whatever it cannot
 * resolve, so a stale name renders as no image rather than as a broken one.
 */
object RpcActivityAssets {

    private val byApplication = ConcurrentHashMap<Long, Map<String, Long>>()
    private val external = ConcurrentHashMap<String, String>()

    private val IMAGE_KEYS = listOf("large_image", "small_image", "invite_cover_image")

    /** Emits [activity] once its `assets` have been rewritten in place. */
    fun resolve(activity: JSONObject, applicationId: Long?): Observable<JSONObject> {
        val assets = activity.optJSONObject("assets")
        if (assets == null || applicationId == null) return Observable.just(activity)
        val names = IMAGE_KEYS.mapNotNull { assets.opt(it) as? String }
        if (names.isEmpty()) return Observable.just(activity)

        val urls = names.filter { it.startsWith("http:") || it.startsWith("https:") }
        val needsNames = names.any { it !in urls }
        return resolveExternal(applicationId, urls)
            .flatMap { if (needsNames) resolveNamed(applicationId) else Observable.just(emptyMap()) }
            .map { named ->
                IMAGE_KEYS.forEach { key ->
                    val name = assets.opt(key) as? String ?: return@forEach
                    val resolved = external[name]?.let { "mp:$it" } ?: named[name]?.toString()
                    if (resolved != null) assets.put(key, resolved) else assets.remove(key)
                }
                activity
            }
            .onErrorReturn { activity }
    }

    private fun resolveExternal(applicationId: Long, urls: List<String>): Observable<Unit> {
        val missing = urls.filter { !external.containsKey(it) }
        if (missing.isEmpty()) return Observable.just(Unit)
        val api = RestAPI.apiOrNull ?: return Observable.just(Unit)
        return api.getApplicationExternalAssets(applicationId, com.discord.restapi.RestAPIParams.ExternalAssets(missing))
            .map { results -> results.forEach { external[it.url] = it.externalAssetPath } }
            .onErrorReturn { }
    }

    private fun resolveNamed(applicationId: Long): Observable<Map<String, Long>> {
        byApplication[applicationId]?.let { return Observable.just(it) }
        val api = RestAPI.apiOrNull ?: return Observable.just(emptyMap())
        return api.getApplicationAssets(applicationId)
            .map { assets -> assets.associate { it.name to it.id }.also { byApplication[applicationId] = it } }
            .onErrorReturn { emptyMap() }
    }
}
