package com.discord.widgets.servers

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.i18nRender
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

class WidgetServerSettingsTransferOwnership : AppDialog(R.layout.widget_guild_transfer_ownership) {
    private lateinit var mfaWrap: LinearLayout
    private lateinit var mfaCodeInput: TextInputLayout
    private lateinit var acknowledge: CheckedSetting
    private lateinit var confirm: MaterialButton
    private lateinit var cancel: MaterialButton
    private var model: Model? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        mfaWrap = view.findViewById(R.id.guild_transfer_ownership_mfa_wrap)
        mfaCodeInput = view.findViewById(R.id.guild_transfer_ownership_mfa_code)
        acknowledge = view.findViewById(R.id.guild_transfer_ownership_acknowledge_check)
        confirm = view.findViewById(R.id.guild_transfer_ownership_confirm)
        cancel = view.findViewById(R.id.guild_transfer_ownership_cancel)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        observeModel(
            argumentsOrDefault.getLong(ARG_GUILD_ID, -1L),
            argumentsOrDefault.getLong(ARG_USER_ID, -1L),
        )
            .bindToComponentLifecycle(this)
            .ui(this)
            .appSubscribe(WidgetServerSettingsTransferOwnership::class.java, onNext = ::configureUI)
    }

    private fun configureUI(model: Model?) {
        if (model == null) {
            dismiss()
            return
        }
        this.model = model
        mfaWrap.visibility = if (model.isMfaEnabled) View.VISIBLE else View.GONE
        acknowledge.setText(
            requireContext().i18nRender(
                R.string.transfer_ownership_acknowledge,
                "username" to model.user.username,
            ),
        )
        confirm.isEnabled = acknowledge.isChecked
        acknowledge.setOnCheckedListener { confirm.isEnabled = it }
        cancel.setOnClickListener { dismiss() }
        confirm.setOnClickListener { transfer(model) }
    }

    private fun transfer(model: Model) {
        val api = RestAPI.apiOrNull ?: return
        api.transferGuildOwnership(
            model.guild.id,
            RestAPIParams.TransferGuildOwnership(model.user.id, mfaCode),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsTransferOwnership::class.java, context) { dismiss() }
    }

    private val mfaCode: String?
        get() = mfaCodeInput.editText?.text?.toString()?.takeIf { model?.isMfaEnabled == true && it.isNotEmpty() }

    internal data class Model(
        val user: User,
        val guild: Guild,
        val isMfaEnabled: Boolean,
        val isGuildOwner: Boolean,
    )

    companion object {
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"
        private const val ARG_USER_ID = "ARG_USER_ID"
        private val TAG = WidgetServerSettingsTransferOwnership::class.java.name

        fun create(guildId: Long, userId: Long, fragmentManager: FragmentManager) {
            WidgetServerSettingsTransferOwnership().apply {
                arguments = Bundle().apply {
                    putLong(ARG_GUILD_ID, guildId)
                    putLong(ARG_USER_ID, userId)
                }
            }.show(fragmentManager, TAG)
        }

        internal fun observeModel(guildId: Long, userId: Long): Observable<Model?> =
            Observable.combineLatest(
                StoreStream.getGuilds().observeGuild(guildId),
                StoreStream.getUsers().observeUser(userId),
                StoreStream.getUsers().observeMe(),
            ) { guild, user, me -> createModel(guild, user, me) }
                .distinctUntilChanged()
                .computationLatest()

        internal fun createModel(guild: Guild?, user: User?, me: MeUser?): Model? =
            if (guild == null || user == null || me == null) null else Model(
                user,
                guild,
                me.mfaEnabled,
                guild.ownerId == me.id,
            )
    }
}
