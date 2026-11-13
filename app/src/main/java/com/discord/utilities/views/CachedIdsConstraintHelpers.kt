package com.discord.utilities.views

import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.Group
import java.util.concurrent.ConcurrentHashMap

/**
 * `app:constraint_referenced_ids` lists view ids by name. ConstraintLayout resolves those names
 * when the helper is inflated, before it has a parent, which falls through to reflection on its
 * own `R` class (throwing for every app id) and then `getIdentifier`; it repeats that on every
 * attach to a window, which a RecyclerView row does constantly. Measured on the chat list, three
 * barriers per attachment made the first chat frame about 30 ms slower.
 *
 * These subclasses, substituted for every inflated `<Barrier>`/`<Group>` by `DiscordViewInflater`,
 * resolve each name list once per process and hand ConstraintLayout plain ids, which it then never
 * re-resolves. A list with a name that is not an app id falls back to ConstraintLayout's own
 * lookup, so behaviour is unchanged.
 */
class CachedIdsBarrier(context: Context, attrs: AttributeSet?) : Barrier(context, attrs) {
    override fun setIds(idList: String?) {
        referencedIds = ReferencedIds.resolve(context, idList) ?: return super.setIds(idList)
    }
}

/** See [CachedIdsBarrier]. */
class CachedIdsGroup(context: Context, attrs: AttributeSet?) : Group(context, attrs) {
    override fun setIds(idList: String?) {
        referencedIds = ReferencedIds.resolve(context, idList) ?: return super.setIds(idList)
    }
}

internal object ReferencedIds {
    private val cache = ConcurrentHashMap<String, IntArray>()

    fun resolve(context: Context, idList: String?): IntArray? = resolve(idList) { name ->
        context.resources.getIdentifier(name, "id", context.packageName)
    }

    /** @return null when [idList] is empty or names something [lookup] cannot find. */
    fun resolve(idList: String?, lookup: (String) -> Int): IntArray? {
        if (idList.isNullOrBlank()) return null
        cache[idList]?.let { return it }
        val ids = idList.split(',').map { lookup(it.trim()) }.toIntArray()
        if (ids.any { it == 0 }) return null
        // Shared safely: setReferencedIds copies the ids into the helper's own array.
        cache[idList] = ids
        return ids
    }
}
