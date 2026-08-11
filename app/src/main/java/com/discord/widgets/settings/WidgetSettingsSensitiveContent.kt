package com.discord.widgets.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppFragment
import com.discord.stores.UserSettings
import com.discord.stores.UserSettingsProtoSetting
import com.discord.utilities.media.ContentSurface
import com.discord.utilities.media.Redaction
import com.discord.utilities.media.SensitiveContentSetting
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings
import rx.Observable

/**
 * How media the server flagged is shown, per category and per surface.
 *
 * Server channels offer no Block: the reference only lets an account hide flagged media outright in
 * DMs.
 */
class WidgetSettingsSensitiveContent : AppFragment(R.layout.widget_settings_sensitive_content) {

    private enum class Category(
        val headerId: Int,
        val setting: UserSettingsProtoSetting<PreloadedUserSettings, PreloadedUserSettings.Builder, SensitiveContentSetting>,
    ) {
        EXPLICIT(R.id.settings_sensitive_explicit_header, UserSettings.explicitContent),
        GORE(R.id.settings_sensitive_gore_header, UserSettings.goreContent),
        SELF_HARM(R.id.settings_sensitive_self_harm_header, UserSettings.selfHarmContent),
    }

    private data class Row(
        val category: Category,
        val surface: ContentSurface,
        val containerId: Int,
        val valueId: Int,
        val labelRes: Int,
    )

    private val rows = listOf(
        Row(Category.EXPLICIT, ContentSurface.FRIEND_DM, R.id.settings_sensitive_explicit_friend_dm, R.id.settings_sensitive_explicit_friend_dm_value, R.string.sensitive_content_friend_dm),
        Row(Category.EXPLICIT, ContentSurface.NON_FRIEND_DM, R.id.settings_sensitive_explicit_non_friend_dm, R.id.settings_sensitive_explicit_non_friend_dm_value, R.string.sensitive_content_non_friend_dm),
        Row(Category.EXPLICIT, ContentSurface.GUILD, R.id.settings_sensitive_explicit_guilds, R.id.settings_sensitive_explicit_guilds_value, R.string.sensitive_content_guilds),
        Row(Category.GORE, ContentSurface.FRIEND_DM, R.id.settings_sensitive_gore_friend_dm, R.id.settings_sensitive_gore_friend_dm_value, R.string.sensitive_content_friend_dm),
        Row(Category.GORE, ContentSurface.NON_FRIEND_DM, R.id.settings_sensitive_gore_non_friend_dm, R.id.settings_sensitive_gore_non_friend_dm_value, R.string.sensitive_content_non_friend_dm),
        Row(Category.GORE, ContentSurface.GUILD, R.id.settings_sensitive_gore_guilds, R.id.settings_sensitive_gore_guilds_value, R.string.sensitive_content_guilds),
        Row(Category.SELF_HARM, ContentSurface.FRIEND_DM, R.id.settings_sensitive_self_harm_friend_dm, R.id.settings_sensitive_self_harm_friend_dm_value, R.string.sensitive_content_friend_dm),
        Row(Category.SELF_HARM, ContentSurface.NON_FRIEND_DM, R.id.settings_sensitive_self_harm_non_friend_dm, R.id.settings_sensitive_self_harm_non_friend_dm_value, R.string.sensitive_content_non_friend_dm),
        Row(Category.SELF_HARM, ContentSurface.GUILD, R.id.settings_sensitive_self_harm_guilds, R.id.settings_sensitive_self_harm_guilds_value, R.string.sensitive_content_guilds),
    )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.sensitive_content_filters)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        Category.entries.forEach { ViewCompat.setAccessibilityHeading(view.findViewById<TextView>(it.headerId), true) }

        rows.forEach { row ->
            view.findViewById<View>(row.containerId).setOnClickListener {
                OptionSheet.show(this, row.category.ordinal, row.surface.ordinal, row.labelRes)
            }
        }

        Observable.combineLatest(
            UserSettings.explicitContent.observe(),
            UserSettings.goreContent.observe(),
            UserSettings.selfHarmContent.observe(),
        ) { explicit, gore, selfHarm ->
            mapOf(Category.EXPLICIT to explicit, Category.GORE to gore, Category.SELF_HARM to selfHarm)
        }
            .distinctUntilChanged()
            .ui(this)
            .subscribe { values -> rows.forEach { row -> bind(view, row, values.getValue(row.category)) } }
    }

    private fun bind(view: View, row: Row, setting: SensitiveContentSetting) {
        view.findViewById<TextView>(row.valueId).setText(label(setting.forSurface(row.surface)))
    }

    class OptionSheet : AppBottomSheet() {
        override fun getContentViewResId() = R.layout.widget_settings_sensitive_content_option

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            setBottomSheetCollapsedStateDisabled()
            val category = Category.entries[requireArguments().getInt(ARG_CATEGORY)]
            val surface = ContentSurface.entries[requireArguments().getInt(ARG_SURFACE)]
            view.findViewById<TextView>(R.id.sensitive_content_sheet_header)
                .setText(requireArguments().getInt(ARG_ROW_TITLE))

            val options = buildMap {
                put(Redaction.SHOW, view.findViewById<CheckedSetting>(R.id.sensitive_content_sheet_show))
                put(Redaction.BLUR, view.findViewById<CheckedSetting>(R.id.sensitive_content_sheet_blur))
                val block = view.findViewById<CheckedSetting>(R.id.sensitive_content_sheet_block)
                if (surface == ContentSurface.GUILD) block.visibility = View.GONE else put(Redaction.BLOCK, block)
            }
            val radios = RadioManager(options.values.toList())
            val current = category.setting.get().forSurface(surface)
            options.forEach { (redaction, radio) ->
                if (redaction == current) radios.setChecked(radio)
                radio.setContainerClickListener {
                    category.setting.update { it.withSurface(surface, redaction) }
                    dismiss()
                }
            }
        }

        companion object {
            private const val ARG_CATEGORY = "category"
            private const val ARG_SURFACE = "surface"
            private const val ARG_ROW_TITLE = "row_title"

            fun show(fragment: AppFragment, category: Int, surface: Int, rowRes: Int) {
                OptionSheet().apply {
                    arguments = Bundle().apply {
                        putInt(ARG_CATEGORY, category)
                        putInt(ARG_SURFACE, surface)
                        putInt(ARG_ROW_TITLE, rowRes)
                    }
                }.show(fragment.childFragmentManager, OptionSheet::class.java.name)
            }
        }
    }

    private companion object {
        fun label(redaction: Redaction) = when (redaction) {
            Redaction.SHOW -> R.string.sensitive_content_show
            Redaction.BLUR -> R.string.sensitive_content_blur
            Redaction.BLOCK -> R.string.sensitive_content_block
        }
    }
}
