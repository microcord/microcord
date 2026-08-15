package com.discord.widgets.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.core.widget.ImageViewCompat
import androidx.cardview.widget.CardView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityType
import com.discord.api.role.GuildRole
import com.discord.databinding.ViewProfileCustomizationSectionPreviewBinding
import com.discord.databinding.WidgetUserSheetBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.PluginSurfaceCustomization.ProfileElement
import com.discord.stores.StoreStream
import com.discord.models.presence.Presence
import com.discord.api.presence.ClientStatus
import com.discord.models.user.User
import com.discord.widgets.user.profile.UserProfileConnectionsView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.discord.widgets.user.profile.ProfileThemeColors
import com.discord.widgets.user.usersheet.UserProfileVoiceSettingsView
import com.discord.widgets.user.presence.ModelRichPresence
import com.discord.widgets.user.presence.ViewHolderUserRichPresence

class ProfileCustomizationPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    data class Section(
        val element: ProfileElement?,
        val title: CharSequence,
        val body: CharSequence,
        val iconRes: Int = 0,
    )

    private val binding = WidgetUserSheetBinding.inflate(LayoutInflater.from(context), this, true)
    private val guildPlaceholder = placeholderText()
    private var pluginRows = emptyList<View>()
    private var profileColors: ProfileThemeColors? = null
    private var fragmentManager: FragmentManager? = null
    private var previewUser: User? = null
    private var richPresenceHolder: ViewHolderUserRichPresence? = null

    init {
        binding.root.isNestedScrollingEnabled = false
        binding.userSheetLoadingContainer.visibility = View.GONE
        binding.userSheetProfileEffect?.visibility = View.GONE
        binding.userSheetContent.visibility = View.VISIBLE
        binding.userSheetProfileAddToServerContainer.visibility = View.GONE
        binding.userSheetProfileEditContainer.visibility = View.GONE
        binding.userSheetRemoveFriendButton.visibility = View.GONE
        binding.userSheetMoreButton.visibility = View.GONE
        binding.userSheetAddFriendActionButton.visibility = View.GONE
        binding.userSheetPendingFriendRequestActionButton.visibility = View.GONE
        binding.userSheetMessageActionButton.apply { visibility = View.VISIBLE; isEnabled = false }
        binding.userSheetCallActionButton.apply { visibility = View.VISIBLE; isEnabled = false }
        binding.userSheetIncomingFriendRequestHeader.visibility = View.GONE
        binding.userSheetFriendRequestIncomingContainer.visibility = View.GONE
        binding.userSheetPrivateProfileCard.visibility = View.GONE
        binding.userSheetProfileStageActionsCard.visibility = View.GONE
        binding.userSheetWidgetsView.visibility = View.GONE
        binding.userSheetPluginTabContent.visibility = View.GONE
        binding.userSheetProfileHeaderView.paintsProfileSurface = false
        binding.userSheetGuildContainer.addView(guildPlaceholder)
    }

    fun bindContext(fragmentManager: FragmentManager, user: User) {
        this.fragmentManager = fragmentManager
        previewUser = user
    }

    fun bindHeader(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        binding.userSheetProfileHeaderView.updateViewState(state)
        profileColors = state.profileThemeColors(StoreStream.getUserSettingsSystem().isDarkTheme())
        applyTheme()
    }

    fun renderTabs(titles: List<CharSequence>) {
        binding.userSheetProfileTabs.removeAllTabs()
        titles.forEachIndexed { index, title ->
            binding.userSheetProfileTabs.addTab(
                binding.userSheetProfileTabs.newTab().setText(title),
                index == 0,
            )
        }
        binding.userSheetProfileTabsContainer.visibility = if (titles.size > 1) View.VISIBLE else View.GONE
    }

    fun renderSections(sections: List<Section>) {
        val builtins = builtinViews()
        builtins.values.flatten().forEach { it.visibility = View.GONE }
        pluginRows.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        pluginRows = emptyList()
        if (sections.none { it.element == ProfileElement.RICH_PRESENCE }) {
            richPresenceHolder?.disposeSubscriptions()
            binding.richPresenceContainer.visibility = View.GONE
        }

        val desired = sections.flatMap { section ->
            section.element?.let { element ->
                configureBuiltin(element, section.body)
                return@flatMap builtins.getValue(element)
            }
            val row = ViewProfileCustomizationSectionPreviewBinding.inflate(
                LayoutInflater.from(context),
                binding.userSheetMainTabContent,
                false,
            )
            row.profileCustomizationSectionHeader.text = section.title
            row.profileCustomizationSectionText.text = section.body
            row.profileCustomizationSectionCard.setCardBackgroundColor(ProfileThemeColors.surfaceOverlay(profileColors))
            profileColors?.let { colors ->
                row.profileCustomizationSectionHeader.setTextColor(colors.textMuted)
                row.profileCustomizationSectionText.setTextColor(colors.textDefault)
            }
            row.profileCustomizationSectionIcon.visibility = if (section.iconRes == 0) View.GONE else View.VISIBLE
            if (section.iconRes != 0) {
                row.profileCustomizationSectionIcon.setImageResource(section.iconRes)
                ImageViewCompat.setImageTintList(
                    row.profileCustomizationSectionIcon,
                    ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal)),
                )
            }
            pluginRows = pluginRows + row.root
            listOf(row.root)
        }

        val parent = binding.userSheetMainTabContent
        val customizable = builtins.values.flatten().toSet() + pluginRows
        val current = parent.children.filter(customizable::contains).toList()
        if (current != desired) {
            customizable.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            desired.forEach(parent::addView)
        }
    }

    private fun configureBuiltin(element: ProfileElement, body: CharSequence) {
        when (element) {
            ProfileElement.ABOUT_ME -> {
                binding.aboutMeHeaderContainer.visibility = View.VISIBLE
                binding.aboutMeHeader.visibility = View.VISIBLE
                binding.aboutMeCard.visibility = View.VISIBLE
                binding.aboutMeText.text = body
            }
            ProfileElement.MEMBER_SINCE -> {
                binding.userSheetMemberSinceHeader.visibility = View.VISIBLE
                binding.userSheetMemberSinceCard.visibility = View.VISIBLE
                binding.userSheetMemberSinceDiscord.text = body
                binding.userSheetMemberSinceDivider.visibility = View.GONE
                binding.userSheetMemberSinceGuildIcon.visibility = View.GONE
                binding.userSheetMemberSinceGuild.visibility = View.GONE
            }
            ProfileElement.FRIENDS_SINCE -> {
                binding.userSheetFriendsSinceHeader.visibility = View.VISIBLE
                binding.userSheetFriendsSinceCard.visibility = View.VISIBLE
                binding.userSheetFriendsSince.text = body
            }
            ProfileElement.VOICE_SETTINGS -> {
                binding.userSheetProfileVoiceSettingsHeader.visibility = View.VISIBLE
                binding.voiceSettingsViewCard.visibility = View.VISIBLE
                binding.userSheetProfileVoiceSettingsView.visibility = View.VISIBLE
                binding.userSheetProfileVoiceSettingsView.updateView(
                    UserProfileVoiceSettingsView.ViewState(false, false, 100f),
                )
                binding.userSheetProfileVoiceSettingsView.isEnabled = false
            }
            ProfileElement.RICH_PRESENCE -> {
                binding.richPresenceContainer.visibility = View.VISIBLE
                val activity = PREVIEW_ACTIVITY
                val presence = ModelRichPresence(Presence(ClientStatus.ONLINE, null, listOf(activity)))
                richPresenceHolder = ViewHolderUserRichPresence.setRichPresence(
                    binding.richPresenceContainer,
                    activity,
                    null,
                    richPresenceHolder,
                ).also { holder ->
                    holder.configureUi(
                        requireNotNull(fragmentManager),
                        null,
                        false,
                        previewUser,
                        context.applicationContext,
                        presence,
                        false,
                    )
                    holder.applyProfileTheme(
                        profileColors,
                        ProfileThemeColors.surfaceOverlay(profileColors),
                        profileColors?.borderOverlay
                            ?: ColorCompat.getThemedColor(context, R.attr.colorBackgroundModifierAccent),
                    )
                }
            }
            ProfileElement.GUILD -> {
                binding.userSheetGuildContainer.visibility = View.VISIBLE
                binding.userSheetGuildHeader.visibility = View.VISIBLE
                binding.userSheetGuildHeader.setText(R.string.roles)
                binding.userSheetRolesList.visibility = View.VISIBLE
                binding.userSheetRolesList.updateView(PREVIEW_ROLES, 0L, profileColors)
                binding.userSheetAdminHeader.visibility = View.GONE
                binding.userSheetAdminCard.visibility = View.GONE
                guildPlaceholder.visibility = View.GONE
            }
            ProfileElement.CONNECTIONS -> {
                binding.userSheetConnectionsHeader.visibility = View.VISIBLE
                binding.userSheetConnectionsCard.visibility = View.VISIBLE
                binding.userSheetConnectionsView.visibility = View.VISIBLE
                binding.userSheetConnectionsView.updateViewState(
                    UserProfileConnectionsView.ViewState(true, true, emptyList()),
                    { _, _, _ -> }, {}, {}, profileColors,
                )
            }
            ProfileElement.NOTE -> {
                binding.userSheetNoteHeader.visibility = View.VISIBLE
                binding.userSheetNoteText.visibility = View.VISIBLE
                binding.userSheetNoteText.text = body
            }
        }
    }

    private fun builtinViews(): Map<ProfileElement, List<View>> = mapOf(
        ProfileElement.ABOUT_ME to listOf(binding.aboutMeHeaderContainer, binding.aboutMeCard),
        ProfileElement.MEMBER_SINCE to listOf(binding.userSheetMemberSinceHeader, binding.userSheetMemberSinceCard),
        ProfileElement.FRIENDS_SINCE to listOf(binding.userSheetFriendsSinceHeader, binding.userSheetFriendsSinceCard),
        ProfileElement.VOICE_SETTINGS to listOf(binding.userSheetProfileVoiceSettingsHeader, binding.voiceSettingsViewCard),
        ProfileElement.RICH_PRESENCE to listOf(binding.richPresenceContainer),
        ProfileElement.GUILD to listOf(binding.userSheetGuildContainer),
        ProfileElement.CONNECTIONS to listOf(binding.userSheetConnectionsHeader, binding.userSheetConnectionsCard),
        ProfileElement.NOTE to listOf(binding.userSheetNoteHeader, binding.userSheetNoteText),
    )

    private fun placeholderText() = TextView(context).apply {
        setTextColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderSecondary))
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun applyTheme() {
        val colors = profileColors
        val body = colors?.bodySurface ?: ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondary)
        binding.root.background = if (colors == null) {
            android.graphics.drawable.ColorDrawable(body)
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(colors.gradientTop, colors.gradientBottom),
            )
        }
        binding.userSheetContent.setBackgroundColor(if (colors == null) body else Color.TRANSPARENT)
        val block = if (colors == null) body else Color.TRANSPARENT
        binding.userSheetHeaderContainer.setBackgroundColor(block)
        binding.userSheetProfileActionsContainer.setBackgroundColor(block)
        binding.userSheetProfileTabsContainer.setBackgroundColor(block)
        val card = ProfileThemeColors.surfaceOverlay(colors)
        listOf<CardView>(
            binding.aboutMeCard,
            binding.userSheetMemberSinceCard,
            binding.userSheetFriendsSinceCard,
            binding.voiceSettingsViewCard,
            binding.userSheetConnectionsCard,
        ).forEach { it.setCardBackgroundColor(card) }
        val primary = colors?.textDefault ?: ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary)
        val muted = colors?.textMuted ?: ColorCompat.getThemedColor(context, R.attr.colorTextMuted)
        listOf(
            binding.aboutMeText,
            binding.userSheetMemberSinceDiscord,
            binding.userSheetFriendsSince,
            binding.userSheetNoteText,
        ).forEach { it.setTextColor(primary) }
        listOf(
            binding.aboutMeHeader,
            binding.userSheetMemberSinceHeader,
            binding.userSheetFriendsSinceHeader,
            binding.userSheetProfileVoiceSettingsHeader,
            binding.userSheetGuildHeader,
            binding.userSheetConnectionsHeader,
            binding.userSheetNoteHeader,
        ).forEach { it.setTextColor(muted) }
        binding.userSheetProfileTabs.setTabTextColors(muted, primary)
        binding.userSheetProfileTabs.setSelectedTabIndicatorColor(primary)
    }

    private companion object {
        val PREVIEW_ROLES = listOf(
            GuildRole(3, "Moderator", 3L, 0x5865F2, true, 0L, true, false, null, null, null),
            GuildRole(2, "Member", 2L, 0x57F287, false, 0L, true, false, null, null, null),
            GuildRole(1, "Example Role", 1L, 0, false, 0L, true, false, null, null, null),
        )
        val PREVIEW_ACTIVITY = Activity(
            name = "Example Game",
            type = ActivityType.PLAYING,
            url = null,
            createdAt = 0L,
            timestamps = null,
            applicationId = null,
            details = "Ranked Match",
            state = "Playing Solo",
            emoji = null,
            party = null,
            assets = null,
            flags = null,
            syncId = null,
            sessionId = null,
            metadata = null,
            platform = null,
            supportedPlatforms = null,
            buttons = null,
        )
    }
}
