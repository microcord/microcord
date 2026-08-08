package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.profile.GuildProfile
import com.discord.api.guild.profile.GuildTagBadge
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.models.guild.Guild
import com.discord.nullserializable.NullSerializable
import com.discord.restapi.RestAPIParams
import com.discord.utilities.color.ColorCompat
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setText
import com.discord.views.CheckedSetting
import com.discord.views.guildtag.GuildTagBadgeDrawable
import com.discord.views.guildtag.GuildTagPickerCell
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.StatefulViews
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/**
 * The guild tag members can wear beside their name: a 2–4 character label, a badge, and the badge's
 * colours.
 *
 * Everything is staged and committed by one `PATCH /guilds/{id}/profile`; turning the tag off is
 * sending a null `tag`, which is why the enable switch is not a separate write.
 *
 * Renaming an existing tag detaches it from every member who had adopted it — the server clears
 * their identity so an established tag cannot be swapped out from under them — so the warning below
 * the field only appears on a true rename.
 */
class WidgetServerSettingsServerTag : AppFragment(R.layout.widget_server_settings_server_tag) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val state = StatefulViews(
        R.id.server_tag_name,
        STATE_ID_ENABLED,
        STATE_ID_BADGE,
        STATE_ID_COLOR_PRIMARY,
    )

    private lateinit var enable: CheckedSetting
    private lateinit var customize: View
    private lateinit var nameInput: TextInputLayout
    private lateinit var renameWarning: TextView
    private lateinit var badgeGrid: GridLayout
    private lateinit var colorGrid: GridLayout
    private lateinit var previewBadge: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var save: FloatingActionButton

    private var serverProfile: GuildProfile? = null
    private var badges: List<GuildTagBadge> = emptyList()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.guild_settings_tag)
        enable = view.findViewById(R.id.server_tag_enable)
        customize = view.findViewById(R.id.server_tag_customize)
        nameInput = view.findViewById(R.id.server_tag_name)
        renameWarning = view.findViewById(R.id.server_tag_rename_warning)
        badgeGrid = view.findViewById(R.id.server_tag_badges)
        colorGrid = view.findViewById(R.id.server_tag_colors)
        previewBadge = view.findViewById(R.id.server_tag_preview_badge)
        previewLabel = view.findViewById(R.id.server_tag_preview_label)
        save = view.findViewById(R.id.server_tag_save)

        save.hide()
        state.setupUnsavedChangesConfirmation(this)
        state.setupTextWatcherWithSaveAction(this, save, nameInput)
        buildColorGrid(GuildTagBadge.DEFAULT)
        parentFragmentManager.setFragmentResultListener(
            RoleColorPickerDialog.REQUEST_KEY,
            this,
        ) { _, result ->
            state.put(STATE_ID_COLOR_PRIMARY, result.getInt(RoleColorPickerDialog.RESULT_COLOR))
            if (result.containsKey(RoleColorPickerDialog.RESULT_ACCENT_COLOR)) {
                state.put(
                    STATE_ID_COLOR_ACCENT,
                    result.getInt(RoleColorPickerDialog.RESULT_ACCENT_COLOR),
                )
            }
            render()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        observeModel(guildId).ui(this).appSubscribe(
            WidgetServerSettingsServerTag::class.java,
            context,
            onNext = ::configureUI,
        )
    }

    private fun configureUI(model: Model?) {
        if (model == null) {
            appActivity?.onBackPressed()
            return
        }
        setActionBarSubtitle(model.guild.name)
        serverProfile = model.profile
        buildBadgeGrid(model.guild.features)
        if (nameInput.getTextOrEmpty().isEmpty()) {
            nameInput.setText(state.get(nameInput.id, model.profile?.tag.orEmpty()))
        }
        enable.setChecked(isEnabled(model), false)
        enable.setContainerClickListener {
            enable.toggle()
            state.put(STATE_ID_ENABLED, enable.isChecked())
            render()
        }
        render()
        save.setOnClickListener { saveTag(model) }
    }

    private fun isEnabled(model: Model) =
        state.get(STATE_ID_ENABLED, model.profile?.tag?.isNotEmpty() == true)

    /**
     * Rebuilt from the guild's features rather than once up front: a pack the guild has not bought
     * is not offered at all, and the feature only arrives with the model.
     */
    private fun buildBadgeGrid(features: Set<GuildFeature>) {
        val unlocked = GuildTagBadge.unlockedFor(features)
        if (unlocked == badges) return
        badges = unlocked
        badgeGrid.removeAllViews()
        unlocked.forEach { badge ->
            badgeGrid.addView(
                pickerCell(badge.name) {
                    state.put(STATE_ID_BADGE, badge.apiValue)
                    render()
                },
            )
        }
    }

    /**
     * The colour grid previews the *selected badge* under each palette rather than showing plain
     * swatches, because a palette only means anything through the art it tints.
     */
    private fun buildColorGrid(badge: GuildTagBadge) {
        colorGrid.removeAllViews()
        PALETTE.forEach { palette ->
            colorGrid.addView(
                pickerCell(paletteLabel(palette)) {
                    state.put(STATE_ID_COLOR_PRIMARY, palette.primary)
                    state.put(STATE_ID_COLOR_ACCENT, palette.accent)
                    render()
                },
            )
        }
        colorGrid.addView(
            pickerCell(getString(R.string.guild_settings_tag_default_color_label)) {
                state.put(STATE_ID_COLOR_PRIMARY, NO_COLOR)
                state.put(STATE_ID_COLOR_ACCENT, NO_COLOR)
                render()
            },
        )
        colorGrid.addView(
            pickerCell(getString(R.string.custom_color)) { openCustomColorPicker() },
        )
        renderColorGrid(badge)
    }

    private fun openCustomColorPicker() {
        val badge = shownBadge()
        RoleColorPickerDialog.show(
            parentFragmentManager,
            initialColor = currentPrimary().takeIf { it != NO_COLOR } ?: PALETTE.first().primary,
            titleRes = R.string.guild_settings_tag_choose_badge_color,
            accentColor = if (badge.hasAccentColor) {
                currentAccent().takeIf { it != NO_COLOR } ?: PALETTE.first().accent
            } else {
                null
            },
        )
    }

    private fun pickerCell(description: CharSequence, onClick: () -> Unit) =
        GuildTagPickerCell(requireContext()).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = dp(SWATCH_DP)
                height = dp(SWATCH_DP)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private fun renderColorGrid(badge: GuildTagBadge) {
        PALETTE.forEachIndexed { index, palette ->
            cell(colorGrid, index).setArtwork(
                GuildTagBadgeDrawable(badge, palette.primary or OPAQUE, palette.accent or OPAQUE),
            )
        }
        cell(colorGrid, PALETTE.size).setArtwork(GuildTagBadgeDrawable(badge))
        cell(colorGrid, PALETTE.size + 1)
            .setArtwork(requireContext().getDrawable(R.drawable.ic_eyedropper_24dp))
    }

    private fun cell(grid: GridLayout, index: Int) = grid.getChildAt(index) as GuildTagPickerCell

    private fun shownBadge(): GuildTagBadge =
        GuildTagBadge.fromApiValue(state.get(STATE_ID_BADGE, serverProfile?.badge))
            ?.takeIf { it in badges } ?: GuildTagBadge.DEFAULT

    private fun currentPrimary() =
        state.get(STATE_ID_COLOR_PRIMARY, parseColor(serverProfile?.badgeColorPrimary))

    private fun currentAccent() =
        state.get(STATE_ID_COLOR_ACCENT, parseColor(serverProfile?.badgeColorSecondary))

    private fun render() {
        val profile = serverProfile
        val enabled = state.get(STATE_ID_ENABLED, profile?.tag?.isNotEmpty() == true)
        customize.visibility = if (enabled) View.VISIBLE else View.GONE

        // A legacy or since-revoked badge has no swatch to highlight, and saving keeps whatever the
        // guild already has, so nothing is selected until the user picks one.
        val badge = GuildTagBadge.fromApiValue(state.get(STATE_ID_BADGE, profile?.badge))
            ?.takeIf { it in badges }
        val shown = badge ?: GuildTagBadge.DEFAULT
        val primary = currentPrimary()
        val accent = currentAccent()
        val tag = nameInput.getTextOrEmpty().trim()

        // The badge picker always shows default colours; the colour picker is what previews tints.
        badges.forEachIndexed { index, entry ->
            cell(badgeGrid, index).setArtwork(GuildTagBadgeDrawable(entry))
        }
        renderSelection(badgeGrid, badges.indexOf(badge))

        renderColorGrid(shown)
        renderSelection(colorGrid, selectedColorIndex(primary, accent, shown))

        previewLabel.text = tag
        previewBadge.setImageDrawable(
            GuildTagBadgeDrawable(
                shown,
                primary.takeIf { it != NO_COLOR }?.or(OPAQUE),
                accent.takeIf { it != NO_COLOR }?.or(OPAQUE),
            ),
        )

        val original = profile?.tag.orEmpty()
        renameWarning.visibility =
            if (original.isNotEmpty() && tag != original) View.VISIBLE else View.GONE

        state.configureSaveActionView(save)
    }

    /** The default cell when nothing is set, a preset when both halves match it, else custom. */
    private fun selectedColorIndex(primary: Int, accent: Int, badge: GuildTagBadge): Int {
        if (primary == NO_COLOR) return PALETTE.size
        val preset = PALETTE.indexOfFirst {
            it.primary == primary && (!badge.hasAccentColor || it.accent == accent)
        }
        return if (preset >= 0) preset else PALETTE.size + 1
    }

    private fun renderSelection(grid: GridLayout, selectedIndex: Int) {
        for (index in 0 until grid.childCount) {
            cell(grid, index).setSelectedState(index == selectedIndex)
        }
    }

    private fun saveTag(model: Model) {
        val enabled = isEnabled(model)
        val tag = nameInput.getTextOrEmpty().trim()
        if (enabled && tag.length < MIN_TAG_LENGTH) {
            nameInput.error = getString(R.string.guild_settings_tag_name_label)
            return
        }
        val body = if (!enabled) {
            RestAPIParams.UpdateGuildProfile(tag = NullSerializable.Null())
        } else {
            RestAPIParams.UpdateGuildProfile(
                tag = NullSerializable.Value(tag),
                badge = state.get(STATE_ID_BADGE, model.profile?.badge ?: GuildTagBadge.DEFAULT.apiValue),
                badgeColorPrimary = nullableHex(currentPrimary()),
                badgeColorSecondary = nullableHex(currentAccent()),
            )
        }
        RestAPI.api.updateGuildProfile(model.guild.id, body)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetServerSettingsServerTag::class.java,
                context = null,
                errorHandler = { error ->
                    if (error.statusCode == RATE_LIMITED) {
                        AppToast.show(this, R.string.guild_settings_tag_rate_limited_error)
                    } else {
                        error.showToasts(context)
                    }
                },
            ) { updated ->
                serverProfile = updated
                state.clear(clearDefaults = true)
                hideKeyboard()
                AppToast.show(this, R.string.server_settings_updated)
            }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    data class Model(val guild: Guild, val profile: GuildProfile?)

    private data class Palette(val primary: Int, val accent: Int)

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val MIN_TAG_LENGTH = 2
        private const val SWATCH_DP = 48
        private const val BADGE_ICON_DP = 28
        private const val OPAQUE = 0xFF000000.toInt()
        private const val RATE_LIMITED = 429

        /** Nothing in the RGB space, so it can mean "use Discord's default colours". */
        private const val NO_COLOR = -1

        private const val STATE_ID_ENABLED = 90301
        private const val STATE_ID_BADGE = 90302
        private const val STATE_ID_COLOR_PRIMARY = 90303
        private const val STATE_ID_COLOR_ACCENT = 90304

        /** The reference's thirteen badge palettes, primary paired with accent. */
        private val PALETTE = listOf(
            Palette(0xFF1C90, 0xFF7FC0),
            Palette(0xFF8F1C, 0xFFAE77),
            Palette(0xEEBE1A, 0xFFFC7F),
            Palette(0x32A070, 0x57B59E),
            Palette(0x32839A, 0x71C2D9),
            Palette(0x8A43FF, 0xBD95FF),
            Palette(0x9B3FE5, 0xCC8DFF),
            Palette(0x942E8F, 0xD46CB5),
            Palette(0xD14242, 0xFF8989),
            Palette(0x814300, 0xA88A6C),
            Palette(0x717224, 0xC3C3C3),
            Palette(0x5D1D47, 0xC58FBB),
            Palette(0x222222, 0xCCCCCC),
        )

        private fun hex(color: Int) = String.format("#%06x", color and 0xFFFFFF)

        private fun nullableHex(color: Int): NullSerializable<String> =
            if (color == NO_COLOR) NullSerializable.Null() else NullSerializable.Value(hex(color))

        private fun paletteLabel(palette: Palette) = "${hex(palette.primary)} ${hex(palette.accent)}"

        private fun parseColor(value: String?): Int =
            value?.removePrefix("#")?.toIntOrNull(16) ?: NO_COLOR

        private fun observeModel(guildId: Long): Observable<Model?> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getUsers().observeMe(),
            RestAPI.apiOrNull?.getGuildProfile(guildId)?.restSubscribeOn()?.map { it as GuildProfile? }
                ?.onErrorResumeNext(Observable.just(null))
                ?: Observable.just(null),
        ) { guild, permissions, me, profile ->
            val allowed = guild != null &&
                guild.hasFeature(GuildFeature.GUILD_TAGS) &&
                (guild.isOwner(me.id) || PermissionUtils.can(Permission.MANAGE_GUILD, permissions))
            if (allowed) Model(guild!!, profile) else null
        }.computationLatest().distinctUntilChanged()

        fun newInstance(guildId: Long) = WidgetServerSettingsServerTag().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("TAG", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsServerTag::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
