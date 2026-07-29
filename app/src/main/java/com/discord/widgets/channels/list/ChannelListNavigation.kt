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
import com.discord.models.invite.InviteUtils
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.channels.WidgetCreateChannel
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
import com.discord.widgets.channels.settings.WidgetChannelGroupDMSettings
import com.discord.widgets.channels.settings.WidgetTextChannelSettings
import com.discord.widgets.channels.settings.WidgetThreadSettings
import com.discord.widgets.channels.threads.browser.WidgetThreadBrowser
import com.discord.widgets.guild_role_subscriptions.entrypoint.WidgetGuildRoleSubscriptionEntryPoint
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventDetailsBottomSheet
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventListBottomSheet
import com.discord.widgets.hubs.WidgetHubAddServer
import com.discord.widgets.status.WidgetGlobalStatusIndicatorState
import com.discord.widgets.tabs.BottomNavViewObserver
import com.discord.widgets.voice.VoiceUtils
import com.discord.widgets.voice.settings.WidgetVoiceChannelSettings
import rx.Observable

/**
 * Everywhere the channels sidebar can send the user.
 *
 * Destinations reconstructed elsewhere are wired directly here so list rows cannot silently
 * become no-ops.
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

    fun showGuildProfile(fragment: Fragment, guildId: Long) {
        WidgetGuildProfileSheet.show(fragment.parentFragmentManager, true, guildId)
    }

    fun createChannel(
        fragment: Fragment,
        guildId: Long,
        channelType: Int,
        categoryId: Long?,
    ) {
        WidgetCreateChannel.show(fragment.requireContext(), guildId, channelType, categoryId)
    }

    fun shareGuildInvite(fragment: Fragment, guildId: Long) {
        WidgetGuildInviteShare.launch(
            context = fragment.requireContext(),
            fragmentManager = fragment.parentFragmentManager,
            guildId = guildId,
            source = WidgetChannelsList.ANALYTICS_SOURCE,
        )
    }

    /** Long-pressing Invite People generates the default invite, falling back to the vanity URL. */
    fun copyGuildInvite(fragment: AppFragment, anchor: View, guildId: Long?) {
        guildId ?: return
        StoreStream.getInviteSettings().generateInviteDefaultChannel(guildId)
            .ui(fragment)
            .appSubscribe(ChannelListNavigation::class.java) { invite ->
                val link = invite?.toLink(anchor.resources, INVITE_BASE_URL)
                    ?: StoreStream.getGuilds().getGuild(guildId)?.vanityUrlCode
                        ?.let { InviteUtils.createLinkFromCode(it) }
                    ?: return@appSubscribe
                copyToClipboard(anchor.context, link)
            }
    }

    fun showVoiceChannelSettings(fragment: Fragment, channelId: Long) {
        WidgetVoiceChannelSettings.launch(channelId, fragment.requireContext())
    }

    fun showTextChannelSettings(fragment: Fragment, channelId: Long) {
        WidgetTextChannelSettings.launch(channelId, fragment.requireContext())
    }

    fun showGroupDmSettings(fragment: Fragment, channelId: Long) {
        WidgetChannelGroupDMSettings.create(channelId, fragment.requireContext())
    }

    fun showThreadBrowser(fragment: Fragment, guildId: Long, channelId: Long) {
        WidgetThreadBrowser.show(
            fragment.requireContext(),
            guildId,
            channelId,
            WidgetChannelsList.ANALYTICS_SOURCE,
        )
    }

    fun showThreadSettings(fragment: Fragment, threadId: Long) {
        WidgetThreadSettings.launch(threadId, fragment.requireContext())
    }

    fun showGuildRoleSubscriptions(fragment: Fragment, guildId: Long, source: String) {
        WidgetGuildRoleSubscriptionEntryPoint.launch(fragment.requireContext(), guildId, source)
    }

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

    fun ackPremiumGuildHint() = StoreStream.getNux().setPremiumGuildHintGuildId(null)

    fun customStatusBackground(): Observable<Boolean> =
        WidgetGlobalStatusIndicatorState.Provider.get().observeState().map { it.isCustomBackground }

    fun bottomNavHeight(): Observable<Int> = BottomNavViewObserver.INSTANCE.observeHeight()

    private const val INVITE_BASE_URL = "https://discord.gg"
}
