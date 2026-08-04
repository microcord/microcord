package com.discord.widgets.settings.account

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppLog
import com.discord.databinding.WidgetDisableDeleteAccountDialogBinding
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.getTextOrEmpty
import rx.Observable

class WidgetDisableDeleteAccountDialog : AppDialog(R.layout.widget_disable_delete_account_dialog) {

    private var _binding: WidgetDisableDeleteAccountDialogBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetDisableDeleteAccountDialogBinding.bind(view)
        isCancelable = false

        val mode = arguments?.getInt(EXTRA_MODE, INVALID_MODE)
            ?.takeIf { it in Mode.entries.indices }
            ?.let(Mode.entries::get)
        if (mode == null) {
            AppLog.e("Disable/Delete Dialog shown with null mode")
            dismiss()
            return
        }

        binding.disableDeleteConfirm.setIsLoading(false)
        binding.disableDeleteHeader.setText(mode.headerStringId)
        binding.disableDeleteBody.setText(mode.bodyStringId)
        binding.disableDeleteConfirm.setText(getString(mode.confirmStringId))
        binding.disableDeleteCancel.setOnClickListener { dismiss() }
        binding.disableDeletePasswordWrap.addBindedTextWatcher(this) {
            binding.disableDeletePasswordWrap.error = null
        }
        binding.disableDeleteConfirm.setOnClickListener { submit(mode) }
    }

    private fun submit(mode: Mode) {
        binding.disableDeleteConfirm.setIsLoading(true)
        val body = RestAPIParams.DisableAccount(binding.disableDeletePasswordWrap.getTextOrEmpty())
        mode.request(RestAPI.api, body)
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
        binding.disableDeleteConfirm.setIsLoading(false)
        if (error.code == INVALID_PASSWORD_CODE) {
            binding.disableDeletePasswordWrap.error = error.message
            error.showErrorToasts = false
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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

        internal fun request(
            api: RestAPIInterface,
            body: RestAPIParams.DisableAccount,
        ): Observable<Void> = when (this) {
            DISABLE -> api.disableAccount(body)
            DELETE -> api.deleteAccount(body)
        }
    }

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        private const val INVALID_MODE = -1
        private const val INVALID_PASSWORD_CODE = 50018

        @JvmStatic
        fun show(fragmentManager: FragmentManager, mode: Mode) {
            WidgetDisableDeleteAccountDialog().apply {
                arguments = Bundle().apply { putInt(EXTRA_MODE, mode.ordinal) }
            }.show(fragmentManager, WidgetDisableDeleteAccountDialog::class.java.name)
        }
    }
}
