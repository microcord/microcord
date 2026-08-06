package com.discord.utilities.icon

import android.os.Build
import android.widget.ImageView
import androidx.annotation.DimenRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayName
import com.discord.api.role.GuildRole
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.images.MGImages
import com.facebook.imagepipeline.request.ImageRequestBuilder

/**
 * Canonical construction of every Discord CDN asset URL used by the client.
 *
 * Reference: `com/discord/utilities/icon/IconUtils.java` (Kotlin metadata names restored).
 *
 * Notes on reconstruction fidelity:
 * - The reference branches every builder on `BuildConfig.HOST_CDN`. In the shipped production
 *   build that constant is `https://cdn.discordapp.com` (proved by `App.onCreate` calling
 *   `ModelEmojiCustom.setCdnUri(BuildConfig.HOST_CDN)`, which appends `/emojis/...` to it), so the
 *   empty-host developer fallback (`https://discord.com/api//guilds/{id}/icons/{hash}.jpg` and
 *   friends) is dead code in 126.21 Stable and is not reproduced here.
 * - `STATIC_IMAGE_EXTENSION` lives in `utilities/string/StringUtilsKt` in the reference. That file
 *   has not been reconstructed yet, so the value is exposed here as [staticImageExtension].
 */
object IconUtils  {

    const val ANIMATED_IMAGE_EXTENSION = "gif"
    const val DEFAULT_ICON = "asset://asset/images/default_icon.jpg"
    const val DEFAULT_ICON_BLURPLE = "asset://asset/images/default_icon_selected.jpg"

    /** Host of the production Discord CDN (`BuildConfig.HOST_CDN` in the reference build). */
    const val CDN_HOST = "https://cdn.discordapp.com"

    /** Sentinel dimension resource meaning "do not constrain the decoded image". */
    @DimenRes
    private val UNRESTRICTED = R.dimen.avatar_size_unrestricted

    private const val IMAGE_SIZE_ASSET_DEFAULT_PX = 160

    /** Default avatar variants for an account that still has a discriminator. */
    private const val LEGACY_DEFAULT_AVATAR_COUNT = 5

    /** Default avatar variants for an account migrated to a unique username. */
    private const val DEFAULT_AVATAR_COUNT = 6

    /** Reference bit-shifts the id before bucketing so ids minted together do not collide. */
    private const val DEFAULT_AVATAR_ID_SHIFT = 22

    /** Webhook authors report this discriminator; the reference pins them to the first variant. */
    const val WEBHOOK_DISCRIMINATOR = "0000"

    private val GROUP_DM_DEFAULT_ICONS: List<String> =
        (0..7).map { "asset://asset/images/group_dm_icon_$it.png" }

    /**
     * The only widths the media proxy will render. Every requested size is rounded *up* to one of
     * these before it is sent as `?size=`, so unrelated views share CDN cache entries.
     */
    private val MEDIA_PROXY_SIZES = intArrayOf(
        16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128,
        160, 240, 256, 300, 320, 480, 512, 600, 640, 1024, 1280, 1536, 2048, 3072, 4096,
    )

    /**
     * Extension used for non-animated CDN assets. Android P and Q ship a WebP decoder that mangles
     * some Discord assets, so those two releases fall back to PNG.
     */
    @JvmStatic
    val staticImageExtension: String =
        if (Build.VERSION.SDK_INT == 28 || Build.VERSION.SDK_INT == 29) "png" else "webp"

    // ---------------------------------------------------------------------------------------
    // Size quantisation
    // ---------------------------------------------------------------------------------------

    /**
     * Rounds [size] up to the smallest supported media-proxy width.
     *
     * @throws NoSuchElementException when [size] exceeds the largest supported width (4096).
     */
    @JvmStatic
    fun getMediaProxySize(size: Int): Int = MEDIA_PROXY_SIZES.first { it >= size }

    /** Appends `?size=` when a positive [size] was requested, quantising it first. */
    private fun withSize(url: String?, size: Int?): String? {
        if (url == null || size == null || size <= 0) return url
        return "$url?size=${getMediaProxySize(size)}"
    }

    /**
     * The reference emits the query unconditionally for guild/role/splash/banner builders, i.e.
     * without the `size > 0` guard used by [withSize]. Kept separate so both behaviors survive.
     */
    private fun sizeQuery(size: Int?): String =
        size?.let { "?size=${getMediaProxySize(it)}" } ?: ""

    // ---------------------------------------------------------------------------------------
    // Animation gating
    // ---------------------------------------------------------------------------------------

    /** Discord marks animated image hashes with an `a_` prefix. */
    fun isImageHashAnimated(imageHash: String): Boolean = imageHash.startsWith("a_")

    fun isDataUrlForGif(dataUrl: String): Boolean = dataUrl.startsWith("data:image/gif")

    /**
     * Resolves the CDN extension for [imageHash].
     *
     * [allowAnimation] is supplied by the caller from the user's animation preference
     * (`StoreUserSettings.observeIsAnimatedEmojisEnabled`, which already folds in reduced motion).
     * An animated hash only becomes a GIF when the user allows animation.
     */
    fun getImageExtension(imageHash: String, allowAnimation: Boolean): String =
        if (allowAnimation && isImageHashAnimated(imageHash)) {
            ANIMATED_IMAGE_EXTENSION
        } else {
            staticImageExtension
        }

    // ---------------------------------------------------------------------------------------
    // User avatars
    // ---------------------------------------------------------------------------------------

    /**
     * Avatar URL for a raw user tuple.
     *
     * A [userId] of `-1` is the "synthetic user" marker: [userAvatar] is already a full URL.
     */
    @JvmStatic
    fun getForUser(
        userId: Long?,
        userAvatar: String?,
        discriminator: Int? = null,
        allowAnimation: Boolean = false,
        size: Int? = null,
        isWebhook: Boolean = false,
    ): String? {
        if (userId == -1L) return userAvatar
        if (userId != null && userAvatar != null) {
            val extension = getImageExtension(userAvatar, allowAnimation)
            return withSize("$CDN_HOST/avatars/$userId/$userAvatar.$extension", size)
        }
        return getDefaultAvatar(getDefaultAvatarIndex(userId, discriminator, isWebhook))
    }

    @JvmStatic
    fun getForUser(user: User?, allowAnimation: Boolean = false, size: Int? = null): String? =
        getForUser(user?.id, user?.avatar, user?.discriminator, allowAnimation, size, user?.isWebhook == true)

    /**
     * Reference `getDefaultAvatarURL`.
     *
     * An account migrated to a unique username reports discriminator `0`, and is bucketed by id
     * across [DEFAULT_AVATAR_COUNT] variants; one that still has a discriminator keeps the older
     * [LEGACY_DEFAULT_AVATAR_COUNT] buckets. Both paths are live — the discriminator is what
     * chooses between them.
     */
    @JvmStatic
    fun getDefaultAvatarIndex(userId: Long?, discriminator: Int?, isWebhook: Boolean = false): Int = when {
        isWebhook -> 0
        discriminator != null && discriminator > 0 -> discriminator % LEGACY_DEFAULT_AVATAR_COUNT
        userId != null -> ((userId ushr DEFAULT_AVATAR_ID_SHIFT) % DEFAULT_AVATAR_COUNT).toInt()
        else -> 0
    }

    /** Bundled asset avatar for the given bucket (`0` until `5`). */
    fun getDefaultAvatar(index: Int): String = "asset://asset/images/default_avatar_$index.png"

    fun getForUserBanner(
        userId: Long,
        bannerHash: String?,
        size: Int? = null,
        allowAnimation: Boolean = false,
    ): String? {
        if (bannerHash.isNullOrBlank()) return null
        val extension = getBannerImageExtension(bannerHash, allowAnimation)
        return withSize("$CDN_HOST/banners/$userId/$bannerHash.$extension", size)
    }

    // ---------------------------------------------------------------------------------------
    // Per-guild member avatars and banners
    // ---------------------------------------------------------------------------------------

    fun getForGuildMember(
        guildMemberAvatar: String?,
        guildId: Long,
        userId: Long,
        size: Int? = null,
        allowAnimation: Boolean = false,
    ): String? {
        if (guildMemberAvatar.isNullOrBlank() || guildId == 0L || userId == 0L) return null
        val extension = getImageExtension(guildMemberAvatar, allowAnimation)
        return withSize(
            "$CDN_HOST/guilds/$guildId/users/$userId/avatars/$guildMemberAvatar.$extension",
            size,
        )
    }

    fun getForGuildMember(
        guildMember: GuildMember,
        size: Int? = null,
        allowAnimation: Boolean = false,
    ): String? = getForGuildMember(
        guildMember.avatarHash,
        guildMember.guildId,
        guildMember.userId,
        size,
        allowAnimation,
    )

    /** Per-guild avatar when the member has one, otherwise the global user avatar. */
    fun getForGuildMemberOrUser(
        user: User?,
        guildMember: GuildMember?,
        size: Int? = null,
        allowAnimation: Boolean = false,
    ): String? =
        if (guildMember != null && guildMember.hasAvatar()) {
            getForGuildMember(guildMember, size, allowAnimation)
        } else {
            getForUser(user, allowAnimation, size)
        }

    fun getForGuildMemberBanner(
        guildMemberBanner: String?,
        guildId: Long,
        userId: Long,
        size: Int? = null,
        allowAnimation: Boolean = false,
    ): String? {
        if (guildMemberBanner.isNullOrBlank() || guildId == 0L || userId == 0L) return null
        val extension = getBannerImageExtension(guildMemberBanner, allowAnimation)
        return withSize(
            "$CDN_HOST/guilds/$guildId/users/$userId/banners/$guildMemberBanner.$extension",
            size,
        )
    }

    private fun getBannerImageExtension(imageHash: String, allowAnimation: Boolean): String =
        if (allowAnimation && isImageHashAnimated(imageHash)) ANIMATED_IMAGE_EXTENSION else staticImageExtension

    // ---------------------------------------------------------------------------------------
    // Guild icon, banner and splash
    // ---------------------------------------------------------------------------------------

    @JvmStatic
    fun getForGuild(
        guildId: Long?,
        icon: String?,
        defaultIcon: String? = null,
        allowAnimation: Boolean = false,
        size: Int? = null,
    ): String? {
        if (icon.isNullOrEmpty() || guildId == null) return defaultIcon
        val extension = getImageExtension(icon, allowAnimation)
        return "$CDN_HOST/icons/$guildId/$icon.$extension" + sizeQuery(size)
    }

    @JvmStatic
    fun getForGuild(
        guild: Guild?,
        defaultIcon: String? = null,
        allowAnimation: Boolean = false,
        size: Int? = null,
    ): String? = getForGuild(guild?.id, guild?.icon, defaultIcon, allowAnimation, size)

    fun getBannerForGuild(
        guildId: Long?,
        banner: String?,
        size: Int? = null,
        allowAnimation: Boolean = false,
    ): String? {
        if (guildId == null || banner == null) return null
        val extension = getImageExtension(banner, allowAnimation)
        return "$CDN_HOST/banners/$guildId/$banner.$extension" + sizeQuery(size)
    }

    fun getBannerForGuild(
        guild: Guild?,
        size: Int? = null,
        allowAnimation: Boolean = false,
    ): String? = getBannerForGuild(guild?.id, guild?.banner, size, allowAnimation)

    fun getGuildSplashUrl(guildId: Long, splashHash: String, size: Int? = null): String =
        "$CDN_HOST/splashes/$guildId/$splashHash.$staticImageExtension" + sizeQuery(size)

    fun getGuildSplashUrl(guild: Guild?, size: Int? = null): String? {
        val splash = guild?.splash ?: return null
        return getGuildSplashUrl(guild.id, splash, size)
    }

    // ---------------------------------------------------------------------------------------
    // Channel and group-DM icons
    // ---------------------------------------------------------------------------------------

    /**
     * @param returnDefaultAsset when the channel has no icon and is not a group DM, `true` yields
     *   an empty string (which clears the Drawee) instead of `null` (which leaves it untouched).
     */
    @JvmStatic
    fun getForChannel(
        channelId: Long,
        icon: String?,
        type: Int,
        returnDefaultAsset: Boolean,
        size: Int? = null,
    ): String? {
        if (icon.isNullOrEmpty()) {
            if (type == Channel.GROUP_DM) return getDefaultForGroupDM(channelId)
            return if (returnDefaultAsset) "" else null
        }
        return withSize("$CDN_HOST/channel-icons/$channelId/$icon.$staticImageExtension", size)
    }

    @JvmStatic
    fun getForChannel(channel: Channel?, size: Int? = null): String? {
        if (channel == null) return null
        if (channel.type == Channel.DM) {
            // `getDMRecipient` only resolves when there is exactly one recipient; otherwise the
            // avatar falls through to the default asset.
            return getForUser(channel.getDMRecipient())
        }
        return getForChannel(channel.id, channel.icon, channel.type, false, size)
    }

    /**
     * Group DMs without a custom icon get a deterministic bundled icon derived from the creation
     * timestamp encoded in the channel snowflake.
     */
    fun getDefaultForGroupDM(channelId: Long): String {
        val createdAtMillis = SnowflakeUtils.toTimestamp(channelId)
        return GROUP_DM_DEFAULT_ICONS[(createdAtMillis % GROUP_DM_DEFAULT_ICONS.size).toInt()]
    }

    // ---------------------------------------------------------------------------------------
    // Role icons
    // ---------------------------------------------------------------------------------------

    fun getRoleIconUrl(roleId: Long, iconHash: String, size: Int? = null): String =
        "$CDN_HOST/role-icons/$roleId/$iconHash.$staticImageExtension" + sizeQuery(size)

    // ---------------------------------------------------------------------------------------
    // Emoji
    // ---------------------------------------------------------------------------------------

    /**
     * Custom emoji image URL.
     *
     * The reference builds this in `models/domain/emoji/ModelEmojiCustom` from the format string
     * `"$HOST_CDN/emojis/%s.%s?size=%s&quality=lossless"`. That model class has not been
     * reconstructed in this project, and five widgets currently hand-build the URL, so the
     * canonical implementation is placed here.
     */
    fun getForEmoji(emojiId: Long, animated: Boolean, sizePx: Int): String {
        val extension = if (animated) ANIMATED_IMAGE_EXTENSION else staticImageExtension
        return "$CDN_HOST/emojis/$emojiId.$extension?size=$sizePx&quality=lossless"
    }

    // ---------------------------------------------------------------------------------------
    // Application assets
    // ---------------------------------------------------------------------------------------

    @JvmStatic
    fun getApplicationIcon(
        applicationId: Long,
        imageId: String,
        sizePx: Int = IMAGE_SIZE_ASSET_DEFAULT_PX,
    ): String =
        "$CDN_HOST/app-icons/$applicationId/$imageId.$staticImageExtension" +
            "?size=${getMediaProxySize(sizePx)}"

    /**
     * Rich-presence asset URL.
     *
     * Assets whose id is `platform:data` are proxied from that platform rather than the Discord
     * app-asset bucket; everything else resolves against the application's asset bucket.
     */
    fun getAssetImage(
        applicationId: Long?,
        imageId: String,
        sizePx: Int = IMAGE_SIZE_ASSET_DEFAULT_PX,
    ): String? {
        val quantisedSize = getMediaProxySize(sizePx)
        if (imageId.contains(':')) {
            val parts = imageId.split(':', limit = 2)
            return getAssetPlatformUrl(parts[0].lowercase(), parts[1])
        }
        if (applicationId == null) return null
        return "$CDN_HOST/app-assets/$applicationId/$imageId.jpg?size=$quantisedSize"
    }

    fun getStoreAssetImage(
        applicationId: Long?,
        imageId: String,
        sizePx: Int = IMAGE_SIZE_ASSET_DEFAULT_PX,
    ): String? {
        if (applicationId == null) return null
        val quantisedSize = getMediaProxySize(sizePx)
        return "$CDN_HOST/app-assets/$applicationId/store/$imageId.jpg?size=$quantisedSize"
    }

    private fun getAssetPlatformUrl(platform: String, data: String): String? = when (platform) {
        "mp" -> "https://media.discordapp.net/$data"
        "spotify" -> "https://i.scdn.co/image/$data"
        else -> null
    }

    /**
     * Gift-embed splash.
     *
     * Unlike every other builder this rounds *strictly up* (first supported width greater than the
     * requested one) and clamps to the largest supported width instead of throwing, then always
     * requests `keep_aspect_ratio=true`.
     */
    fun getGiftSplashUrl(applicationId: Long, hash: String?, size: Int? = null): String? {
        if (hash == null) return null
        val quantisedSize = size?.let { requested ->
            MEDIA_PROXY_SIZES.firstOrNull { it > requested } ?: MEDIA_PROXY_SIZES.last()
        }
        val query = quantisedSize
            ?.let { "?size=$it&keep_aspect_ratio=true" }
            ?: "?keep_aspect_ratio=true"
        return "$CDN_HOST/app-icons/$applicationId/$hash.$staticImageExtension$query"
    }

    // ---------------------------------------------------------------------------------------
    // ImageView binding
    // ---------------------------------------------------------------------------------------

    @JvmStatic
    @JvmOverloads
    fun setIcon(
        imageView: ImageView,
        url: String?,
        @DimenRes sizeDimenRes: Int = UNRESTRICTED,
        transform: ((ImageRequestBuilder) -> Unit)? = null,
        changeDetector: MGImages.ChangeDetector = MGImages.AlwaysUpdateChangeDetector,
    ) {
        val sizePx = imageView.resources.getDimensionPixelSize(sizeDimenRes)
        setIcon(imageView, url, sizePx, sizePx, true, transform, changeDetector)
    }

    @JvmStatic
    @JvmOverloads
    fun setIcon(
        imageView: ImageView,
        url: String?,
        widthPixels: Int,
        heightPixels: Int,
        useSmallCache: Boolean = true,
        transform: ((ImageRequestBuilder) -> Unit)? = null,
        changeDetector: MGImages.ChangeDetector = MGImages.AlwaysUpdateChangeDetector,
    ) {
        MGImages.setImage(
            imageView,
            url,
            widthPixels,
            heightPixels,
            useSmallCache,
            transform,
            changeDetector,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun setIcon(
        imageView: ImageView,
        user: User?,
        @DimenRes sizeDimenRes: Int = UNRESTRICTED,
        transform: ((ImageRequestBuilder) -> Unit)? = null,
        changeDetector: MGImages.ChangeDetector = MGImages.AlwaysUpdateChangeDetector,
        guildMember: GuildMember? = null,
    ) {
        setIcon(
            imageView,
            user?.username,
            user?.id,
            user?.avatar,
            user?.discriminator,
            sizeDimenRes,
            transform,
            changeDetector,
            guildMember,
        )
    }

    @JvmStatic
    @JvmOverloads
    fun setIcon(
        imageView: ImageView,
        username: String?,
        userId: Long?,
        userAvatar: String?,
        discriminator: Int?,
        @DimenRes sizeDimenRes: Int = UNRESTRICTED,
        transform: ((ImageRequestBuilder) -> Unit)? = null,
        changeDetector: MGImages.ChangeDetector = MGImages.AlwaysUpdateChangeDetector,
        guildMember: GuildMember? = null,
    ) {
        val sizePx =
            if (sizeDimenRes != UNRESTRICTED) {
                imageView.resources.getDimensionPixelSize(sizeDimenRes)
            } else {
                null
            }
        val url = if (guildMember != null && guildMember.hasAvatar()) {
            getForGuildMember(guildMember, sizePx)
        } else {
            getForUser(userId, userAvatar, discriminator, size = sizePx)
        }
        setIcon(
            imageView,
            url,
            sizePx ?: 0,
            sizePx ?: 0,
            useSmallCache = true,
            transform = transform,
            changeDetector = changeDetector,
        )
        imageView.contentDescription = username
    }

    @JvmStatic
    @JvmOverloads
    fun setIcon(
        imageView: ImageView,
        channel: Channel?,
        @DimenRes sizeDimenRes: Int = UNRESTRICTED,
        changeDetector: MGImages.ChangeDetector = MGImages.AlwaysUpdateChangeDetector,
    ) {
        setIcon(imageView, getForChannel(channel), sizeDimenRes, null, changeDetector)
        imageView.contentDescription = channel?.getDisplayName()
    }

    @JvmStatic
    @JvmOverloads
    fun setIcon(
        imageView: ImageView,
        guild: Guild?,
        @DimenRes sizeDimenRes: Int = UNRESTRICTED,
        changeDetector: MGImages.ChangeDetector = MGImages.AlwaysUpdateChangeDetector,
        allowAnimation: Boolean = false,
    ) {
        setIcon(
            imageView,
            getForGuild(guild, allowAnimation = allowAnimation),
            sizeDimenRes,
            null,
            changeDetector,
        )
        imageView.contentDescription = guild?.name
    }

    @JvmStatic
    @JvmOverloads
    fun setIcon(
        imageView: ImageView,
        guildRole: GuildRole?,
        @DimenRes sizeDimenRes: Int = UNRESTRICTED,
        changeDetector: MGImages.ChangeDetector = MGImages.AlwaysUpdateChangeDetector,
    ) {
        val iconHash = guildRole?.icon ?: return
        setIcon(
            imageView,
            getRoleIconUrl(guildRole.id, iconHash),
            sizeDimenRes,
            null,
            changeDetector,
        )
    }
}
