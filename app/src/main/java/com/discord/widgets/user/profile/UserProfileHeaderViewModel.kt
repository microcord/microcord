package com.discord.widgets.user.profile

import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import com.discord.api.channel.Channel
import com.discord.api.channel.isMultiUserDM
import com.discord.api.premium.PremiumTier
import com.discord.api.user.UserProfile
import com.discord.app.AppViewModel
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.streams.StreamContext
import com.discord.widgets.user.presence.ModelRichPresence
import rx.Observable

class UserProfileHeaderViewModel @JvmOverloads constructor(
    private val userId: Long = ME,
    storeObservable: Observable<StoreState>? = null,
) : AppViewModel<UserProfileHeaderViewModel.ViewState>(ViewState.Uninitialized) {
    data class StoreState(
        val me: MeUser,
        val user: User,
        val userIdToGuildMemberMap: Map<Long, GuildMember>,
        val guildMembers: Collection<Map<Long, GuildMember>>,
        val richPresence: ModelRichPresence?,
        val streamContext: StreamContext?,
        val userProfile: UserProfile,
        val allowAnimatedEmojis: Boolean,
        val reducedMotionEnabled: Boolean,
        val channel: Channel?,
        val syncProfileThemeWithUserTheme: Boolean = false,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Loaded(
            val user: User,
            val banner: String?,
            val bannerColorHex: String?,
            val guildMember: GuildMember?,
            val guildMembersForAka: List<GuildMember>,
            val presence: Presence?,
            val streamContext: StreamContext?,
            val userProfile: UserProfile,
            val isMeUserPremium: Boolean,
            val isMeUserVerified: Boolean,
            val allowAnimatedEmojis: Boolean,
            val showPresence: Boolean,
            val editable: Boolean,
            val reducedMotionEnabled: Boolean,
            val allowAnimationInReducedMotion: Boolean,
            val isMe: Boolean,
            /** Where this profile is being viewed from; decides whether a friend nickname applies. */
            val channelId: Long? = null,
            val bannerGuildId: Long? = null,
            val themeColors: List<Int>? = null,
            val syncProfileThemeWithUserTheme: Boolean = false,
        ) : ViewState() {
            val hasGuildMemberAvatar = guildMember?.hasAvatar() == true
            val hasNickname = !guildMember?.nick.isNullOrBlank()
            val showMediumAvatar = hasGuildMemberAvatar && !hasNickname
            val showSmallAvatar = hasGuildMemberAvatar && hasNickname
            val shouldAnimateBanner = !reducedMotionEnabled || allowAnimationInReducedMotion
            private val guildProfileBanner = userProfile.guildMemberProfile?.banner
            val effectiveBanner = guildMember?.bannerHash
                ?: guildProfileBanner
                ?: userProfile.userProfile?.banner
                ?: banner
            val effectiveBannerGuildId = when {
                guildMember?.hasBanner() == true -> guildMember.guildId
                guildProfileBanner != null -> bannerGuildId
                    ?: userProfile.guildMemberProfile?.guildId
                    ?: guildMember?.guildId
                else -> null
            }
            val shouldAutoPlayBanner = shouldAnimateBanner && effectiveBanner?.let {
                IconUtils.isDataUrlForGif(it) || IconUtils.isImageHashAnimated(it)
            } == true
            val shouldShowGIFTag = reducedMotionEnabled && !allowAnimationInReducedMotion &&
                effectiveBanner?.let(IconUtils::isImageHashAnimated) == true
            val themeGradientColors = (themeColors
                ?: userProfile.guildMemberProfile?.themeColors
                ?: userProfile.userProfile?.themeColors)
                ?.takeIf { it.size == 2 }
                ?.mapNotNull(::profileColorToArgb)
                ?.takeIf { it.size == 2 }
            /** A guild profile's pronouns fall back to the account's, as its bio does not. */
            val pronouns: String? = (
                userProfile.guildMemberProfile?.pronouns?.takeIf(String::isNotBlank)
                    ?: userProfile.userProfile?.pronouns
                )?.takeIf(String::isNotBlank)

            val avatarDecoration = userProfile.guildMember?.avatarDecorationData
                ?: userProfile.user.avatarDecorationData?.value
            val profileEffect = (
                userProfile.guildMemberProfile?.profileEffect
                    ?: userProfile.userProfile?.profileEffect
                )?.takeIf { FeatureFlags.profileEffectsEnabled() }
            val avatarColorId by lazy { user.avatar ?: user.discriminator.toString() }
            val guildMemberColorId by lazy { guildMember?.avatarHash ?: avatarColorId }
            val showAkas = guildMembersForAka.isNotEmpty() && !isMe

            internal fun profileThemeColors(isDarkTheme: Boolean): ProfileThemeColors? =
                themeGradientColors?.let {
                    ProfileThemeColors.from(it[0], it[1], isDarkTheme, syncProfileThemeWithUserTheme)
                }
        }
    }

    init {
        storeObservable?.computationLatest()?.ui(this)?.appSubscribe(
            UserProfileHeaderViewModel::class.java,
            onNext = ::handleStoreState,
        )
    }

    internal fun handleStoreState(storeState: StoreState) {
        updateViewState(mapStoreState(storeState, viewState))
    }

    @MainThread
    fun toggleAllowAnimationInReducedMotion() {
        val state = viewState as? ViewState.Loaded ?: return
        if (!state.reducedMotionEnabled) return
        updateViewState(state.copy(allowAnimationInReducedMotion = !state.allowAnimationInReducedMotion))
    }

    companion object {
        const val ME = -1L

        internal fun profileColorToArgb(color: Int): Int? =
            color.takeIf { it in 0..0xFFFFFF }?.or(0xFF000000.toInt())

        internal fun mapStoreState(storeState: StoreState, currentState: ViewState?): ViewState.Loaded {
            val userId = storeState.user.id
            val guildMember = storeState.userIdToGuildMemberMap[userId]
            val guildMembersForAka = if (storeState.channel?.isMultiUserDM() == true) {
                storeState.guildMembers.mapNotNull { it[userId] }
            } else {
                emptyList()
            }
            val profileMetadata = storeState.userProfile.guildMemberProfile
                ?: storeState.userProfile.userProfile
            return ViewState.Loaded(
                user = storeState.user,
                banner = profileMetadata?.banner ?: storeState.userProfile.user.banner?.value,
                bannerGuildId = profileMetadata?.guildId,
                bannerColorHex = storeState.userProfile.userProfile?.accentColor
                    ?.let { "#%06X".format(it) }
                    ?: storeState.userProfile.user.bannerColor?.value,
                themeColors = profileMetadata?.themeColors,
                guildMember = guildMember,
                guildMembersForAka = guildMembersForAka,
                presence = storeState.richPresence?.presence,
                streamContext = storeState.streamContext,
                userProfile = storeState.userProfile,
                isMeUserPremium = storeState.me.premiumTier != PremiumTier.NONE,
                isMeUserVerified = storeState.me.isVerified,
                allowAnimatedEmojis = storeState.allowAnimatedEmojis,
                showPresence = true,
                editable = false,
                reducedMotionEnabled = storeState.reducedMotionEnabled,
                allowAnimationInReducedMotion =
                    (currentState as? ViewState.Loaded)?.allowAnimationInReducedMotion ?: false,
                isMe = userId == storeState.me.id,
                channelId = storeState.channel?.id,
                syncProfileThemeWithUserTheme = storeState.syncProfileThemeWithUserTheme,
            )
        }
    }
}

internal data class ProfileThemeColors(
    val primary: Int,
    val headerSurface: Int,
    val bodySurface: Int,
    val gradientTop: Int,
    val gradientBottom: Int,
    val sectionOverlay: Int,
    val noteOverlay: Int,
    val borderOverlay: Int,
    val button: Int,
    val rolePillSurface: Int,
    val tagPillSurface: Int,
    val textDefault: Int,
    val textMuted: Int,
    val textSubtle: Int,
    val isDark: Boolean,
) {
    companion object {
        /** `PROFILE_GRADIENT_OVERLAY` is 60% black or white; the synced variant is 80%. */
        private const val OVERLAY_ALPHA = 153
        private const val OVERLAY_ALPHA_SYNCED = 204

        private const val MOD_MUTED_ALPHA = 0x0D
        private const val MOD_STRONG_ALPHA = 0x1A

        private const val DARK_SECTION = 0x29000000
        private const val LIGHT_SECTION = 0x3DFFFFFF
        private const val DARK_BORDER = 0x14FFFFFF
        private const val LIGHT_BORDER = 0x14000000

        fun from(
            primary: Int,
            secondary: Int,
            isDarkTheme: Boolean,
            syncWithUserTheme: Boolean = false,
        ): ProfileThemeColors {
            // Desktop chooses the custom profile's mode from the primary color, independently of
            // the app theme. Dark profiles get a dark derived surface and light text. The
            // accessibility setting overrides that with the reader's own theme.
            val profileIsDark =
                if (syncWithUserTheme) isDarkTheme else perceivedDarkness(primary) > 0.5
            val overlay = if (profileIsDark) 0x000000 else 0xFFFFFF
            val alpha = if (syncWithUserTheme) OVERLAY_ALPHA_SYNCED else OVERLAY_ALPHA
            val top = overlayed(primary, overlay, alpha)
            val bottom = overlayed(secondary, overlay, alpha)
            return ProfileThemeColors(
                primary = primary,
                headerSurface = top,
                bodySurface = top,
                gradientTop = top,
                gradientBottom = bottom,
                sectionOverlay = if (profileIsDark) DARK_SECTION else LIGHT_SECTION,
                // The note sits on the same overlay as the mutual servers and friends rows; a
                // heavier one reads as an opaque panel rather than part of the profile.
                noteOverlay = if (profileIsDark) DARK_SECTION else LIGHT_SECTION,
                borderOverlay = if (profileIsDark) DARK_BORDER else LIGHT_BORDER,
                button = contrastButton(primary),
                rolePillSurface = gradientOverlay(secondary, profileIsDark, MOD_MUTED_ALPHA),
                tagPillSurface = gradientOverlay(secondary, profileIsDark, MOD_STRONG_ALPHA),
                textDefault = if (profileIsDark) 0xF5FFFFFF.toInt() else 0xF5000000.toInt(),
                textMuted = if (profileIsDark) 0xC2FFFFFF.toInt() else 0xC2000000.toInt(),
                textSubtle = if (profileIsDark) 0xE0FFFFFF.toInt() else 0xE0000000.toInt(),
                isDark = profileIsDark,
            )
        }

        /**
         * The `BACKGROUND_MOD_*` family under a profile theme: the secondary colour fully
         * saturated and pinned to the opposite end of the lightness range, laid over the
         * gradient at the token's own opacity.
         */
        private fun gradientOverlay(secondary: Int, profileIsDark: Boolean, alpha: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(secondary, hsl)
            hsl[1] = 1f
            hsl[2] = if (profileIsDark) 0.92f else 0.08f
            return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), alpha)
        }

        /** Discord adjusts LAB lightness in chroma.js steps until white contrast is between 7 and 10. */
        private fun contrastButton(primary: Int): Int {
            val lab = colorToLab(primary)
            repeat(100) {
                val color = labToColor(lab)
                val contrast = contrast(color, 0xFFFFFFFF.toInt())
                if (contrast in 7.0..10.0) return color
                lab[0] = (lab[0] + if (contrast > 10.0) 18.0 else -18.0).coerceIn(0.0, 100.0)
            }
            return labToColor(lab)
        }

        private fun contrast(first: Int, second: Int): Double {
            val firstLuminance = luminance(first)
            val secondLuminance = luminance(second)
            return (maxOf(firstLuminance, secondLuminance) + 0.05) /
                (minOf(firstLuminance, secondLuminance) + 0.05)
        }

        private fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color ushr shift) and 0xFF) / 255.0
                return if (value <= 0.03928) value / 12.92
                else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
        }

        private fun perceivedDarkness(color: Int): Double = 1.0 - (
            0.299 * ((color ushr 16) and 0xFF) +
                0.587 * ((color ushr 8) and 0xFF) +
                0.114 * (color and 0xFF)
            ) / 255.0

        private fun colorToLab(color: Int): DoubleArray {
            fun channel(shift: Int): Double {
                val value = ((color ushr shift) and 0xFF) / 255.0
                return if (value <= 0.04045) value / 12.92
                else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            val red = channel(16)
            val green = channel(8)
            val blue = channel(0)
            val x = (red * 0.4124 + green * 0.3576 + blue * 0.1805) / 0.95047
            val y = red * 0.2126 + green * 0.7152 + blue * 0.0722
            val z = (red * 0.0193 + green * 0.1192 + blue * 0.9505) / 1.08883
            fun pivot(value: Double) = if (value > 0.008856) Math.cbrt(value) else 7.787 * value + 16.0 / 116.0
            val fx = pivot(x)
            val fy = pivot(y)
            val fz = pivot(z)
            return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
        }

        private fun labToColor(lab: DoubleArray): Int {
            val rgb = labToRgb(lab)
            fun channel(value: Double) = Math.round(value * 255.0).toInt()
            return (0xFF shl 24) or (channel(rgb[0]) shl 16) or (channel(rgb[1]) shl 8) or channel(rgb[2])
        }

        private fun labToRgb(lab: DoubleArray): DoubleArray {
            val fy = (lab[0] + 16.0) / 116.0
            val fx = lab[1] / 500.0 + fy
            val fz = fy - lab[2] / 200.0
            fun pivot(value: Double): Double {
                val cube = value * value * value
                return if (cube > 0.008856) cube else (value - 16.0 / 116.0) / 7.787
            }
            val x = 0.95047 * pivot(fx)
            val y = pivot(fy)
            val z = 1.08883 * pivot(fz)
            fun channel(value: Double): Double {
                val gamma = if (value <= 0.0031308) 12.92 * value
                else 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055
                return gamma.coerceIn(0.0, 1.0)
            }
            val red = channel(x * 3.2406 - y * 1.5372 - z * 0.4986)
            val green = channel(x * -0.9689 + y * 1.8758 + z * 0.0415)
            val blue = channel(x * 0.0557 - y * 0.2040 + z * 1.0570)
            return doubleArrayOf(red, green, blue)
        }

        /**
         * `calculateOverlayedColor`: source-over in non-linear sRGB, per channel, floored.
         * [alpha] is the 0-255 form the reference quantises its token opacity to before mixing.
         */
        private fun overlayed(base: Int, overlay: Int, alpha: Int): Int {
            val opacity = alpha / 255.0
            fun channel(shift: Int): Int {
                val over = (overlay ushr shift) and 0xFF
                val under = (base ushr shift) and 0xFF
                return Math.floor(opacity * over + (1 - opacity) * under).toInt()
            }
            return 0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        internal fun contrastRatio(first: Int, second: Int) = contrast(first, second)
    }
}
