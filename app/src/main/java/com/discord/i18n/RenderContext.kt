package com.discord.i18n

import android.view.View
import androidx.annotation.ColorInt

/** Mutable configuration consumed while rendering a translated first-party string. */
class RenderContext {
    val args: MutableMap<String, String> = HashMap()
    internal val hooks: MutableMap<String, Hook> = HashMap()
    internal var orderedArguments: List<Any?>? = null

    @ColorInt
    var boldColor: Int? = null

    @ColorInt
    var strikethroughColor: Int? = null

    var uppercase: Boolean = false
    internal var hasClickables: Boolean = false

    fun hook(key: String, initializer: Hook.() -> Unit) {
        hooks[key] = Hook().apply(initializer)
        hasClickables = hasClickables || hooks[key]?.onClick != null
    }

    fun onClick(key: String, @ColorInt color: Int? = null, action: (View) -> Unit) {
        hook(key) {
            clickColor = color
            onClick = action
        }
    }
}
