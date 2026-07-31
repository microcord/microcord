package com.discord.app

import android.content.Context
import androidx.annotation.AnimRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import com.discord.R
import com.discord.utilities.accessibility.AccessibilityUtils

/** Applies the reference activity enter/exit animation pairs, respecting reduced motion. */
abstract class AppTransitionActivity : AppCompatActivity() {
    data class Animations(
        @AnimRes val openEnter: Int,
        @AnimRes val openExit: Int,
        @AnimRes val closeEnter: Int,
        @AnimRes val closeExit: Int,
    )

    enum class Transition(val animations: Animations) {
        TYPE_FADE(Animations(R.anim.activity_fade_open_in, R.anim.activity_fade_open_out, R.anim.activity_fade_close_in, R.anim.activity_fade_close_out)),
        TYPE_FADE_FAST(Animations(R.anim.activity_fade_open_in_fast, R.anim.activity_fade_open_out_fast, R.anim.activity_fade_close_in_fast, R.anim.activity_fade_close_out_fast)),
        TYPE_STANDARD(Animations(R.anim.activity_standard_open_in, R.anim.activity_standard_open_out, R.anim.activity_standard_close_in, R.anim.activity_standard_close_out)),
        TYPE_SLIDE_HORIZONTAL(Animations(R.anim.activity_slide_horizontal_open_in, R.anim.activity_slide_horizontal_open_out, R.anim.activity_slide_horizontal_close_in, R.anim.activity_slide_horizontal_close_out)),
        TYPE_SLIDE_HORIZONTAL_REVERSE(Animations(R.anim.activity_slide_horizontal_close_in, R.anim.activity_slide_horizontal_close_out, R.anim.activity_slide_horizontal_open_in, R.anim.activity_slide_horizontal_open_out)),
        TYPE_SLIDE_VERTICAL(Animations(R.anim.activity_slide_vertical_open_in, R.anim.activity_slide_vertical_open_out, R.anim.activity_slide_vertical_close_in, R.anim.activity_slide_vertical_close_out)),
        TYPE_SLIDE_VERTICAL_WITH_FADE(Animations(R.anim.activity_slide_vertical_open_in, R.anim.activity_fade_open_out, R.anim.activity_fade_close_in, R.anim.activity_slide_vertical_close_out)),
        TYPE_SLIDE_POP_VERTICAL(Animations(R.anim.activity_slide_pop_vertical_open_in, R.anim.activity_slide_pop_vertical_open_out, R.anim.activity_slide_pop_vertical_close_in, R.anim.activity_slide_pop_vertical_close_out)),
        TYPE_SLIDE_POP_HORIZONTAL(Animations(R.anim.activity_slide_pop_horizontal_open_in, R.anim.activity_slide_pop_horizontal_open_out, R.anim.activity_slide_pop_horizontal_close_in, R.anim.activity_slide_pop_horizontal_close_out)),
        TYPE_NONE(Animations(0, 0, 0, 0));

        fun toActivityOptions(context: Context): ActivityOptionsCompat? =
            if (AccessibilityUtils.isReducedMotionEnabled()) null
            else ActivityOptionsCompat.makeCustomAnimation(context, animations.openEnter, animations.openExit)
    }

    var transitionAnimations: Animations = Transition.TYPE_SLIDE_POP_VERTICAL.animations

    protected fun markBackNavigation() {
        isBackNavigation = true
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        markBackNavigation()
    }

    override fun onResume() {
        super.onResume()
        applyTransition(opening = true)
        isBackNavigation = false
    }

    override fun onPause() {
        super.onPause()
        applyTransition(opening = false)
    }

    @Suppress("DEPRECATION")
    private fun applyTransition(opening: Boolean) {
        if ((isBackNavigation && opening) || (!isBackNavigation && !opening) || AccessibilityUtils.isReducedMotionEnabled()) return
        val animations = transitionAnimations
        overridePendingTransition(
            if (opening) animations.openEnter else animations.closeEnter,
            if (opening) animations.openExit else animations.closeExit,
        )
    }

    private companion object {
        var isBackNavigation = false
    }
}
