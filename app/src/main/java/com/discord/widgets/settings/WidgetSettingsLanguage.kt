package com.discord.widgets.settings

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import rx.functions.Action1

/**
 * Picks the app's language and whether that choice follows the account to other devices.
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsLanguage`. The picker itself is
 * [WidgetSettingsLanguageSelect], a dialog that reports back through the fragment-result API.
 *
 * Changing the language recreates the activity so every already-inflated string is re-resolved.
 */
class WidgetSettingsLanguage : AppFragment(R.layout.widget_settings_language) {

    private val viewModel: SettingsLanguageViewModel by appViewModels { SettingsLanguageViewModel() }

    private lateinit var currentFlag: ImageView
    private lateinit var currentText: TextView
    private lateinit var syncCheck: CheckedSetting

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.language)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarDisplayHomeAsUpEnabled()
        SettingsNavigation.bindBack(this)

        currentFlag = view.findViewById(R.id.settings_language_current_flag)
        currentText = view.findViewById(R.id.settings_language_current_text)
        syncCheck = view.findViewById(R.id.settings_language_sync_check)

        view.findViewById<View>(R.id.settings_language_current).setOnClickListener {
            WidgetSettingsLanguageSelect.show(this)
        }
        WidgetSettingsLanguageSelect.registerForResult(this) { locale ->
            viewModel.selectLocale(locale)
        }

        listOf(R.id.settings_language_header, R.id.settings_language_sync_header)
            .forEach { id -> ViewCompat.setAccessibilityHeading(view.findViewById(id), true) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { configureUI(it) }
        viewModel.observeEvents().ui(this).subscribe { handleEvent(it) }
    }

    private fun configureUI(viewState: SettingsLanguageViewModel.ViewState) {
        if (viewState !is SettingsLanguageViewModel.ViewState.Loaded) return
        currentText.setText(getLocaleResId(viewState.locale))
        currentFlag.setImageResource(getLocaleFlagResId(viewState.locale))
        syncCheck.setOnCheckedListener(null)
        syncCheck.isChecked = viewState.isLocaleSyncEnabled
        syncCheck.setOnCheckedListener(Action1 { viewModel.setLocaleSyncEnabled(it) })
    }

    /**
     * The reference recreates the activity here so the new locale's resources are re-resolved.
     * This project's `AppActivity` already watches `StoreUserSettingsSystem.observeSettings` and
     * restarts itself when the applied locale, theme or font scale changes, so doing it here too
     * would restart twice.
     */
    private fun handleEvent(event: SettingsLanguageViewModel.Event) = when (event) {
        is SettingsLanguageViewModel.Event.LocaleUpdated ->
            Toast.makeText(requireContext(), R.string.language_updated, Toast.LENGTH_SHORT).show()
    }

    companion object {

        fun launch(context: Context) {
            SettingsNavigation.launch(context, WidgetSettingsLanguage::class.java)
        }

        /**
         * The language's own name, written in that language.
         *
         * These are deliberately not translatable resources — a Japanese speaker looking for their
         * language in a Turkish UI needs to see "日本語", not "Japonca".
         */
        fun getAsStringInLocale(locale: String?): String = when (locale) {
            "bg" -> "български"
            "cs" -> "Čeština"
            "da" -> "Dansk"
            "de" -> "Deutsch"
            "el" -> "Ελληνικά"
            "fi" -> "Suomi"
            "fr" -> "Français"
            "hi" -> "हिंदी"
            "hr" -> "Hrvatski"
            "hu" -> "Magyar"
            "it" -> "Italiano"
            "ja" -> "日本語"
            "ko" -> "한국어"
            "lt" -> "Lietuviškai"
            "nl" -> "Nederlands"
            "no" -> "Norsk"
            "pl" -> "Polski"
            "ro" -> "Română"
            "ru" -> "Русский"
            "th" -> "ไทย"
            "tr" -> "Türkçe"
            "uk" -> "Українська"
            "vi" -> "Tiếng Việt"
            "en-GB" -> "English, UK"
            "es-ES" -> "Español"
            "pt-BR" -> "Português do Brasil"
            "sv-SE" -> "Svenska"
            "zh-CN" -> "中文"
            "zh-TW" -> "繁體中文"
            else -> "English, USA"
        }

        /** The language's name in the *current* UI language. */
        @StringRes
        fun getLocaleResId(locale: String?): Int = when (locale) {
            "bg" -> R.string.bg
            "cs" -> R.string.cs
            "da" -> R.string.da
            "de" -> R.string.de
            "el" -> R.string.el
            "fi" -> R.string.fi
            "fr" -> R.string.fr
            "hi" -> R.string.hi
            "hr" -> R.string.hr
            "hu" -> R.string.hu
            "it" -> R.string.it
            "ja" -> R.string.ja
            "ko" -> R.string.ko
            "lt" -> R.string.lt
            "nl" -> R.string.nl
            "no" -> R.string.no
            "pl" -> R.string.pl
            "ro" -> R.string.ro
            "ru" -> R.string.ru
            "th" -> R.string.th
            "tr" -> R.string.tr
            "uk" -> R.string.uk
            "vi" -> R.string.vi
            "en-GB" -> R.string.en_gb
            "es-ES" -> R.string.es_es
            "pt-BR" -> R.string.pt_br
            "sv-SE" -> R.string.sv_se
            "zh-CN" -> R.string.zh_cn
            "zh-TW" -> R.string.zh_tw
            else -> R.string.en_us
        }

        @DrawableRes
        fun getLocaleFlagResId(locale: String?): Int = when (locale) {
            "bg" -> R.drawable.icon_flag_bg
            "cs" -> R.drawable.icon_flag_cs
            "da" -> R.drawable.icon_flag_da
            "de" -> R.drawable.icon_flag_de
            "el" -> R.drawable.icon_flag_el
            "fi" -> R.drawable.icon_flag_fi
            "fr" -> R.drawable.icon_flag_fr
            "hi" -> R.drawable.icon_flag_hi
            "hr" -> R.drawable.icon_flag_hr
            "hu" -> R.drawable.icon_flag_hu
            "it" -> R.drawable.icon_flag_it
            "ja" -> R.drawable.icon_flag_ja
            "ko" -> R.drawable.icon_flag_ko
            "lt" -> R.drawable.icon_flag_lt
            "nl" -> R.drawable.icon_flag_nl
            "no" -> R.drawable.icon_flag_no
            "pl" -> R.drawable.icon_flag_pl
            "ro" -> R.drawable.icon_flag_ro
            "ru" -> R.drawable.icon_flag_ru
            "th" -> R.drawable.icon_flag_th
            "tr" -> R.drawable.icon_flag_tr
            "uk" -> R.drawable.icon_flag_uk
            "vi" -> R.drawable.icon_flag_vi
            "en-GB" -> R.drawable.icon_flag_en_gb
            "es-ES" -> R.drawable.icon_flag_es_es
            "pt-BR" -> R.drawable.icon_flag_pt_br
            "sv-SE" -> R.drawable.icon_flag_sv_se
            "zh-CN" -> R.drawable.icon_flag_zh_cn
            "zh-TW" -> R.drawable.icon_flag_zh_tw
            else -> R.drawable.icon_flag_en_us
        }
    }
}
