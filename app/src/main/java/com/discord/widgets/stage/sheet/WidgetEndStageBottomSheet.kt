package com.discord.widgets.stage.sheet

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.views.ScreenTitleView
import com.discord.widgets.stage.StageChannelAPI
import com.google.android.material.button.MaterialButton

/**
 * Confirms ending a stage, which disconnects every speaker and closes the room.
 *
 * Reports back through the fragment-result API so the caller — usually the fullscreen stage — can
 * dismiss itself. The result fires on cancel as well as on confirm: either way the moderator is
 * done with this decision.
 */
class WidgetEndStageBottomSheet : AppBottomSheet() {

    private val channelId: Long by lazy { requireArguments().getLong(EXTRA_CHANNEL_ID) }
    private val requestKey: String by lazy {
        requireArguments().getString(EXTRA_REQUEST_KEY, "")
    }

    override fun getContentViewResId() = R.layout.widget_end_stage_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = view.findViewById<ScreenTitleView>(R.id.title)
        val cancel = view.findViewById<MaterialButton>(R.id.cancel)
        val confirm = view.findViewById<MaterialButton>(R.id.confirm)

        title.setTitle(getString(R.string.explicit_end_stage_title))
        title.setSubtitle(getString(R.string.explicit_end_stage_subtitle))
        cancel.text = getString(R.string.explicit_end_stage_cancel)
        confirm.text = getString(R.string.explicit_end_stage_confirm_mobile)

        cancel.setOnClickListener {
            dismiss()
            callOnActionTaken()
        }
        confirm.setOnClickListener {
            dismiss()
            onConfirmEnd()
            callOnActionTaken()
        }
    }

    private fun onConfirmEnd() {
        StageChannelAPI.endStageInstance(channelId)
            .restSubscribeOn()
            .appSubscribe(WidgetEndStageBottomSheet::class.java) { }
    }

    private fun callOnActionTaken() = setFragmentResult(requestKey, Bundle())

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_REQUEST_KEY = "INTENT_EXTRA_REQUEST_KEY"

        /**
         * Listens for this sheet closing. [requestKey] must match the one passed to [show];
         * a mismatched key is ignored rather than firing the wrong caller's callback.
         */
        fun registerForResult(fragment: Fragment, requestKey: String, onActionTaken: () -> Unit) {
            fragment.setFragmentResultListener(requestKey) { resultRequestKey, _ ->
                if (resultRequestKey == requestKey) onActionTaken()
            }
        }

        fun show(fragmentManager: FragmentManager, requestKey: String, channelId: Long) {
            WidgetEndStageBottomSheet().apply {
                arguments = bundleOf(
                    EXTRA_CHANNEL_ID to channelId,
                    EXTRA_REQUEST_KEY to requestKey,
                )
            }.show(fragmentManager, WidgetEndStageBottomSheet::class.java.simpleName)
        }
    }
}
