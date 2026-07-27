package com.discord.widgets.guild_role_subscriptions.tier

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Spacing for the tier preview list, keyed off the row type rather than the position.
 *
 * The three exceptions to the uniform inset all exist so a row can meet the one above it:
 *  - the member preview is a full-bleed chat sample, so it keeps only half its left inset;
 *  - the plan-details card is full-bleed on every side;
 *  - the tier header owns its own horizontal padding and only needs to be separated from what
 *    follows.
 */
class GuildRoleSubscriptionTierItemDecoration(
    private val verticalSpacing: Int,
    private val horizontalSpacing: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        super.getItemOffsets(outRect, view, parent, state)
        val position = parent.getChildAdapterPosition(view)
        when (parent.adapter?.getItemViewType(position)) {
            GuildRoleSubscriptionTierAdapter.VIEW_TYPE_TIER_MEMBER_PREVIEW -> {
                outRect.top = verticalSpacing
                outRect.bottom = verticalSpacing
                outRect.left = horizontalSpacing / 2
                outRect.right = horizontalSpacing
            }
            GuildRoleSubscriptionTierAdapter.VIEW_TYPE_PLAN_DETAILS -> Unit
            GuildRoleSubscriptionTierAdapter.VIEW_TYPE_TIER_HEADER -> {
                outRect.bottom = verticalSpacing
            }
            else -> {
                outRect.left = horizontalSpacing
                outRect.right = horizontalSpacing
                outRect.top = verticalSpacing
                outRect.bottom = verticalSpacing
            }
        }
    }
}
