package com.discord.widgets.settings.account.mfa

import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.views.CodeVerificationView

class WidgetEnableMFAInput : AppFragment(R.layout.widget_settings_enable_mfa_input) {
    private val viewModel by appActivityViewModels { WidgetEnableMFAViewModel() }
    private var codeInput: CodeVerificationView? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        codeInput = view.findViewById<CodeVerificationView>(R.id.user_settings_mfa_enable_input_code).also { input ->
            input.onCodeEntered = { viewModel.enableMFA(view.context, it) }
        }
    }

    override fun onDestroyView() {
        codeInput = null
        super.onDestroyView()
    }

    override fun setMenuVisibility(menuVisible: Boolean) {
        super.setMenuVisibility(menuVisible)
        if (!menuVisible) return
        val context = context ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).text ?: return
        if (text.length == 6 && TextUtils.isDigitsOnly(text)) codeInput?.setCode(text)
    }
}
