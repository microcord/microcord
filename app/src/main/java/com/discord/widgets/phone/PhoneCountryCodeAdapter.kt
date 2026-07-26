package com.discord.widgets.phone

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.phone.PhoneCountryCode

/**
 * The country list inside [WidgetPhoneCountryCodeBottomSheet].
 *
 * Deliberately a plain [RecyclerView.Adapter] rather than an `MGRecyclerAdapterSimple`, matching the
 * reference: the list is rebuilt wholesale on every keystroke of the search field and has no stable
 * row identity to diff against.
 */
class PhoneCountryCodeAdapter : RecyclerView.Adapter<PhoneCountryCodeAdapterItem>() {

    private var items: List<PhoneCountryCode> = emptyList()

    internal var onCountryCodeSelectedListener: OnCountryCodeSelectedListener? = null
        private set

    /** Replaces the visible countries and the row-tap callback in one step. */
    fun configure(
        countryCodes: List<PhoneCountryCode>,
        onCountryCodeSelectedListener: OnCountryCodeSelectedListener,
    ) {
        items = countryCodes
        this.onCountryCodeSelectedListener = onCountryCodeSelectedListener
        // The reference does not notify here; the sheet always assigns the adapter to the recycler
        // immediately afterwards, which performs the initial bind. Notifying keeps the list correct
        // when `configure` is called again for a later search term on an already-attached adapter.
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PhoneCountryCodeAdapterItem, position: Int) {
        holder.bind(items[position])
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhoneCountryCodeAdapterItem =
        PhoneCountryCodeAdapterItem(
            this,
            LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_phone_country_code_list_item, parent, false),
        )
}
