package com.discord.widgets.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppDialog
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * The language picker opened from [WidgetSettingsLanguage].
 *
 * Reference: `com.discord.widgets.settings.WidgetSettingsLanguageSelect`. The dialog does not know
 * who opened it: it reports the chosen locale through the fragment-result API, which the caller
 * subscribes to with [registerForResult].
 *
 * Each row shows the language twice — once in the current UI language, once in its own — so the
 * list stays navigable no matter which language the app is currently in.
 */
class WidgetSettingsLanguageSelect : AppDialog(R.layout.widget_settings_language_select) {

    private var adapter: Adapter? = null

    override fun onResume() {
        super.onResume()
        val recycler = requireView().findViewById<RecyclerView>(R.id.settings_language_select_list)
        adapter = MGRecyclerAdapter
            .configure<Item, Adapter>(Adapter(recycler, this))
            .also { it.setData(LOCALES.map(::Item)) }
    }

    private fun onLocaleSelected(locale: String) {
        setFragmentResult(REQUEST_KEY_USER_LOCALE, Bundle().apply { putString(RESULT_KEY_LOCALE, locale) })
        dismiss()
    }

    /** One selectable language. */
    data class Item(val locale: String) : MGRecyclerDataPayload {
        override val type: Int get() = 0
        override val key: String get() = locale
    }

    private class Adapter(
        recycler: RecyclerView,
        val dialog: WidgetSettingsLanguageSelect,
    ) : MGRecyclerAdapterSimple<Item>(recycler) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AdapterItemLocale =
            AdapterItemLocale(this)

        class AdapterItemLocale(
            adapter: Adapter,
        ) : MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_settings_language_select_item, adapter) {

            private val flag: ImageView = itemView.findViewById(R.id.flag_icon)
            private val name: TextView = itemView.findViewById(R.id.settings_language_select_item_name)
            private val nameLocalized: TextView =
                itemView.findViewById(R.id.settings_language_select_item_name_localized)

            override fun onConfigure(position: Int, data: Item) {
                super.onConfigure(position, data)
                itemView.setOnClickListener { adapter.dialog.onLocaleSelected(data.locale) }
                name.setText(WidgetSettingsLanguage.getLocaleResId(data.locale))
                nameLocalized.text = WidgetSettingsLanguage.getAsStringInLocale(data.locale)
                flag.setImageResource(WidgetSettingsLanguage.getLocaleFlagResId(data.locale))
            }
        }
    }

    companion object {
        private const val REQUEST_KEY_USER_LOCALE = "REQUEST_KEY_USER_LOCALE"
        private const val RESULT_KEY_LOCALE = "INTENT_EXTRA_LOCALE"

        /**
         * The languages Discord ships, in the reference's order — grouped by script and region
         * rather than sorted, so the Latin-script European languages stay together.
         */
        private val LOCALES = listOf(
            "da", "de", "en-GB", "en-US", "es-ES", "fr", "hr", "it", "lt", "hu",
            "nl", "no", "pl", "pt-BR", "ro", "fi", "sv-SE", "vi", "tr", "cs",
            "el", "bg", "ru", "uk", "ja", "zh-TW", "th", "zh-CN", "ko", "hi",
        )

        fun registerForResult(fragment: Fragment, onLocaleSelected: (String) -> Unit) {
            fragment.setFragmentResultListener(REQUEST_KEY_USER_LOCALE) { _, bundle ->
                bundle.getString(RESULT_KEY_LOCALE)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(onLocaleSelected)
            }
        }

        fun show(fragment: Fragment) {
            WidgetSettingsLanguageSelect()
                .apply { arguments = Bundle() }
                .show(fragment.parentFragmentManager, WidgetSettingsLanguageSelect::class.java.name)
        }
    }
}
