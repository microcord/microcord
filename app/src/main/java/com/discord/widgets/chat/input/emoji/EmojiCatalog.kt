package com.discord.widgets.chat.input.emoji

import android.content.Context
import com.discord.app.AppLog
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.EmojiCategory
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import rx.Observable
import rx.schedulers.Schedulers

/**
 * The bundled Unicode emoji catalog (`assets/data/emojis.json`), parsed once per process.
 *
 * Two things matter here and both used to be wrong:
 *
 * * **It is not parsed on the main thread.** The file is ~1.5k emoji across eight groups; parsing
 *   it inline in `onViewCreated` stalls the frame in which the picker opens. [observe] does the
 *   work on [Schedulers.io] and replays the result to every later subscriber.
 * * **A failure is reported, not swallowed.** A missing or corrupt asset used to fall back to an
 *   empty catalogue silently, which presents as "the emoji picker is just blank" with nothing in
 *   the log to explain it.
 *
 * This parser remains available for rendering tests that use the bundled catalog directly.
 */
object EmojiCatalog {

    private const val ASSET_PATH = "data/emojis.json"

    @Volatile
    private var cached: Map<EmojiCategory, List<Emoji>>? = null

    /**
     * Emits the catalog once, then completes.
     *
     * Cached across subscriptions because the asset cannot change while the process is alive, and
     * because the picker is re-created on every channel switch.
     */
    fun observe(context: Context): Observable<Map<EmojiCategory, List<Emoji>>> {
        cached?.let { return Observable.just(it) }
        val appContext = context.applicationContext
        return Observable.fromCallable { load(appContext) }
            .subscribeOn(Schedulers.io())
    }

    /** Blocking load. Public for tests; UI code goes through [observe]. */
    fun load(context: Context): Map<EmojiCategory, List<Emoji>> {
        cached?.let { return it }
        val parsed = try {
            context.assets.open(ASSET_PATH).bufferedReader().use { parse(it.readText()) }
        } catch (t: Throwable) {
            AppLog.e("Unable to read the bundled emoji catalog at $ASSET_PATH", t)
            emptyMap()
        }
        cached = parsed
        return parsed
    }

    /**
     * Parses the asset into declaration order per category.
     *
     * Unknown top-level keys are skipped rather than dropped into a catch-all: the file is
     * versioned with the client, so a key we do not recognise means the model is out of date and
     * guessing a category for it would put emoji under the wrong tab.
     */
    fun parse(json: String): Map<EmojiCategory, List<Emoji>> =
        JsonParser.parseString(json).asJsonObject.entrySet().mapNotNull { (key, values) ->
            val category = EmojiCategory.getByString(key) ?: return@mapNotNull null
            category to values.asJsonArray.map { readEmoji(it.asJsonObject) }
        }.toMap()

    private fun readEmoji(item: JsonObject): ModelEmojiUnicode = ModelEmojiUnicode(
        names = item.getAsJsonArray("names").map { it.asString },
        surrogates = item.get("surrogates").asString,
        hasDiversity = item.get("hasDiversity")?.asBoolean == true,
        hasMultiDiversity = item.get("hasMultiDiversity")?.asBoolean == true,
        hasDiversityParent = item.get("hasDiversityParent")?.asBoolean == true,
        hasMultiDiversityParent = item.get("hasMultiDiversityParent")?.asBoolean == true,
        diversityChildren = item.getAsJsonArray("diversityChildren")
            ?.map { readEmoji(it.asJsonObject) },
    )
}
