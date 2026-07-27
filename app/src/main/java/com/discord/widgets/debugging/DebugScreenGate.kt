package com.discord.widgets.debugging

import com.discord.BuildConfig

/**
 * Whether the internal debug surfaces may be reached at all.
 *
 * **A deliberate divergence from the reference, and it must stay one.** Discord 126.21 ships its
 * debug screens in the release build: the log viewer hangs off an unconditional `menu_settings`
 * overflow item with no build-type check anywhere on the path, and the experiment-override screen
 * is gated only by `UserUtils.isStaff` — an account flag that arrives from the server, not a
 * property of the binary. Reproducing either faithfully would ship them to every user.
 *
 * That matters more here than it does upstream because of what the log viewer shows. It renders
 * raw log text and copies it to the clipboard on long-press, so anything a call site logs is
 * readable, and on the reference the only thing standing between a token and the screen is
 * call-site discipline. [com.discord.app.AppLog.redactSecrets] gives us a real second line of
 * defence, but defence in depth means the screen should not be reachable in release regardless.
 *
 * Gate every debug entry point in **two** places — the `launch` helper *and* the fragment's
 * `onViewBound` — so a synthetic intent or a restored fragment cannot walk in behind the launcher.
 *
 * Note that hiding a menu row is not gating. `WidgetSettings` currently hides the debug item
 * because the screen was unfinished; the moment a parity pass un-hides it, only this gate is left.
 */
internal object DebugScreenGate {

    val isEnabled: Boolean get() = BuildConfig.DEBUG
}
