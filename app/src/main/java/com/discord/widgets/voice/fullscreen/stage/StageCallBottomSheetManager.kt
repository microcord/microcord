package com.discord.widgets.voice.fullscreen.stage

import androidx.fragment.app.FragmentManager
import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.stores.StoreStream
import com.discord.widgets.stage.sheet.WidgetStageAudienceNoticeBottomSheet
import com.discord.widgets.stage.sheet.WidgetStageModeratorJoinBottomSheet
import com.discord.widgets.stage.sheet.WidgetStageStartEventBottomSheet

/**
 * Decides which sheet, if any, greets someone the moment they land in a stage channel.
 *
 * Exactly one of three is shown, and which one depends only on the role the user arrives with:
 * the audience gets the "you are listening" notice, a moderator arriving at a channel with no
 * running stage gets the start-stage form, and a moderator arriving at a live stage they are not
 * speaking on gets the join-as-speaker prompt. A moderator who is already a speaker gets nothing.
 */
class StageCallBottomSheetManager {

    /**
     * @return whether the user is genuinely in this stage channel — `true` even when no sheet was
     *   shown, because a moderator already on stage is a valid "nothing to prompt" outcome. The
     *   caller uses this to stop asking on subsequent state emissions.
     */
    fun configureBottomSheet(fragmentManager: FragmentManager, channel: Channel): Boolean {
        val voiceStates = StoreStream.getVoiceStates().get()[channel.id] ?: return false
        val myRoles = StoreStream.getStageChannels().getMyRoles(channel.id) ?: return false
        if (!channel.isStageVoiceChannel()) return false
        if (!voiceStates.containsKey(StoreStream.getUsers().getMe().id)) return false

        val shouldStartStage = myRoles.isModerator &&
            StoreStream.getStageInstances().getStageInstanceForChannel(channel.id) == null

        when {
            myRoles.isAudience ->
                WidgetStageAudienceNoticeBottomSheet.show(fragmentManager, channel.id)
            shouldStartStage ->
                WidgetStageStartEventBottomSheet.show(fragmentManager, channel.id)
            myRoles.isModerator && !myRoles.isSpeaker ->
                WidgetStageModeratorJoinBottomSheet.show(fragmentManager, channel.id)
        }
        return true
    }
}
