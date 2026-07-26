package com.discord.utilities.views

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.drawable.getThemedDrawableRes

/**
 * A `RecyclerView.Adapter` over an immutable list, for the many small, single-view-type lists in the
 * app — permission pickers, overlay participant strips, hub domain selectors and so on.
 *
 * There is no diffing: [setData] replaces the list and invalidates everything. That is deliberate
 * for these lists, which are short and re-rendered wholesale; anything long enough to notice the
 * difference should use `MGRecyclerAdapterSimple` with a `DiffCreator` instead.
 *
 * @param createViewHolder builds a holder from an inflater and the parent, so callers do not have to
 *   subclass the adapter just to inflate a row.
 */
open class SimpleRecyclerAdapter<T, VH : SimpleRecyclerAdapter.ViewHolder<T>>(
    data: List<T> = emptyList(),
    private val createViewHolder: (LayoutInflater, ViewGroup) -> VH,
) : RecyclerView.Adapter<VH>() {

    private var data: List<T> = data

    override fun getItemCount(): Int = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        createViewHolder(LayoutInflater.from(parent.context), parent)

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

    /** Replaces the whole list and rebinds. */
    fun setData(data: List<T>) {
        this.data = data
        notifyDataSetChanged()
    }

    /** Holder that knows how to render one [T]. */
    abstract class ViewHolder<T>(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(data: T)
    }

    companion object {
        /**
         * Adds a divider between rows using the theme's `colorPrimaryDivider`, matching the
         * layout manager's orientation.
         *
         * Requires a [LinearLayoutManager] to already be set — the orientation is read from it.
         */
        fun RecyclerView.addThemedDivider() {
            val dividerRes = getThemedDrawableRes(R.attr.colorPrimaryDivider)
            val divider = ResourcesCompat.getDrawable(resources, dividerRes, context.theme) ?: return
            val orientation = (layoutManager as LinearLayoutManager).orientation
            addItemDecoration(
                DividerItemDecoration(context, orientation).apply { setDrawable(divider) },
            )
        }
    }
}
