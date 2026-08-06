package com.discord.widgets.channels.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton
import com.discord.i18n.i18nRender

internal class ThreadDeleteDialog : AppDialog(R.layout.widget_channel_settings_delete) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.channel_settings_delete_title).setText(R.string.delete_thread)
        view.findViewById<TextView>(R.id.channel_settings_delete_body).text =
            requireContext().i18nRender(
                R.string.delete_channel_body,
                "channelName" to argumentsOrDefault.getString(ARG_CHANNEL_NAME).orEmpty(),
            )
        view.findViewById<MaterialButton>(R.id.channel_settings_delete_cancel)
            .setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.channel_settings_delete_confirm)
            .setOnClickListener {
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    Bundle().apply { putLong(RESULT_CHANNEL_ID, argumentsOrDefault.getLong(ARG_CHANNEL_ID)) },
                )
                dismiss()
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
