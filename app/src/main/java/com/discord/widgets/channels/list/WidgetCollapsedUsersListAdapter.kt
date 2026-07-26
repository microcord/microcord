package com.discord.widgets.channels.list

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.channels.list.items.CollapsedUser
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The overlapping avatar pile used wherever a small group of users has to fit in one row — a voice
 * channel's participants, a call message, a Spotify listening party.
 *
 * The overlap is an item decoration rather than a negative margin so the first avatar keeps its
 * full leading edge and the pile stays right-aligned when it is shorter than the row.
 */
class WidgetCollapsedUsersListAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<CollapsedUser>(recycler) {

    init {
        recycler.addItemDecoration(
            OverlapDecoration(-recycler.context.resources.getDimensionPixelSize(R.dimen.avatar_overlap)),
        )
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, CollapsedUser> =
        WidgetCollapsedUserListItem(R.layout.widget_collapsed_voice_user_list_item, this)

    /** Pulls every item except the first back by [offset], so the avatars overlap. */
    class OverlapDecoration(private val offset: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val params = view.layoutParams as RecyclerView.LayoutParams
            outRect.set(if (params.viewAdapterPosition != 0) offset else 0, 0, 0, 0)
        }
    }

    class WidgetCollapsedUserListItem(
        @LayoutRes layoutResId: Int,
        adapter: WidgetCollapsedUsersListAdapter,
    ) : MGRecyclerViewHolder<WidgetCollapsedUsersListAdapter, CollapsedUser>(layoutResId, adapter) {

        private val count: TextView = itemView.findViewById(R.id.widget_collapsed_user_count)
        private val avatar: SimpleDraweeView =
            itemView.findViewById(R.id.widget_collapsed_user_imageview)

        override fun onConfigure(position: Int, data: CollapsedUser) {
            super.onConfigure(position, data)
            when {
                // The trailing "+N" bubble: a generic silhouette behind the overflow count.
                data.extraCount > 0 -> {
                    count.setTextAndVisibilityBy("+${data.extraCount}")
                    MGImages.setImage(avatar, R.drawable.drawable_grey_user_icon)
                }
                !data.emptySlot -> {
                    count.visibility = View.GONE
                    IconUtils.setIcon(avatar, data.user, R.dimen.avatar_size_medium)
                }
                // An empty slot with no overflow count is a spacer; both views keep their
                // previous content, matching the reference.
            }
        }
    }
}
