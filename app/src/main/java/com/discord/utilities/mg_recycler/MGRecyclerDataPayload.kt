package com.discord.utilities.mg_recycler

import com.discord.utilities.recycler.DiffKeyProvider

/**
 * A single row in an [MGRecyclerAdapterSimple] backed list.
 *
 * [type] selects the view holder to inflate and is returned verbatim from
 * `RecyclerView.Adapter.getItemViewType`; [DiffKeyProvider.key] identifies the row across updates.
 */
interface MGRecyclerDataPayload : DiffKeyProvider {
    val type: Int
}
