package com.discord.widgets.channels.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.api.channel.getDeletedMessage
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.stores.StoreStream
import com.google.android.material.button.MaterialButton
import com.discord.i18n.i18nRender

internal class ThreadDeleteDialog : AppDialog(R.layout.widget_channel_settings_delete) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useDialogTheme()
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val isForumPost = StoreStream.getChannels()
            .getChannel(argumentsOrDefault.getLong(ARG_CHANNEL_ID))
            ?.let { StoreStream.getChannels().getChannel(it.parentId)?.isThreadOnlyChannel() } == true
        view.findViewById<TextView>(R.id.channel_settings_delete_title)
            .setText(if (isForumPost) R.string.delete_forum_post else R.string.delete_thread)
        view.findViewById<TextView>(R.id.channel_settings_delete_body).text =
            requireContext().i18nRender(
                R.string.delete_channel_body,
                "channelName" to argumentsOrDefault.getString(ARG_CHANNEL_NAME).orEmpty(),
            )
        view.findViewById<MaterialButton>(R.id.channel_settings_delete_cancel)
            .setOnClickListener { dismiss() }
        val confirm = view.findViewById<MaterialButton>(R.id.channel_settings_delete_confirm)
        confirm.setOnClickListener { delete(confirm) }
    }

    /**
     * Deletes here rather than leaving it to whoever opened this: the thread actions sheet that
     * offers it has already closed by the time this is confirmed, so nothing else is left to.
     */
    private fun delete(confirm: MaterialButton) {
        val channelId = argumentsOrDefault.getLong(ARG_CHANNEL_ID)
        val context = requireContext().applicationContext
        confirm.isEnabled = false
        isCancelable = false
        RestAPI.api.deleteChannel(channelId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = ThreadDeleteDialog::class.java,
                errorHandler = {
                    AppToast.show(context, R.string.default_failure_to_perform_action_message)
                    confirm.isEnabled = true
                    isCancelable = true
                },
            ) { channel ->
                channel.getDeletedMessage()?.let { AppToast.show(context, it) }
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle().apply { putLong(RESULT_CHANNEL_ID, channelId) })
                dismissAllowingStateLoss()
            }
    }

    companion object {
        const val REQUEST_KEY = "THREAD_DELETE_RESULT"
        const val RESULT_CHANNEL_ID = "RESULT_CHANNEL_ID"
        private const val ARG_CHANNEL_ID = "ARG_CHANNEL_ID"
        private const val ARG_CHANNEL_NAME = "ARG_CHANNEL_NAME"

        fun show(fragmentManager: FragmentManager, channelId: Long, channelName: String) {
            ThreadDeleteDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putString(ARG_CHANNEL_NAME, channelName)
                }
            }.show(fragmentManager, ThreadDeleteDialog::class.java.name)
        }
    }
}
