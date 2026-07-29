package com.discord.widgets.settings.account

import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.auth.mfa.BackupCode
import com.discord.api.auth.mfa.GetBackupCodesSendVerificationKeyRequestBody
import com.discord.api.auth.mfa.GetBackupCodesVerificationRequestBody
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.SettingsStrings

class WidgetSettingsAccountBackupCodes : AppFragment(R.layout.widget_settings_account_backup_codes) {
    private lateinit var adapter: Adapter
    private lateinit var dimmer: DimmerView
    private var nonce = ""
    private var password = ""
    private var regenerateNonce = ""
    private var verificationKey = ""

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.two_fa_backup_codes_label)
        setActionBarDisplayHomeAsUpEnabled()
        view.findViewById<TextView>(R.id.settings_backup_codes_info).apply {
            text = SettingsStrings.render(context, R.string.two_fa_backup_codes_body)
            movementMethod = LinkMovementMethod.getInstance()
        }
        view.findViewById<RecyclerView>(R.id.settings_backup_codes_rv).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = Adapter().also { this@WidgetSettingsAccountBackupCodes.adapter = it }
        }
        view.findViewById<View>(R.id.settings_backup_codes_generate).setOnClickListener {
            getBackupCodes(regenerate = true)
        }
        dimmer = view.findViewById(R.id.dimmer_view)
        dimmer.setDimmed(true, true)
        showPasswordModal()
    }

    private fun sendVerificationKey() {
        RestAPI.api.getBackupCodesSendVerificationKey(GetBackupCodesSendVerificationKeyRequestBody(password))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetSettingsAccountBackupCodes::class.java,
                context = context,
                errorHandler = { showPasswordModal() },
            ) { response ->
                nonce = response.nonce
                regenerateNonce = response.regenerateNonce
                showVerificationKeyModal()
            }
    }

    private fun getBackupCodes(regenerate: Boolean = false) {
        RestAPI.api.getBackupCodesVerification(
            GetBackupCodesVerificationRequestBody(
                verificationKey,
                if (regenerate) regenerateNonce else nonce,
                regenerate,
            ),
        )
            .restSubscribeOn()
            .withDimmer(dimmer, 100L)
            .ui(this)
            .appSubscribe(WidgetSettingsAccountBackupCodes::class.java, context) { response ->
                adapter.setData(
                    buildRows(
                        response.backupCodes,
                        getString(R.string.user_settings_available_codes),
                        getString(R.string.user_settings_used_backup_codes),
                    ),
                )
            }
    }

    private fun showPasswordModal() {
        val activity = appActivity ?: return
        WidgetNoticeDialog.showInputModal(
            activity = activity,
            headerText = getString(R.string.two_fa_backup_codes_label),
            bodyText = getString(R.string.user_settings_enter_password_view_codes),
            hintText = getString(R.string.form_label_password),
            onOKClicked = { _, value ->
                password = value
                sendVerificationKey()
            },
            onCancelClicked = { activity.onBackPressedDispatcher.onBackPressed() },
            cancelable = false,
            isPassword = true,
            okText = getString(R.string.two_fa_backup_codes_next_action),
        )
    }

    private fun showVerificationKeyModal() {
        val activity = appActivity ?: return
        WidgetNoticeDialog.showInputModal(
            activity = activity,
            headerText = getString(R.string.two_fa_confirm_view_backup_codes_label),
            bodyText = getString(R.string.two_fa_confirm_view_backup_codes_body),
            hintText = getString(R.string.two_fa_confirm_view_backup_codes_input_label),
            onOKClicked = { _, value ->
                verificationKey = value
                getBackupCodes()
            },
            onCancelClicked = { activity.onBackPressedDispatcher.onBackPressed() },
            cancelable = false,
            shouldHideKeyboard = true,
        )
    }

    sealed interface BackupCodeRow {
        val key: String
        val type: Int
    }

    data class BackupCodeItem(val backupCode: BackupCode) : BackupCodeRow {
        override val key = backupCode.code
        override val type = TYPE_CODE
        override fun toString() = "BackupCodeItem(backupCode=<redacted>, consumed=${backupCode.consumed})"
    }

    data class BackupCodeItemHeader(val headerText: CharSequence) : BackupCodeRow {
        override val key = headerText.toString()
        override val type = TYPE_HEADER
    }

    private class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows = emptyList<BackupCodeRow>()

        fun setData(rows: List<BackupCodeRow>) {
            this.rows = rows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_HEADER) {
                R.layout.widget_settings_item_backup_code_header
            } else {
                R.layout.widget_settings_item_backup_code
            }
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false) as TextView
            return if (viewType == TYPE_HEADER) HeaderHolder(view) else CodeHolder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> holder.bind(rows[position] as BackupCodeItemHeader)
                is CodeHolder -> holder.bind(rows[position] as BackupCodeItem)
            }
        }

        private class HeaderHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
            fun bind(item: BackupCodeItemHeader) {
                text.text = item.headerText
            }
        }

        private class CodeHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
            fun bind(item: BackupCodeItem) {
                val code = item.backupCode.code
                text.text = text.context.getString(R.string.backup_codes_dash, code.substring(0, 4), code.substring(4))
                if (item.backupCode.consumed) {
                    text.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_check_brand_24dp, 0)
                    text.setOnClickListener(null)
                } else {
                    text.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                    text.setOnClickListener { AppToast.copyToClipboard(it.context, code) }
                }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CODE = 1

        internal fun buildRows(
            codes: List<BackupCode>,
            availableHeader: CharSequence,
            usedHeader: CharSequence,
        ): List<BackupCodeRow> = buildList {
            val available = codes.filterNot(BackupCode::consumed)
            val used = codes.filter(BackupCode::consumed)
            if (available.isNotEmpty()) {
                add(BackupCodeItemHeader(availableHeader))
                available.mapTo(this, ::BackupCodeItem)
            }
            if (used.isNotEmpty()) {
                add(BackupCodeItemHeader(usedHeader))
                used.mapTo(this, ::BackupCodeItem)
            }
        }

        @JvmStatic
        fun launch(context: Context) =
            AppScreenRouter.launch(context, WidgetSettingsAccountBackupCodes::class.java, Intent())
    }
}
