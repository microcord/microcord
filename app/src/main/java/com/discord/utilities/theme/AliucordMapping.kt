package com.discord.utilities.theme

/**
 * How Aliucord theme sections land on this app's resources.
 *
 * Aliucord's Themer (Vendicated/AliucordPlugins `Themer`) expands each `simple_colors` key into fixed
 * color and attribute lists ([SIMPLE]), then swaps colors by value. The by-value part is replayed per
 * name through [REPAINTED]. Bump [VERSION] whenever a table changes, because it is part
 * of the cached resource table's key.
 */
internal object AliucordMapping {
    const val VERSION = 4

    /** A color or attribute name, optionally with its alpha byte forced (Aliucord's `alpha` rules). */
    class Target(val name: String, val alpha: Int? = null) {
        fun apply(color: Int) = if (alpha == null) color else (color and 0x00FFFFFF) or (alpha shl 24)
    }

    class SimpleRule(val colors: List<Target>, val attrs: List<Target>, val tints: List<String> = emptyList())

    private val ACTIVE_CHANNEL = listOf("colorChannelItemSelected", "colorBackgroundModifierSelected")
    private val CHANNEL_OVERLAYS = listOf(
        "drawable_overlay_channels_selected_dark", "drawable_overlay_channels_selected_light",
        "drawable_overlay_channels_active_dark", "drawable_overlay_channels_active_light",
    )
    private val INPUT_BACKGROUND = listOf("colorChatInputBackground", "colorInputBackground")

    /**
     * `simple_colors` keys, ported from Themer's `ThemeLoader`/`Constants`.
     *
     * Deliberate differences:
     * - `brand_new_500` is not an attribute here, so it becomes `color_brand_500`.
     * - `uikit_btn_bg_color_selector_secondary_dark` is not repainted with the accent. Its enabled
     *   leaf is the muted grey that icons and dividers share.
     * - `statusbar` sets nothing. The app draws edge to edge, so the status bar shows whatever
     *   surface is behind it.
     */
    val SIMPLE: Map<String, SimpleRule> = mapOf(
        "accent" to SimpleRule(
            colors = listOf(
                "link", "link_light", "brand_new", "brand_new_230", "brand_new_360", "brand_new_500", "brand_new_530",
                "brand_new_560", "brand_new_600", "uikit_btn_bg_color_selector_brand",
                "uikit_btn_compound_color_selector_dark", "uikit_btn_compound_color_selector_light",
            ).map(::Target),
            attrs = listOf(
                "color_brand", "color_brand_500", "colorControlBrandForeground", "colorControlActivated", "colorTextLink",
                "theme_chat_mention_foreground",
            ).map(::Target) + Target("theme_chat_mention_background", alpha = 0x1A),
            tints = listOf("ic_nitro_rep", "drawable_voice_indicator_speaking"),
        ),
        "background" to SimpleRule(
            colors = listOf(
                "dark_grey_2", "primary_600", "primary_660", "primary_800", "primary_dark_600", "primary_dark_630",
                "primary_dark_800",
            ).map(::Target),
            attrs = listOf(
                "colorSurface", "colorBackgroundFloating", "colorTabsBackground", "theme_chat_spoiler_inapp_bg",
                "primary_600", "primary_660", "primary_800",
            ).map(::Target),
        ),
        "background_secondary" to SimpleRule(
            colors = listOf("primary_500", "primary_630", "primary_700", "primary_dark_660", "primary_dark_700").map(::Target),
            attrs = (listOf("colorBackgroundTertiary", "colorBackgroundSecondary", "primary_700", "theme_chat_spoiler_bg") +
                INPUT_BACKGROUND + ACTIVE_CHANNEL).map(::Target),
            tints = CHANNEL_OVERLAYS,
        ),
        "mention_highlight" to SimpleRule(
            colors = listOf(Target("status_yellow_500", alpha = 0xFF)),
            attrs = listOf(Target("theme_chat_mentioned_me")),
        ),
        "active_channel" to SimpleRule(emptyList(), ACTIVE_CHANNEL.map(::Target), CHANNEL_OVERLAYS),
        "input_background" to SimpleRule(emptyList(), INPUT_BACKGROUND.map(::Target)),
        "blocked_bg" to SimpleRule(emptyList(), listOf(Target("theme_chat_blocked_bg"))),
        "statusbar" to SimpleRule(emptyList(), emptyList()),
    )

    /**
     * Selector files Aliucord themes recolor by name, and the leaf color of each one's enabled or
     * checked state.
     *
     * Overriding the selector itself with one color would flatten its disabled and unchecked
     * states. Recoloring the leaf keeps them. Any other selector name is skipped when the table
     * is built.
     *
     * `uikit_settings_item_text_color_light` is skipped on purpose. Themes set it next to the
     * `_dark` one, and its leaf is the grey that dark-theme icons and dividers use.
     */
    val SELECTOR_LEAVES: Map<String, String> = mapOf(
        "uikit_btn_bg_color_selector_brand" to "brand_new_500",
        "uikit_btn_bg_color_selector_green" to "status_green_600",
        "uikit_btn_bg_color_selector_red" to "status_red_500",
        "uikit_btn_compound_color_selector_dark" to "brand_360",
        "uikit_btn_compound_color_selector_light" to "brand_500",
        "uikit_settings_item_text_color_dark" to "primary_100",
    )

    /**
     * Theme attributes Aliucord's by-value swap reached that this app's redesign points at other
     * colors.
     *
     * In Discord 126.21 each attribute here resolved to one of the listed color names. This app
     * repainted them; `colorBackgroundPrimary` is `neutral_66` here, for example. So overriding the
     * color by name alone no longer reaches them. Every other attribute still uses the same color
     * name as 126.21, and a `colors` override reaches it directly.
     */
    private val REPAINTED: List<Pair<List<String>, List<String>>> = listOf(
        listOf("status_yellow_500_alpha_10") to listOf("colorBackgroundMentioned", "colorInfoWarningBackground", "theme_chat_mentioned_me"),
        listOf("brand_500_alpha_20") to listOf("color_brand_alpha_20"),
        listOf("primary_500_alpha_60") to listOf("colorBackgroundModifierActive", "colorBackgroundModifierSelected"),
        listOf("black", "black_500", "black_530", "black_560", "black_600", "black_630", "black_660", "black_700", "black_730", "black_760", "black_800", "black_830", "black_860", "black_900") to listOf("colorOnBackground", "colorOnSecondary"),
        listOf("focus_primary", "focus_primary_500", "link", "link_500") to listOf("colorTextLink"),
        listOf("primary_800", "primary_dark_800") to listOf("colorBackgroundFloating", "colorSurface", "colorTabsBackground"),
        listOf("primary_700", "primary_dark_700") to listOf("colorBackgroundTertiary", "colorInputBackground", "notice_popup_bg"),
        listOf("primary_660", "primary_dark_660") to listOf("actionModeSplitBackground", "colorPrimaryDark"),
        listOf("primary_630", "primary_dark_630") to listOf("colorBackgroundMobileSecondary", "colorBackgroundNestedFloating", "colorBackgroundSecondary", "primary_630", "theme_thread_browser_fg", "toolbarColor"),
        listOf("dark_grey_2_alpha_10") to listOf("theme_friends_divider"),
        listOf("primary_600", "primary_dark_600") to listOf("boost_confirmation_card_bg", "colorBackgroundMobilePrimary", "colorBackgroundPrimary", "colorPrimary", "colorPrimarySurface", "primary_600", "theme_thread_browser_bg"),
        listOf("flexInputIconColor", "primary_500", "primary_dark", "primary_dark_500") to listOf("colorBackgroundAccent"),
        listOf("primary_360", "primary_dark_360") to listOf("colorChannelDefault"),
        listOf("primary_dark_330") to listOf("colorTextMuted"),
        listOf("primary_300", "primary_dark_300") to listOf("colorHeaderSecondary", "colorInteractiveNormal"),
        listOf("primary_200", "primary_dark_200") to listOf("colorInteractiveHover", "colorTextNormal"),
        listOf("primary_100", "primary_dark_100") to listOf("colorControlNormal"),
        listOf("primary_000", "white", "white_100", "white_130", "white_160", "white_200", "white_230", "white_260", "white_300", "white_330", "white_345", "white_360", "white_400", "white_430", "white_460", "white_500") to listOf("colorOnPrimary", "colorOnPrimarySurface", "colorOnSurface", "elevationOverlayColor"),
    )

    private val attrsByColorName: Map<String, List<String>> by lazy {
        buildMap<String, MutableList<String>> {
            REPAINTED.forEach { (names, attrs) -> names.forEach { getOrPut(it) { mutableListOf() } += attrs } }
        }
    }

    /** Themer's extra tints for some palette colors. */
    val COLOR_TINTS: Map<String, List<String>> = mapOf("brand_500" to listOf("ic_nitro_rep"))

    /** Theme attributes that held [colorName]'s value in 126.21's dark theme and no longer use that name. */
    fun attrsForColor(colorName: String): List<String> = attrsByColorName[colorName].orEmpty()

    /** For tests: every name in [REPAINTED]. */
    internal val repaintedNames get() = REPAINTED.flatMap { it.first } to REPAINTED.flatMap { it.second }
}
