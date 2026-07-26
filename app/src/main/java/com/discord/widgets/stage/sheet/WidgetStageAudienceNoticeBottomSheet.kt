package com.discord.widgets.stage.sheet

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui

/**
 * First-run explainer shown to someone who has just joined a stage as an audience member: they are
 * muted, they can raise a hand, and so on.
 *
 * The fourth bullet only applies to public stages — it warns that anyone on Discord can find the
 * room — so it is hidden for guild-only stages.
 */
class WidgetStageAudienceNoticeBottomSheet : AppBottomSheet() {

    private val channelId: Long by lazy { argumentsOrDefault.getLong(EXTRA_CHANNEL_ID) }

    private lateinit var bullet4Icon: ImageView
    private lateinit var bullet4Label: TextView

    override fun getContentViewResId() = R.layout.widget_stage_audience_notice_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bullet4Icon = view.findViewById(R.id.bullet_4_icon)
        bullet4Label = view.findViewById(R.id.bullet_4_label)
        setPeekHeightBottomView(view.findViewById(R.id.bottom_of_bullets))
        view.findViewById<View>(R.id.button).setOnClickListener { dismiss() }
    }

    override fun onResume() {
        super.onResume()
        StoreStream.getStageInstances().observeStageInstanceForChannel(channelId)
            .ui(this)
            .appSubscribe(WidgetStageAudienceNoticeBottomSheet::class.java) { configureUI(it) }
    }

    private fun configureUI(stageInstance: StageInstance?) {
        val isPublic = stageInstance?.privacyLevel == StageInstancePrivacyLevel.PUBLIC
        bullet4Icon.visibility = if (isPublic) View.VISIBLE else View.GONE
        bullet4Label.visibility = if (isPublic) View.VISIBLE else View.GONE
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        /** Shows the notice, but only the first time this install ever joins a stage audience. */
        fun show(fragmentManager: FragmentManager, channelId: Long) {
            if (WidgetStageAudienceNoticeManager.hasUserSeenAudienceNotice()) return
            WidgetStageAudienceNoticeManager.markUserSeenAudienceNotice()
            WidgetStageAudienceNoticeBottomSheet().apply {
                arguments = bundleOf(EXTRA_CHANNEL_ID to channelId)
            }.show(fragmentManager, WidgetStageAudienceNoticeBottomSheet::class.java.simpleName)
        }
    }
}
