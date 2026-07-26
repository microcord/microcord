package com.discord.widgets.phone

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.phone.PhoneCountryCode
import com.discord.utilities.phone.PhoneUtils

/** One country row: localised country name on the left, dialling code on the right. */
class PhoneCountryCodeAdapterItem(
    private val adapter: PhoneCountryCodeAdapter,
    itemView: View,
) : RecyclerView.ViewHolder(itemView) {

    private val container: View = itemView.findViewById(R.id.phone_country_code_item)
    private val name: TextView = itemView.findViewById(R.id.phone_country_code_name)
    private val code: TextView = itemView.findViewById(R.id.phone_country_code_code)

    fun bind(data: PhoneCountryCode) {
        name.text = PhoneUtils.getTranslatedStringForCountry(data, itemView.context)
        code.text = data.phoneCountryCode
        container.setOnClickListener {
            adapter.onCountryCodeSelectedListener?.onCountryCodeSelected(data)
        }
    }
}
