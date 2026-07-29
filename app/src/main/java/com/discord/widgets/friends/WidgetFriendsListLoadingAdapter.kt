package com.discord.widgets.friends

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R

/**
 * The skeleton shown while relationships are still loading.
 *
 * A fixed [LOADING_CELLS_COUNT] placeholder rows, deliberately more than fit on screen so the list
 * looks like it continues past the fold. Nothing is bound — the row is a static placeholder
 * drawable — which is why [onBindViewHolder] is empty in the reference too.
 */
class WidgetFriendsListLoadingAdapter : RecyclerView.Adapter<FriendsListLoadingViewHolder>() {

    override fun getItemCount(): Int = LOADING_CELLS_COUNT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendsListLoadingViewHolder =
        FriendsListLoadingViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_friends_list_adapter_item_loading, parent, false),
        )

    override fun onBindViewHolder(holder: FriendsListLoadingViewHolder, position: Int) = Unit

    private companion object {
        private const val LOADING_CELLS_COUNT = 15
    }
}
