package com.discord.widgets.phone

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.models.phone.PhoneCountryCode
import com.discord.utilities.phone.PhoneUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.TimeUnit
import rx.subjects.BehaviorSubject

/**
 * The country-code picker every phone input opens.
 *
 * Without this screen `PhoneOrEmailInputView`'s country field has nowhere to go and phone entry is
 * pinned to the default `+1`, which is what made phone auth unusable outside North America.
 *
 * The list is filtered as the user types, matching either the country's English name or its
 * localised name, and the search is debounced by 200 ms so a fast typist does not rebuild a
 * 250-row list on every keystroke.
 *
 * Selection normally flows back through `StorePhone` (the reference's only channel). Because that
 * store is not yet wired into `StoreStream` — see [PhoneCountryCodes] — [show] also accepts an
 * optional listener so a caller can receive the choice directly. That parameter is an addition to
 * the reference API, not a reconstruction of it; it becomes redundant the moment the store is
 * published and can then be deleted.
 */
class WidgetPhoneCountryCodeBottomSheet : AppBottomSheet() {

    private val adapter = PhoneCountryCodeAdapter()
    private val nameFilterSubject: BehaviorSubject<String> = BehaviorSubject.create()

    private lateinit var searchInput: TextInputLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView

    /**
     * Set by [show] on the instance it creates. Not part of the saved state: a recreated sheet has
     * no listener, which is why the store remains the authoritative channel.
     */
    private var onCountryCodeSelected: OnCountryCodeSelectedListener? = null

    override fun getContentViewResId(): Int = R.layout.widget_phone_country_code_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchInput = view.findViewById(R.id.phone_country_code_search)
        recycler = view.findViewById(R.id.phone_country_code_recycler)
        // The recycler's own style supplies the LinearLayoutManager; only the adapter is set here.
        recycler.setHasFixedSize(false)
        recycler.adapter = adapter
        emptyView = buildEmptyView(view)
    }

    override fun onResume() {
        super.onResume()
        searchInput.addBindedTextWatcher(this) { editable ->
            nameFilterSubject.onNext(editable.toString())
        }
        nameFilterSubject
            .debounce(SEARCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .ui(this)
            .appSubscribe(WidgetPhoneCountryCodeBottomSheet::class.java) { filter -> configureUI(filter) }
        configureUI("")
    }

    private fun configureUI(filter: String) {
        val allCountryCodes = PhoneCountryCodes.list(requireContext())
        val matches = allCountryCodes.filter { countryCode -> matches(countryCode, filter) }

        adapter.configure(matches) { selected ->
            // `select` returns false while `StorePhone` is unpublished; the sheet then reports the
            // choice to whoever opened it so the picker is still functional.
            if (!PhoneCountryCodes.select(selected)) {
                onCountryCodeSelected?.onCountryCodeSelected(selected)
            }
            dismiss()
        }

        val hasCountries = matches.isNotEmpty()
        recycler.visibility = if (hasCountries) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasCountries) View.GONE else View.VISIBLE
        // "no countries at all" means the asset could not be read, which is a different failure
        // from "your search matched nothing".
        emptyView.setText(
            if (allCountryCodes.isEmpty()) R.string.network_error_unknown else R.string.search_no_results,
        )
    }

    private fun matches(countryCode: PhoneCountryCode, filter: String): Boolean =
        countryCode.name.contains(filter, ignoreCase = true) ||
            PhoneUtils.getTranslatedStringForCountry(countryCode, requireContext())
                .contains(filter, ignoreCase = true)

    /**
     * The recovered layout has no empty/error slot, so one is added at runtime rather than editing
     * the recovered XML. The reference simply shows a blank list in both cases.
     */
    private fun buildEmptyView(root: View): TextView {
        val container = root as android.view.ViewGroup
        return TextView(root.context).apply {
            visibility = View.GONE
            val padding = (EMPTY_STATE_PADDING_DP * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            container.addView(this, container.indexOfChild(recycler) + 1)
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 200L
        private const val EMPTY_STATE_PADDING_DP = 16

        /**
         * @param onCountryCodeSelected invoked only while `StorePhone` is unpublished; see the class
         *   documentation. Reference callers pass nothing and read the store instead.
         */
        @JvmStatic
        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            onCountryCodeSelected: OnCountryCodeSelectedListener? = null,
        ) {
            WidgetPhoneCountryCodeBottomSheet()
                .apply { this.onCountryCodeSelected = onCountryCodeSelected }
                .show(fragmentManager, WidgetPhoneCountryCodeBottomSheet::class.java.name)
        }
    }
}
