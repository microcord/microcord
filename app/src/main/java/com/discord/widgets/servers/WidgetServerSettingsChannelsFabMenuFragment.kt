package com.discord.widgets.servers

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.widgets.channels.WidgetCreateChannel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.functions.Action0

class WidgetServerSettingsChannelsFabMenuFragment :
    Fragment(R.layout.widget_server_settings_channels_sort_fab_menu) {
    private var dismissHandler: Action0? = null
    private var dismissed = false

    override fun onResume() {
        super.onResume()
        val root = requireView() as TableLayout
        val guildId = arguments?.getLong(INTENT_EXTRA_GUILD_ID, 0L) ?: 0L
        bind(root, R.id.fab_menu_add_category) {
            WidgetCreateChannel.show(requireContext(), guildId, Channel.CATEGORY)
        }
        bind(root, R.id.fab_menu_add_voice) {
            WidgetCreateChannel.show(requireContext(), guildId, Channel.GUILD_VOICE)
        }
        bind(root, R.id.fab_menu_add_text) {
            WidgetCreateChannel.show(requireContext(), guildId, Channel.GUILD_TEXT)
        }
        bind(root, R.id.fab_menu_main, null)
        root.setOnClickListener { dismiss() }
        root.post {
            if (isAdded) animateEntrance(root, root.findViewById(R.id.fab_menu_main_fab))
        }
    }

    override fun onPause() {
        super.onPause()
        dismiss()
    }

    private fun bind(root: View, id: Int, action: (() -> Unit)?) {
        val row = root.findViewById<TableRow>(id)
        val listener = View.OnClickListener {
            action?.invoke()
            dismiss()
        }
        row.setOnClickListener(listener)
        repeat(row.childCount) { row.getChildAt(it).setOnClickListener(listener) }
    }

    private fun animateEntrance(menu: ViewGroup, mainFab: FloatingActionButton) {
        repeat(menu.childCount) { index ->
            val row = menu.getChildAt(index)
            row.alpha = 0f
            row.translationY = row.height.toFloat()
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((menu.childCount - index - 1) * 25L)
                .setDuration(resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
                .start()
        }
        mainFab.scaleX = 0f
        mainFab.scaleY = 0f
        mainFab.animate().scaleX(1f).scaleY(1f)
            .setDuration(resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
            .start()
    }

    private fun dismiss() {
        if (dismissed) return
        dismissed = true
        if (isAdded) parentFragmentManager.beginTransaction().remove(this).commit()
        dismissHandler?.call()
        dismissHandler = null
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val TAG = "channels fab menu"

        @JvmStatic
        fun show(guildId: Long, fragmentManager: FragmentManager, dismissHandler: Action0) {
            val fragment = WidgetServerSettingsChannelsFabMenuFragment().apply {
                arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
                this.dismissHandler = dismissHandler
            }
            fragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.anim_fade_in, R.anim.anim_fade_out)
                .add(R.id.widget_server_settings_channels_container, fragment, TAG)
                .addToBackStack(TAG)
                .commit()
        }
    }
}
