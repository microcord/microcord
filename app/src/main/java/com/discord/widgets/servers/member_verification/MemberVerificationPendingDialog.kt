package com.discord.widgets.servers.member_verification

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.home.WidgetHome
import com.google.android.material.button.MaterialButton

class MemberVerificationPendingDialog : AppDialog(R.layout.widget_member_verification_pending_dialog) {

    private val guildId: Long get() = argumentsOrDefault.getLong(INTENT_EXTRA_GUILD_ID)
    private val requestedState: MemberVerificationPendingViewModel.DialogState?
        get() = argumentsOrDefault.getSerializable(INTENT_EXTRA_DIALOG_STATE)
            as? MemberVerificationPendingViewModel.DialogState
    private val viewModel: MemberVerificationPendingViewModel by appViewModels {
        MemberVerificationPendingViewModel(guildId, requestedState)
    }

    private lateinit var image: ImageView
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private lateinit var tertiary: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        image = view.findViewById(R.id.member_verification_pending_img)
        title = view.findViewById(R.id.pending_dialog_title)
        description = view.findViewById(R.id.pending_dialog_desc)
        primary = view.findViewById(R.id.member_verification_primary_btn)
        secondary = view.findViewById(R.id.member_verification_secondary_btn)
        tertiary = view.findViewById(R.id.member_verification_tertiary_btn)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().ui(this)
            .appSubscribe(MemberVerificationPendingDialog::class.java, context) { state ->
                if (state is MemberVerificationPendingViewModel.ViewState.Loaded) configure(state)
            }
        viewModel.observeEvents().ui(this)
            .appSubscribe(MemberVerificationPendingDialog::class.java, context) { event ->
                if (event is MemberVerificationPendingViewModel.Event.Error) {
                    AppToast.show(this, R.string.guild_settings_public_update_failed)
                }
            }
    }

    private fun configure(state: MemberVerificationPendingViewModel.ViewState.Loaded) {
        image.visibility = if (state.showPendingImage) View.VISIBLE else View.GONE
        tertiary.visibility = if (state.showTertiaryButton) View.VISIBLE else View.GONE
        secondary.visibility = View.VISIBLE
        primary.backgroundTintList = ColorStateList.valueOf(
            if (state.dialogState in setOf(
                    MemberVerificationPendingViewModel.DialogState.CANCEL,
                    MemberVerificationPendingViewModel.DialogState.LEAVE,
                )
            ) {
                ContextCompat.getColor(requireContext(), R.color.status_red_500)
            } else {
                ColorCompat.getThemedColor(requireContext(), R.attr.color_brand_500)
            },
        )
        when (state.dialogState) {
            MemberVerificationPendingViewModel.DialogState.UPGRADE -> configureUpgrade()
            MemberVerificationPendingViewModel.DialogState.PENDING -> configurePending(state)
            MemberVerificationPendingViewModel.DialogState.CANCEL -> configureCancel()
            MemberVerificationPendingViewModel.DialogState.REJECTED -> configureRejected(state.rejectionReason)
            MemberVerificationPendingViewModel.DialogState.LEAVE -> configureLeave(state.isMember)
        }
    }

    private fun configureUpgrade() {
        image.setImageResource(R.drawable.ic_community_update)
        title.setText(R.string.member_verification_warning_update_modal_title)
        description.setText(R.string.member_verification_warning_update_modal_desc)
        primary.setText(R.string.member_verification_warning_update)
        secondary.setText(R.string.cancel)
        primary.setOnClickListener { UriHandler.directToPlayStore(requireContext()) }
        secondary.setOnClickListener { dismiss() }
    }

    private fun configurePending(state: MemberVerificationPendingViewModel.ViewState.Loaded) {
        image.setImageResource(R.drawable.ic_member_verification_pending)
        title.setText(R.string.member_verification_pending_application_modal_title)
        description.setText(R.string.member_verification_pending_application_modal_desc)
        primary.setText(R.string.got_it)
        primary.setOnClickListener { dismiss() }
        secondary.setText(R.string.member_verification_cancel_application)
        secondary.setOnClickListener {
            viewModel.updateDialogState(MemberVerificationPendingViewModel.DialogState.CANCEL)
        }
        // Leaving is only offered to someone who actually joined; an applicant to a manual-approval
        // guild has nothing to leave, only an application to withdraw.
        tertiary.visibility = if (state.isMember) View.VISIBLE else View.GONE
        tertiary.setText(R.string.leave_server)
        tertiary.setOnClickListener {
            viewModel.updateDialogState(MemberVerificationPendingViewModel.DialogState.LEAVE)
        }
    }

    private fun configureCancel() {
        title.setText(R.string.member_verification_pending_application_cancel_modal_title)
        description.setText(R.string.member_verification_cancel_pending_application_modal_desc)
        primary.setText(R.string.member_verification_pending_application_modal_cancel)
        secondary.setText(R.string.member_verification_pending_application_modal_dismiss)
        primary.setOnClickListener {
            viewModel.resetGuildJoinRequest()
            dismiss()
        }
        secondary.setOnClickListener { dismiss() }
    }

    private fun configureRejected(reason: String?) {
        title.setText(R.string.member_verification_application_rejected_title)
        description.text = reason?.takeUnless(String::isBlank)?.let {
            "${getString(R.string.member_verification_application_rejected_reason)} $it"
        }.orEmpty()
        primary.setText(R.string.okay)
        secondary.setText(R.string.member_verification_application_reapply)
        primary.setOnClickListener { dismiss() }
        secondary.setOnClickListener {
            viewModel.reapply()
            // The cleared application leaves nothing to look at, so the form opens straight away
            // rather than dropping the user back where they came from.
            WidgetMemberVerification.create(requireContext(), guildId, "Membership Gating")
            dismiss()
        }
    }

    private fun configureLeave(isMember: Boolean) {
        title.setText(R.string.member_verification_pending_application_leave_server_modal_title)
        description.setText(R.string.member_verification_pending_application_leave_server_modal_desc)
        primary.setText(R.string.member_verification_pending_application_modal_leave)
        secondary.setText(R.string.member_verification_pending_application_modal_dismiss)
        primary.setOnClickListener {
            if (isMember) viewModel.leaveGuild() else viewModel.deleteGuildJoinRequest()
            dismiss()
        }
        secondary.setOnClickListener { dismiss() }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_DIALOG_STATE = "INTENT_EXTRA_DIALOG_STATE"

        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            guildId: Long,
            dialogState: MemberVerificationPendingViewModel.DialogState? = null,
        ) {
            MemberVerificationPendingDialog().apply {
                arguments = Bundle().apply {
                    putLong(INTENT_EXTRA_GUILD_ID, guildId)
                    putSerializable(INTENT_EXTRA_DIALOG_STATE, dialogState)
                }
            }.show(fragmentManager, MemberVerificationPendingDialog::class.java.simpleName)
        }

        @JvmOverloads
        fun enqueue(guildId: Long, dialogState: MemberVerificationPendingViewModel.DialogState? = null) {
            val notices = StoreStream.getNotices()
            val name = "MEMBERVERIFICATION-$guildId"
            notices.requestToShow(
                StoreNotices.Notice(name = name, validScreens = listOf(WidgetHome::class)) { activity ->
                    show(activity.supportFragmentManager, guildId, dialogState)
                    notices.markSeen(name)
                    true
                },
            )
        }
    }
}
