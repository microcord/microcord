package com.discord.widgets.settings.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.api.guildmember.PatchGuildMemberBody
import com.discord.api.user.DisplayNameStyles
import com.discord.api.user.PatchUserBody
import com.discord.app.AppFragment
import com.discord.nullserializable.NullSerializable
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.displaynamestyles.DisplayNameStyleColors
import com.discord.utilities.displaynamestyles.DisplayNameStyleFonts
import com.discord.utilities.displaynamestyles.DisplayNameStylePresets
import com.discord.utilities.displaynamestyles.DisplayNameStyleRenderer
import com.discord.utilities.displaynamestyles.DisplayNameStyleUtils
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.premium.grantsAccessToProfileCustomization
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.views.DisplayNameTextView
import com.discord.widgets.settings.SettingsNavigation
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import kotlin.random.Random

/**
 * Picks the font, effect and colours a display name is drawn with.
 *
 * The three rows each open a picker and write back into one pending [DisplayNameStyles]; nothing
 * reaches the server until Apply, which only appears once something has actually changed. The
 * preview is built from the real renderer rather than from mock art, and shows the two places a
 * style lands differently: a profile, where the colour and effect are drawn, and a chat row, where
 * only the font is.
 *
 * Opened for the account or for one guild. In guild scope the destructive action clears the
 * override rather than the style, so it reads as "use default".
 */
class WidgetDisplayNameStyles : AppFragment(R.layout.widget_display_name_styles) {

    private lateinit var previewName: DisplayNameTextView
    private lateinit var chatName: DisplayNameTextView
    private lateinit var fontValue: TextView
    private lateinit var effectValue: TextView
    private lateinit var colorValue: TextView
    private lateinit var colorSwatch: View
    private lateinit var apply: MaterialButton
    private lateinit var dimmer: DimmerView
    private lateinit var scroll: NestedScrollView

    private var guildId: Long? = null
    private var displayName: String = ""
    private var saved: DisplayNameStyles? = null
    private var pending: DisplayNameStyles = DisplayNameStyles()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.display_name_styles_title)

        guildId = mostRecentIntent.getLongExtra(ARG_GUILD_ID, NO_GUILD).takeIf { it != NO_GUILD }
        previewName = view.findViewById(R.id.display_name_styles_preview_name)
        chatName = view.findViewById(R.id.display_name_styles_chat_name)
        fontValue = view.findViewById(R.id.display_name_styles_font_value)
        effectValue = view.findViewById(R.id.display_name_styles_effect_value)
        colorValue = view.findViewById(R.id.display_name_styles_color_value)
        colorSwatch = view.findViewById(R.id.display_name_styles_color_swatch)
        apply = view.findViewById(R.id.display_name_styles_apply)
        scroll = view.findViewById(R.id.display_name_styles_scroll)
        dimmer = view.findViewById(R.id.display_name_styles_dimmer)

        val me = StoreStream.getUsers().getMe()
        val member = guildId?.let { StoreStream.getGuilds().getMember(it, me.id) }
        displayName = me.getDisplayName(member?.nick)
        saved = DisplayNameStyleUtils.resolve(me, member)
        pending = saved ?: defaultStyle()

        val avatar = IconUtils.getForUser(me)
        view.findViewById<SimpleDraweeView>(R.id.display_name_styles_preview_avatar).setImageURI(avatar)
        view.findViewById<SimpleDraweeView>(R.id.display_name_styles_chat_avatar).setImageURI(avatar)
        view.findViewById<TextView>(R.id.display_name_styles_preview_username).text =
            me.getUserNameWithDiscriminator()

        val editable = me.premiumTier.grantsAccessToProfileCustomization()
        view.findViewById<TextView>(R.id.display_name_styles_upsell).isVisible = !editable

        view.findViewById<View>(R.id.display_name_styles_font_row).setOnClickListener {
            if (editable) openPicker(WidgetDisplayNameStyleSheet.Kind.FONT)
        }
        view.findViewById<View>(R.id.display_name_styles_effect_row).setOnClickListener {
            if (editable) openPicker(WidgetDisplayNameStyleSheet.Kind.EFFECT)
        }
        view.findViewById<View>(R.id.display_name_styles_color_row).setOnClickListener {
            if (editable) openPicker(WidgetDisplayNameStyleSheet.colorKindFor(pending.effectId))
        }
        view.findViewById<MaterialButton>(R.id.display_name_styles_surprise).apply {
            isVisible = editable
            setOnClickListener { select(randomStyle()) }
        }
        view.findViewById<MaterialButton>(R.id.display_name_styles_remove).apply {
            // Nothing to remove until there is a saved style; in a guild, nothing to fall back to
            // unless an override exists.
            isVisible = editable && saved != null
            setText(
                if (guildId != null) {
                    R.string.display_name_styles_guild_default
                } else {
                    R.string.display_name_styles_remove
                },
            )
            setOnClickListener { save(null) }
        }
        apply.setOnClickListener { save(pending) }

        setFragmentResultListener(WidgetDisplayNameStyleSheet.RESULT_KEY) { _, bundle -> onPicked(bundle) }
        select(pending)
    }

    private fun openPicker(kind: WidgetDisplayNameStyleSheet.Kind) =
        WidgetDisplayNameStyleSheet.show(parentFragmentManager, kind, pending)

    private fun onPicked(bundle: Bundle) {
        val style = when {
            bundle.containsKey(WidgetDisplayNameStyleSheet.RESULT_FONT_ID) ->
                pending.copy(fontId = bundle.getInt(WidgetDisplayNameStyleSheet.RESULT_FONT_ID))
            bundle.containsKey(WidgetDisplayNameStyleSheet.RESULT_EFFECT_ID) -> {
                val effectId = bundle.getInt(WidgetDisplayNameStyleSheet.RESULT_EFFECT_ID)
                pending.copy(
                    effectId = effectId,
                    colors = DisplayNameStylePresets.adaptColors(pending.colors, effectId),
                )
            }
            else -> pending.copy(
                colors = bundle.getIntArray(WidgetDisplayNameStyleSheet.RESULT_COLORS)
                    ?.toList().orEmpty(),
            )
        }
        select(style)
    }

    private fun select(style: DisplayNameStyles) {
        pending = style
        previewName.text = displayName
        chatName.text = displayName
        DisplayNameStyleRenderer.apply(previewName, pending)
        DisplayNameStyleRenderer.apply(chatName, pending, DisplayNameStyleRenderer.Mode.FONT)

        fontValue.setText(DisplayNameStylePresets.fontName(pending.fontId))
        effectValue.setText(DisplayNameStylePresets.effectName(pending.effectId))
        colorValue.text = pending.colors
            .takeIf { it.isNotEmpty() }
            ?.joinToString { String.format("#%06X", it and RGB_MASK) }
            ?: getString(R.string.display_name_styles_none)
        colorSwatch.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            pending.colors
                .takeIf { it.isNotEmpty() }
                ?.map(DisplayNameStyleColors::opaque)
                ?.let { if (it.size == 1) listOf(it.first(), it.first()) else it }
                ?.toIntArray()
                ?: IntArray(2) { ColorCompat.getThemedColor(requireContext(), R.attr.colorTextMuted) },
        ).apply { shape = GradientDrawable.OVAL }

        val changed = pending != saved
        apply.isVisible = changed
        // Only reserve room for the floating button while it is actually there; a fixed inset
        // leaves an empty strip under the last row the rest of the time.
        scroll.setPadding(0, 0, 0, dp(if (changed) APPLY_INSET_DP else EDGE_INSET_DP))
    }

    /**
     * Sends [style], or clears it when null.
     *
     * All three fields move together: the server reads them as one style, so sending a font
     * without its effect would leave the other two at whatever they already were.
     */
    private fun save(style: DisplayNameStyles?) {
        val font = style?.let { NullSerializable.Value(it.fontId) } ?: NullSerializable.Null()
        val effect = style?.let { NullSerializable.Value(it.effectId) } ?: NullSerializable.Null()
        // An empty list is meaningful: it is how "no colour of its own" is stored, so a solid name
        // follows the theme instead of pinning a hex that only suits one of them.
        val colors = style?.let { NullSerializable.Value(it.colors) } ?: NullSerializable.Null()
        val guild = guildId
        val request = if (guild != null) {
            RestAPI.api.updateMeGuildMember(
                guild,
                PatchGuildMemberBody(
                    displayNameFontId = font,
                    displayNameEffectId = effect,
                    displayNameColors = colors,
                ),
            ).map { }
        } else {
            RestAPI.api.patchUser(
                PatchUserBody(
                    displayNameFontId = font,
                    displayNameEffectId = effect,
                    displayNameColors = colors,
                ),
            ).map { }
        }
        request
            .restSubscribeOn(false)
            .withDimmer(dimmer)
            .ui(this)
            .appSubscribe(javaClass) { activity?.onBackPressed() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun defaultStyle() = DisplayNameStyles(
        fontId = DisplayNameStyles.FONT_DEFAULT,
        effectId = DisplayNameStyles.EFFECT_SOLID,
        colors = emptyList(),
    )

    private fun randomStyle(): DisplayNameStyles {
        val effectId = DisplayNameStylePresets.EFFECT_ORDER.random(RANDOM)
        return DisplayNameStyles(
            fontId = DisplayNameStyleFonts.PICKER_ORDER.random(RANDOM),
            effectId = effectId,
            colors = DisplayNameStylePresets.presetsFor(effectId).random(RANDOM),
        )
    }

    companion object {
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"
        private const val NO_GUILD = 0L
        private const val RGB_MASK = 0xFFFFFF
        private const val APPLY_INSET_DP = 80
        private const val EDGE_INSET_DP = 16

        private val RANDOM = Random.Default

        fun launch(context: Context, guildId: Long? = null) {
            SettingsNavigation.launch(
                context,
                WidgetDisplayNameStyles::class.java,
                Intent().putExtra(ARG_GUILD_ID, guildId ?: NO_GUILD),
            )
        }
    }
}
