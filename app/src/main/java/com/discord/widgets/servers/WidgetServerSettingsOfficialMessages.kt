package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.StatefulViews
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.Observable

/**
 * The colour used to highlight messages a moderator marked as official.
 *
 * Reference: `GuildSettingsModalOfficialMessages`. The screen owns one guild field,
 * `official_message_color`, and previews it the way the chat list will render it: the row is tinted
 * at [HIGHLIGHT_ALPHA] and the body text is nudged until it clears WCAG contrast against that tint.
 */
class WidgetServerSettingsOfficialMessages :
    AppFragment(R.layout.widget_server_settings_official_messages) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val state = StatefulViews(STATE_ID_COLOR)

    private lateinit var colorContainer: View
    private lateinit var colorValue: TextView
    private lateinit var colorSwatch: View
    private lateinit var preview: View
    private lateinit var previewBody: TextView
    private lateinit var save: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.guild_settings_official_messages)
        colorContainer = view.findViewById(R.id.official_messages_color_container)
        colorValue = view.findViewById(R.id.official_messages_color_value)
        colorSwatch = view.findViewById(R.id.official_messages_color_swatch)
        preview = view.findViewById(R.id.official_messages_preview)
        previewBody = view.findViewById(R.id.official_messages_preview_body)
        save = view.findViewById(R.id.official_messages_save)
        save.hide()
        state.setupUnsavedChangesConfirmation(this)
        parentFragmentManager.setFragmentResultListener(
            RoleColorPickerDialog.REQUEST_KEY,
            this,
        ) { _, result ->
            state.put(STATE_ID_COLOR, result.getInt(RoleColorPickerDialog.RESULT_COLOR))
            renderColor(state.get(STATE_ID_COLOR, DEFAULT_COLOR))
            state.configureSaveActionView(save)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        observeModel(guildId)
            .ui(this)
            .appSubscribe(WidgetServerSettingsOfficialMessages::class.java, context) { guild ->
                if (guild == null) {
                    appActivity?.onBackPressed()
                    return@appSubscribe
                }
                setActionBarSubtitle(guild.name)
                val current = state.get(STATE_ID_COLOR, guild.officialMessageColor ?: DEFAULT_COLOR)
                renderColor(current)
                colorContainer.setOnClickListener {
                    RoleColorPickerDialog.show(parentFragmentManager, current)
                }
                state.configureSaveActionView(save)
                save.setOnClickListener { saveColor(guild, current) }
            }
    }

    private fun renderColor(color: Int) {
        val opaque = color or OPAQUE
        colorValue.text = String.format("#%06X", color and RGB_MASK)
        colorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(opaque)
        }
        preview.setBackgroundColor(ColorUtils.setAlphaComponent(opaque, HIGHLIGHT_ALPHA))
        previewBody.setTextColor(accessibleTextColor(opaque))
    }

    private fun saveColor(guild: Guild, color: Int) {
        RestAPI.api.updateGuild(
            guild.id,
            RestAPIParams.UpdateGuild(officialMessageColor = color and RGB_MASK),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsOfficialMessages::class.java, context) {
                state.clear(clearDefaults = true)
                AppToast.show(this, R.string.server_settings_updated)
            }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        /** The colour is not a view, so [StatefulViews] needs a key outside the id space. */
        private const val STATE_ID_COLOR = 90201

        internal const val DEFAULT_COLOR = 0x3498DB
        private const val RGB_MASK = 0xFFFFFF
        private const val OPAQUE = 0xFF000000.toInt()
        private const val HIGHLIGHT_ALPHA = 41

        /** WCAG AA for body text. */
        private const val MIN_CONTRAST = 4.5

        /**
         * Lightens or darkens [color] until it reads against the highlighted row.
         *
         * A user-picked hue is frequently unreadable on its own tint, and the reference solves this
         * by walking the colour towards whichever extreme it is already closer to.
         */
        internal fun accessibleTextColor(color: Int): Int {
            val background = ColorUtils.blendARGB(Color.BLACK, color, HIGHLIGHT_ALPHA / 255f)
            val towards = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE
            var candidate = color
            var step = 0f
            while (step <= 1f) {
                if (ColorUtils.calculateContrast(candidate, background) >= MIN_CONTRAST) return candidate
                step += 0.05f
                candidate = ColorUtils.blendARGB(color, towards, step)
            }
            return towards
        }

        private fun observeModel(guildId: Long): Observable<Guild?> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getUsers().observeMe(),
        ) { guild, permissions, me ->
            guild?.takeIf {
                it.hasFeature(GuildFeature.VERIFIED) && (
                    it.isOwner(me.id) || PermissionUtils.canAndIsElevated(
                        Permission.MANAGE_GUILD,
                        permissions,
                        me.mfaEnabled,
                        it.mfaLevel,
                    )
                    )
            }
        }.computationLatest().distinctUntilChanged()

        fun newInstance(guildId: Long) = WidgetServerSettingsOfficialMessages().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("OFFICIAL_MESSAGES", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsOfficialMessages::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
