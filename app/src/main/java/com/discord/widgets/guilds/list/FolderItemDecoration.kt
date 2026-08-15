package com.discord.widgets.guilds.list

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Draws the rounded "capsule" behind a folder and the guilds it contains.
 *
 * Folder children are ordinary top-level rows in the adapter — nothing nests — so the grouping is
 * purely decorative and has to be painted underneath them here.
 *
 * Three drawables, all resolved from theme attributes by the fragment:
 * * [drawableNoChildren] — the folder-creation preview under a guild that is currently being
 *   dragged onto,
 * * [tintableDrawableNoChildren] — a collapsed folder, always tinted with its colour,
 * * [drawableWithChildren] — the tall capsule spanning a folder and its expanded children.
 *
 * [defaultColor] is the reference's `DEFAULT_FOLDER_COLOR`, used where a folder sets none.
 */
class FolderItemDecoration(
    private val drawableNoChildren: Drawable,
    private val tintableDrawableNoChildren: Drawable,
    private val drawableWithChildren: Drawable,
    @ColorInt private val defaultColor: Int,
    sizePx: Int,
) : RecyclerView.ItemDecoration() {

    private val halfSize: Int = sizePx / 2

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.childCount < 1) return

        val childCount = parent.childCount
        var index = drawBackgroundForInitialFolder(c, parent)
        while (index < childCount) {
            val view = parent.getChildAt(index)
            val holder = parent.getChildViewHolder(view)

            if (holder is GuildListViewHolder.FolderViewHolder && holder.shouldDrawDecoration()) {
                val centerX = (view.left + view.right) / 2
                val centerY = (view.top + view.bottom) / 2
                val numChildren = holder.numChildren
                if (numChildren == 0) {
                    DrawableCompat.setTint(tintableDrawableNoChildren, holder.color ?: defaultColor)
                    val drawable = tintableDrawableNoChildren
                    drawable.setBounds(
                        centerX - halfSize,
                        centerY - halfSize,
                        centerX + halfSize,
                        centerY + halfSize,
                    )
                    drawable.draw(c)
                } else {
                    drawableWithChildren.setBounds(
                        centerX - halfSize,
                        centerY - halfSize,
                        centerX + halfSize,
                        centerY + (view.height * numChildren) + halfSize,
                    )
                    drawableWithChildren.draw(c)
                }
            }

            if (holder is GuildListViewHolder.GuildViewHolder && holder.isTargetedForFolderCreation()) {
                val centerX = (view.left + view.right) / 2
                val centerY = (view.top + view.bottom) / 2
                drawableNoChildren.setBounds(
                    centerX - halfSize,
                    centerY - halfSize,
                    centerX + halfSize,
                    centerY + halfSize,
                )
                drawableNoChildren.draw(c)
            }

            index++
        }
    }

    /**
     * Paints the part of a capsule whose folder row has already been scrolled off the top.
     *
     * The main pass can only start at a folder it can see. When the list is scrolled into the
     * middle of an expanded folder the first visible rows are its children, so the capsule is drawn
     * from a point [EXTRA_PADDING_PX] above the viewport — and, when the folder continues past the
     * bottom edge too, the same distance below it.
     *
     * @return the index the main pass should start at: the first row that is not part of this
     *   leading capsule.
     */
    private fun drawBackgroundForInitialFolder(canvas: Canvas, parent: RecyclerView): Int {
        val firstView = parent.getChildAt(0)
        val firstHolder = parent.getChildViewHolder(firstView)
        val folderId = (firstHolder as? GuildListViewHolder.GuildViewHolder)?.folderId ?: return 0

        var count = 1
        while (count < parent.childCount) {
            val holder = parent.getChildViewHolder(parent.getChildAt(count))
            if (holder is GuildListViewHolder.GuildViewHolder && holder.folderId == folderId) {
                count++
            } else {
                break
            }
        }
        if (count <= 0) return count

        val centerX = (firstView.left + firstView.right) / 2
        val centerY = (firstView.top + firstView.bottom) / 2

        // The folder also continues below the viewport when every visible row belongs to it and the
        // last one is not the folder's final guild.
        val lastHolder = parent.getChildViewHolder(parent.getChildAt(parent.childCount - 1))
        val continuesBelow = parent.childCount == count &&
            lastHolder is GuildListViewHolder.GuildViewHolder &&
            lastHolder.isLastGuildInFolder == false

        var bottom = ((count - 1) * firstView.height) + centerY + halfSize
        if (continuesBelow) bottom += EXTRA_PADDING_PX

        drawableWithChildren.setBounds(
            centerX - halfSize,
            centerY - halfSize - EXTRA_PADDING_PX,
            centerX + halfSize,
            bottom,
        )
        drawableWithChildren.draw(canvas)
        return count
    }

    private companion object {
        /** How far the capsule is extended past an edge of the viewport it runs off. */
        private const val EXTRA_PADDING_PX = 200
    }
}
