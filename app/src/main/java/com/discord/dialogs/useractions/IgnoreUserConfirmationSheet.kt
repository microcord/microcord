package com.discord.dialogs.useractions

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.i18n.i18nFormat
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.SettingsStrings
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton

/**
 * Explains what ignoring does before it happens, and offers blocking instead.
 *
 * Ignoring is easily mistaken for blocking, so the reference gates it behind this sheet rather than
 * a plain confirmation: two lines say what it hides, the third says what it deliberately does not,
 * and the "Block instead" row leads to the stronger action for anyone who wanted that all along.
 */
class IgnoreUserConfirmationSheet : AppBottomSheet() {

    private val userId: Long get() = argumentsOrDefault.getLong(ARG_USER_ID)

    /**
     * Not parcelled, so a sheet restored after process death dismisses without acting — the same
     * contract [com.discord.dialogs.SimpleConfirmationDialog] uses for destructive confirmations.
     */
    var onConfirmIgnore: (() -> Unit)? = null
    var onBlockInstead: (() -> Unit)? = null

    override fun getContentViewResId(): Int = R.layout.widget_ignore_user_confirmation

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindRow(
            view.findViewById(R.id.ignore_confirmation_line_1),
            R.drawable.ic_check_circle_24dp,
            R.string.ignore_user_confirm_explain_line_1_title,
            R.string.ignore_user_confirm_explain_line_1_subtitle,
        )
        bindRow(
            view.findViewById(R.id.ignore_confirmation_line_2),
            R.drawable.ic_check_circle_24dp,
            R.string.ignore_user_confirm_explain_line_2_title,
            R.string.ignore_user_confirm_explain_line_2_subtitle,
        )
        bindRow(
            view.findViewById(R.id.ignore_confirmation_line_3),
            R.drawable.ic_info_24dp,
            R.string.ignore_user_confirm_explain_line_3_title,
            R.string.ignore_user_confirm_explain_line_3_subtitle,
        )

        view.findViewById<TextView>(R.id.ignore_confirmation_footer).apply {
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            text = SettingsStrings.render(
                context,
                R.string.ignore_user_confirm_cta_mobile_footer,
                "articleLink" to HELP_ARTICLE_URL,
            )
        }

        view.findViewById<MaterialButton>(R.id.ignore_confirmation_confirm).setOnClickListener {
            onConfirmIgnore?.invoke()
            dismiss()
        }

        view.findViewById<View>(R.id.ignore_confirmation_block_instead).setOnClickListener {
            onBlockInstead?.invoke()
            dismiss()
        }

        StoreStream.getUsers().observeUser(userId)
            .ui(this)
            .appSubscribe(IgnoreUserConfirmationSheet::class.java) { user ->
                user ?: return@appSubscribe
                IconUtils.setIcon(
                    view.findViewById<SimpleDraweeView>(R.id.ignore_confirmation_avatar),
                    user,
                    R.dimen.avatar_size_extra_large,
                )
                val name = StoreStream.getUserRelationships().getNickname(userId) ?: user.username
                view.findViewById<TextView>(R.id.ignore_confirmation_title).text =
                    requireContext().i18nFormat(R.string.ignore_user_confirm_title, "username" to name)
            }
    }

    private fun bindRow(row: View, iconRes: Int, labelRes: Int, subLabelRes: Int) {
        row.findViewById<ImageView>(R.id.ignore_row_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.ignore_row_label).setText(labelRes)
        row.findViewById<TextView>(R.id.ignore_row_sublabel).setText(subLabelRes)
    }

    companion object {
        private const val ARG_USER_ID = "com.discord.intent.extra.EXTRA_USER_ID"

        private const val HELP_ARTICLE_URL =
            "https://support.discord.com/hc/en-us/articles/34568139311383"

        fun show(
            fragmentManager: FragmentManager,
            userId: Long,
            onConfirmIgnore: () -> Unit,
            onBlockInstead: () -> Unit,
        ) {
            IgnoreUserConfirmationSheet().apply {
                arguments = Bundle().apply { putLong(ARG_USER_ID, userId) }
                this.onConfirmIgnore = onConfirmIgnore
                this.onBlockInstead = onBlockInstead
                show(fragmentManager, IgnoreUserConfirmationSheet::class.java.name)
            }
        }
    }
}
