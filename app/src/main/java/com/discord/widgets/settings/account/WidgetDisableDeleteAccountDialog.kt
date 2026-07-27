package com.discord.widgets.settings.account

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppLog
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.views.LoadingButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

class WidgetDisableDeleteAccountDialog : AppDialog(R.layout.widget_disable_delete_account_dialog) {

    private lateinit var password: TextInputLayout
    private lateinit var mfaCode: TextInputLayout
    private lateinit var confirm: LoadingButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        isCancelable = false
        password = view.findViewById(R.id.disable_delete_password_wrap)
        mfaCode = view.findViewById(R.id.disable_delete_code_wrap)
        confirm = view.findViewById(R.id.disable_delete_confirm)

        val mode = arguments?.getInt(EXTRA_MODE, INVALID_MODE)
            ?.takeIf { it in Mode.entries.indices }
            ?.let(Mode.entries::get)
        if (mode == null) {
            AppLog.e("Disable/Delete Dialog shown with null mode")
            dismiss()
            return
        }

        confirm.setIsLoading(false)
        view.findViewById<TextView>(R.id.disable_delete_header).setText(mode.headerStringId)
        view.findViewById<TextView>(R.id.disable_delete_body).setText(mode.bodyStringId)
        confirm.setText(getString(mode.confirmStringId))
        view.findViewById<View>(R.id.disable_delete_cancel).setOnClickListener { dismiss() }
        mfaCode.addBindedTextWatcher(this) { mfaCode.error = null }
        password.addBindedTextWatcher(this) { password.error = null }
        confirm.setOnClickListener { submit(mode) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUsers().observeMe()
            .ui(this)
            .appSubscribe(WidgetDisableDeleteAccountDialog::class.java) { configureUI(it) }
    }

    private fun configureUI(me: MeUser) {
        mfaCode.visibility = if (me.mfaEnabled) View.VISIBLE else View.GONE
    }

    private fun submit(mode: Mode) {
        confirm.setIsLoading(true)
        val body = RestAPIParams.DisableAccount(
            password = password.getTextOrEmpty(),
            code = mfaCode.getTextOrEmpty().takeIf { mfaCode.visibility == View.VISIBLE },
        )
        mode.request(body)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetDisableDeleteAccountDialog::class.java,
                context = context,
                errorHandler = ::handleError,
            ) {
                StoreStream.getAuthentication().logout()
            }
    }

    private fun handleError(error: RestApiError) {
        confirm.setIsLoading(false)
        when (error.code) {
            INVALID_PASSWORD_CODE -> {
                password.error = error.message
                error.showErrorToasts = false
            }
            INVALID_MFA_CODE -> {
                mfaCode.error = error.message
                error.showErrorToasts = false
            }
        }
    }

    enum class Mode(
        val headerStringId: Int,
        val bodyStringId: Int,
        val confirmStringId: Int,
    ) {
        // Ordinals are API behavior: bytecode assigns DISABLE(0), DELETE(1).
        DISABLE(R.string.disable_account, R.string.disable_account_body, R.string.disable),
        DELETE(R.string.delete_account, R.string.delete_account_body, R.string.delete),
        ;

        internal fun request(body: RestAPIParams.DisableAccount): Observable<Void> = when (this) {
            DISABLE -> RestAPI.api.disableAccount(body)
            DELETE -> RestAPI.api.deleteAccount(body)
        }
    }

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        private const val INVALID_MODE = -1
        private const val INVALID_PASSWORD_CODE = 50018
        private const val INVALID_MFA_CODE = 60008

        @JvmStatic
        fun show(fragmentManager: FragmentManager, mode: Mode) {
            WidgetDisableDeleteAccountDialog().apply {
                arguments = Bundle().apply { putInt(EXTRA_MODE, mode.ordinal) }
            }.show(fragmentManager, WidgetDisableDeleteAccountDialog::class.java.name)
        }
    }
}
