package com.discord.widgets.channels.list

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventDetailsBottomSheet
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventListBottomSheet
import com.discord.widgets.hubs.WidgetHubAddServer
import com.discord.widgets.voice.VoiceUtils
import rx.Observable

/**
 * Everywhere the channels sidebar can send the user.
 *
 * A handful of these destinations exist in this reconstruction and are wired directly. The rest
 * are screens that have not been rebuilt yet; each is an overridable handler that does nothing,
 * so the row is still rendered and still reports the tap rather than being hidden or crashing.
 *
 * Every entry names the screen or store it is waiting on. When that lands, replace the default
 * body and delete the property — do not add a second seam alongside it.
 */
object ChannelListNavigation {

    // ---- Live -------------------------------------------------------------------------------

    /**
     * Joins a voice or stage channel.
     *
     * Live as soon as `StoreStream` calls `VoiceStoreAccess.install(...)`; until then the tap is
     * dropped rather than half-joining a call.
     */
    fun callChannel(fragment: AppFragment, channel: Channel) =
        VoiceUtils.handleCallChannel(channel, fragment)

    /** The hub "Add a server" flow. */
    fun addServerToHub(fragment: Fragment) {
        AppScreenRouter.launch(fragment.requireContext(), WidgetHubAddServer::class.java)
    }

    /** Per-channel notification overrides. */
    fun showChannelNotificationSettings(fragment: Fragment, channelId: Long) {
        WidgetChannelNotificationSettings.launch(fragment.requireContext(), channelId)
    }

    /** Copies [text] and confirms with a toast, as the reference's "Copy ID" row does. */
    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
        Toast.makeText(context, R.string.copied_text, Toast.LENGTH_SHORT).show()
    }

    // ---- Pending screens --------------------------------------------------------------------

    /** TODO(WidgetGuildProfileSheet): guild name / banner tap. */
    @JvmField
    var showGuildProfile: (fragment: Fragment, guildId: Long) -> Unit = { _, _ -> }

    /** TODO(WidgetCreateChannel): the category "+" and the section-header "+". */
    @JvmField
    var createChannel: (
        fragment: Fragment,
        guildId: Long,
        channelType: Int,
        categoryId: Long?,
    ) -> Unit = { _, _, _, _ -> }

    /** TODO(WidgetGuildInviteShare): tap on the "Invite people" row. */
    @JvmField
    var shareGuildInvite: (fragment: Fragment, guildId: Long) -> Unit = { _, _ -> }

    /**
     * TODO(StoreInstantInvites): long-press on the "Invite people" row copies a link to the
     * clipboard in the reference. [anchor] is the row, used for the confirmation toast.
     */
    @JvmField
    var copyGuildInvite: (anchor: View, guildId: Long?) -> Unit = { _, _ -> }

    /** TODO(WidgetVoiceChannelSettings): long-press on a voice channel. */
    @JvmField
    var showVoiceChannelSettings: (fragment: Fragment, channelId: Long) -> Unit = { _, _ -> }

    /** TODO(WidgetTextChannelSettings): "Edit channel" for a guild text/announcement/forum channel. */
    @JvmField
    var showTextChannelSettings: (fragment: Fragment, channelId: Long) -> Unit = { _, _ -> }

    /** TODO(WidgetChannelGroupDMSettings): "Edit channel" for a group DM. */
    @JvmField
    var showGroupDmSettings: (fragment: Fragment, channelId: Long) -> Unit = { _, _ -> }

    /** TODO(WidgetThreadBrowser): "Browse threads" from the channel sheet. */
    @JvmField
    var showThreadBrowser: (fragment: Fragment, guildId: Long, channelId: Long) -> Unit =
        { _, _, _ -> }

    /** TODO(WidgetThreadSettings): "Edit thread" from the thread sheet. */
    @JvmField
    var showThreadSettings: (fragment: Fragment, threadId: Long) -> Unit = { _, _ -> }

    /** TODO(WidgetGuildRoleSubscriptionEntryPoint): locked role-subscription channel, and the tab. */
    @JvmField
    var showGuildRoleSubscriptions: (fragment: Fragment, guildId: Long, source: String) -> Unit =
        { _, _, _ -> }

    /** Opens the scheduled-event list from a normal guild's channel sidebar. */
    fun showGuildScheduledEventList(fragment: Fragment, guildId: Long) {
        WidgetGuildScheduledEventListBottomSheet.show(fragment.parentFragmentManager, guildId)
    }

    /** Opens details for an external event surfaced in the normal guild channel list. */
    fun showGuildScheduledEventDetails(fragment: Fragment, eventId: Long) {
        WidgetGuildScheduledEventDetailsBottomSheet.showForGuild(fragment.parentFragmentManager, eventId)
    }

    /** TODO(MemberVerificationUtils): the membership-screening card's button. */
    @JvmField
    var showMemberVerification: (fragment: Fragment, guildId: Long) -> Unit = { _, _ -> }

    /** TODO(StoreNux): dismissing the boost hint must persist, or it returns on the next emission. */
    @JvmField
    var ackPremiumGuildHint: () -> Unit = { }

    // ---- Pending observers ------------------------------------------------------------------

    /**
     * Whether the global status indicator is drawing its own background over the top of the panel.
     *
     * TODO(WidgetGlobalStatusIndicatorState): while inert the panel keeps its rounded corners at
     * all times, which is only wrong while a call/stream bar is showing.
     */
    @JvmField
    var customStatusBackground: () -> Observable<Boolean> = { Observable.just(false) }

    /**
     * Height of the bottom navigation bar, mirrored into the list's trailing spacer.
     *
     * TODO(BottomNavViewObserver): while inert the spacer is zero-height, so the last row can sit
     * under the navigation bar.
     */
    @JvmField
    var bottomNavHeight: () -> Observable<Int> = { Observable.just(0) }
}
