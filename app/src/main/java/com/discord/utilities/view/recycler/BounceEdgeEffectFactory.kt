package com.discord.utilities.view.recycler

import android.graphics.Canvas
import kotlin.math.abs
import android.util.Log
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.EdgeEffect
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * Rubber-band overscroll, in place of the platform's glow or stretch.
 *
 * Dragging past an edge shifts the whole list and lets go of it on a spring, which is what iOS does
 * and what the reference therefore gets for free from `UIScrollView`. The resistance curve is that
 * one, scaled down to [TRAVEL]: the offset approaches a fixed ceiling asymptotically, so the first
 * pixels move nearly one-to-one and it stiffens sharply the further it is pulled. A flat multiplier
 * gives itself away immediately by staying equally loose however hard it is dragged.
 *
 * The list itself is translated, not its items, so nothing has to be undone when a row is recycled.
 * The parent keeps clipping: the far edge slides out of view while the near edge reveals the
 * container behind it, which is the same movement the content appears to make.
 */
class BounceEdgeEffectFactory : RecyclerView.EdgeEffectFactory() {

    override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect =
        object : EdgeEffect(view.context) {

            /** Pulling the top or left edge moves the list forward; the far edges move it back. */
            private val sign = if (direction == DIRECTION_BOTTOM || direction == DIRECTION_RIGHT) -1f else 1f
            private val horizontal = direction == DIRECTION_LEFT || direction == DIRECTION_RIGHT
            private val property = if (horizontal) SpringAnimation.TRANSLATION_X else SpringAnimation.TRANSLATION_Y
            private val size: Float
                get() = (if (horizontal) view.width else view.height).toFloat().coerceAtLeast(1f)

            /** Drag distance in pixels, before the resistance curve is applied to it. */
            private var pulled = 0f

            private val spring = SpringAnimation(view, property, 0f).apply {
                spring = SpringForce(0f)
                    .setDampingRatio(DAMPING_RATIO)
                    .setStiffness(STIFFNESS)
            }

            /**
             * Whether there is anything to scroll.
             *
             * The reference turns bounce off on every short screen it has — the auth and
             * verification forms, alerts, tab rails, sized action sheets — which is `UIScrollView`
             * declining to bounce content that already fits. A list has no such default: it
             * overscrolls a half-empty settings page as readily as a year of messages.
             */
            private val scrollable: Boolean
                get() = if (horizontal) {
                    view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)
                } else {
                    view.canScrollVertically(-1) || view.canScrollVertically(1)
                }

            override fun onPull(deltaDistance: Float) = onPull(deltaDistance, 0.5f)

            override fun onPull(deltaDistance: Float, displacement: Float) {
                super.onPull(deltaDistance, displacement)
                if (!scrollable) return
                spring.cancel()
                // Grabbing a list mid-bounce finds it already displaced. Counting from zero would
                // snap it back to nothing before it started following the finger again.
                if (pulled == 0f) pulled = unband(abs(property.getValue(view)), size)
                // The delta arrives as a fraction of the viewport; the curve works in pixels.
                pulled = (pulled + deltaDistance * size).coerceAtLeast(0f)
                property.setValue(view, sign * rubberBand(pulled, size))
            }

            override fun onRelease() {
                super.onRelease()
                releasePlatformDistance()
                pulled = 0f
                // The velocity is sticky, so it has to be cleared or the last fling's is reused.
                if (property.getValue(view) != 0f) spring.setStartVelocity(0f).start()
            }

            /**
             * A fling that ran out of list. The velocity carries into the spring, so the list
             * overshoots and settles rather than stopping dead against the edge.
             *
             * What arrives is the content's velocity, but the list is only moving at the rate the
             * band is letting it — handing the spring the full figure while already stretched pays
             * the same energy twice and throws it most of a screen.
             */
            override fun onAbsorb(velocity: Int) {
                super.onAbsorb(velocity)
                releasePlatformDistance()
                val outward = velocity * bandSlope(pulled, size)
                pulled = 0f
                spring.cancel()
                spring.setStartVelocity(sign * outward).start()
            }

            /**
             * Hands back the distance the superclass has been accumulating through [onPull].
             *
             * It is wound down inside `EdgeEffect.update`, which only ever runs from `draw` — and
             * this one never delegates, so left alone it stays raised for good. A list stuck that
             * way stops taking taps entirely: `RecyclerView` asks the effect on every touch down
             * whether there is a stretch to catch, and takes the press as a grab when there is.
             */
            private fun releasePlatformDistance() = finish()

            /** The translation is the whole effect; none of the platform's own drawing is wanted. */
            override fun draw(canvas: Canvas): Boolean = false

            /** Reporting work outstanding would have the list invalidate itself forever. */
            override fun isFinished(): Boolean = pulled == 0f && !spring.isRunning
        }

    companion object {
        /**
         * `UIScrollView`'s resistance constant, and the same number the reference reaches for by
         * hand when it resists a drag of its own (`POP_RESISTANCE`). Lower drags harder.
         */
        private const val RESISTANCE = 0.55f

        /**
         * How far the list may travel, as a fraction of its own length.
         *
         * The curve below approaches this, so it sets the scale of the whole effect. `UIScrollView`
         * lets the offset run at the viewport itself, which at this size is most of a screen — far
         * more than the give wanted here. This sits under the platform's own stretch, at 0.032 of
         * the view, so the list reads as sprung without a scroll ever becoming a drag of the list.
         * Bounding it at all follows the reference, which caps its own resisted drags rather than
         * letting them run (`MAX_DRAWER_VERTICAL_DRAG`).
         */
        private const val TRAVEL = 0.02f

        /**
         * The reference's `ON_PRESS_SPRING`, which is
         * `{mass: 1, overshootClamping: true, damping: 27, stiffness: 300}`.
         *
         * Reanimated states a spring as mass and damping where [SpringForce] states it as stiffness
         * and a ratio, so `27 / (2 * sqrt(300 * 1))` is 0.78 — but that is the same spring the
         * reference pairs with clamping, and the clamping is the point. A scroll view cannot
         * overshoot its own edge, because past the edge there is nothing to show; a list that
         * crosses its resting position and swings back is doing something no scroller does.
         */
        private const val STIFFNESS = 300f
        private const val DAMPING_RATIO = SpringForce.DAMPING_RATIO_NO_BOUNCY

        /**
         * What a saturating curve alone gets wrong: past a certain drag it stops answering, and a
         * list that has stopped moving under a finger that is still moving reads as jammed. The
         * reference is asymptotic but never flat, so a little is always given back.
         */
        private const val CREEP = 0.02f

        private fun rubberBand(distance: Float, dimension: Float): Float {
            val travel = dimension * TRAVEL
            return (1f - (1f / (distance * RESISTANCE / travel + 1f))) * travel + distance * CREEP
        }

        /**
         * [rubberBand] read backwards, to recover the drag behind a displacement. [CREEP] is left
         * out of the inversion: it has no closed form together with the curve, and it contributes
         * so little over the range this is asked about that carrying it would only risk the seed
         * running away as the curve flattens.
         */
        private fun unband(offset: Float, dimension: Float): Float {
            val travel = dimension * TRAVEL
            val y = offset.coerceAtMost(travel * 0.95f)
            return y / (travel - y) * travel / RESISTANCE
        }

        /** How far the list moves per pixel of finger at [distance] — [rubberBand]'s derivative. */
        private fun bandSlope(distance: Float, dimension: Float): Float {
            val k = distance * RESISTANCE / (dimension * TRAVEL) + 1f
            return 1f / (k * k) + CREEP
        }

        /**
         * Gives [recyclerView] the bounce.
         *
         * Overscroll has to be allowed for an edge effect to be consulted at all, and these lists
         * turn it off in XML to be rid of the platform glow — which silences this with it.
         */
        fun install(recyclerView: RecyclerView) {
            recyclerView.overScrollMode = RecyclerView.OVER_SCROLL_ALWAYS
            recyclerView.edgeEffectFactory = BounceEdgeEffectFactory()
        }

        /** Whether the platform's own overscroll is the stretch rather than the glow. */
        private val stretches = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /**
         * Gives every scroller under [root] the bounce, so a screen gets it by existing rather than
         * by remembering to ask.
         *
         * Scroll views cannot be given an edge effect — the platform owns theirs, and it is not
         * pluggable — so the closest thing available is to stop silencing it, which is only worth
         * doing where it is the stretch. Below that the app's choice to show nothing at all beats
         * bringing the glow back.
         *
         * Anything inflated after this runs is missed; nested fragments, sheets and dialogs are
         * covered because each walks its own tree when its view is created.
         */
        fun installAll(root: View) {
            when (root) {
                is RecyclerView -> install(root)
                is ScrollView, is NestedScrollView, is HorizontalScrollView ->
                    if (stretches) root.overScrollMode = View.OVER_SCROLL_ALWAYS
            }
            if (root is ViewGroup) {
                for (index in 0 until root.childCount) installAll(root.getChildAt(index))
            }
        }
    }
}
