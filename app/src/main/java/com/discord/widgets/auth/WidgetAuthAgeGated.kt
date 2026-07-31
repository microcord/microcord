package com.discord.widgets.auth

import android.view.View
import android.widget.TextView
import com.discord.R
import com.google.android.material.button.MaterialButton

class WidgetAuthAgeGated : AuthFragment(R.layout.widget_auth_age_gated, AuthRoute.AGE_GATED) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.auth_register_underage_button)
            .setOnClickListener { actions.close() }
    }

    override fun render(state: AuthUiState) {
        state.underageMessage?.let {
            requireView().findViewById<TextView>(R.id.auth_register_underage_description).text = it
        }
    }
}
