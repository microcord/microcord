package com.discord.utilities.icon

import android.content.res.Resources
import android.os.Build
import android.widget.ImageView
import androidx.annotation.DimenRes
import androidx.annotation.VisibleForTesting
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
import com.discord.utilities.feature.FeatureFlags
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
    const val WEBP_IMAGE_EXTENSION = "webp"
    const val PNG_IMAGE_EXTENSION = "png"
    const val JPEG_IMAGE_EXTENSION = "jpg"

    /** A `v_` hash is a video asset; the proxy serves it whole and refuses to resize it. */
    const val VIDEO_IMAGE_EXTENSION = "mp4"
    const val DEFAULT_ICON = "asset://asset/images/default_icon.jpg"
    const val DEFAULT_ICON_BLURPLE = "asset://asset/images/default_icon_selected.jpg"

    /** Host of the production Discord CDN (`BuildConfig.HOST_CDN` in the reference build). */
    const val CDN_HOST = "https://cdn.discordapp.com"

    /** Sentinel dimension resource meaning "do not constrain the decoded image". */
    @DimenRes
    private val UNRESTRICTED = R.dimen.avatar_size_unrestricted

    private const val IMAGE_SIZE_ASSET_DEFAULT_PX = 160

    /** Presence of this marks a signed attachment link, which must not be rewritten. */
    private const val SIGNED_PARAM = "hm="

    /** What a full-screen viewer asks for, in dp; density takes it the rest of the way. */
    const val AVATAR_MAX_SIZE_DP = 1024

    /** What a chat row asks for when it does not say; deliberately larger than it draws. */
    const val AVATAR_SIZE_DP = 128

    /** Emoji are requested at one size and scaled down by the row that draws them. */
    const val EMOJI_SIZE_DP = 48

    /** The reference pins guild banners to one width rather than the caller's. */
    private const val GUILD_BANNER_SIZE_DP = 360

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
     * Rounds [size] to a supported media-proxy width.
     *
     * [allowUpscaling] permits snapping down to the rung below when the overshoot is within 25%,
     * letting the proxy stretch the smaller rendition rather than fetch the next one up.
     */
    @JvmStatic
    @JvmOverloads
    fun getMediaProxySize(size: Int, allowUpscaling: Boolean = false): Int {
        if (allowUpscaling) {
            val below = MEDIA_PROXY_SIZES.lastOrNull { it <= size }
            if (below != null && size.toDouble() / below <= 1.25) return below
        }
        return MEDIA_PROXY_SIZES.firstOrNull { size <= it } ?: MEDIA_PROXY_SIZES.last()
    }

    /**
     * Every `size` a caller passes is in dp.
     *
     * The proxy renders in device pixels, so asking for the dp figure hands back an image the
     * display then has to stretch by the screen's density — which is the whole of why assets look
     * soft on a dense screen.
     */
    /** Overridable so unit tests are not at the mercy of whatever density the JVM reports. */
    @JvmStatic
    var displayDensity: () -> Float = {
        runCatching { Resources.getSystem().displayMetrics.density }.getOrDefault(1f).takeIf { it > 0f } ?: 1f
    }

    @JvmStatic
    @JvmOverloads
    fun proxySizePx(sizeDp: Int, allowUpscaling: Boolean = false, maxDensity: Float = Float.MAX_VALUE): Int =
        getMediaProxySize((sizeDp * minOf(displayDensity(), maxDensity)).toInt(), allowUpscaling)

    /** Decorations are drawn 1.2x the avatar they ring. */
    const val AVATAR_DECORATION_SIZE_DP = 70

    /**
     * `animated=true` is what makes the proxy return every frame of an animated WebP; without it
     * the same URL yields a still, which is how a static preview is requested.
     */
    private fun assetQuery(
        size: Int?,
        imageHash: String?,
        allowAnimation: Boolean,
        extension: String,
        extra: String? = null,
    ): String {
        val params = ArrayList<String>(3)
        if (size != null && size > 0) params += "size=${proxySizePx(size)}"
        if (extension == WEBP_IMAGE_EXTENSION && allowAnimation && imageHash != null && isImageHashAnimated(imageHash)) {
            params += "animated=true"
        }
        extra?.let { params += it }
        return if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
    }

    private fun withSize(url: String?, size: Int?): String? {
        if (url == null || size == null || size <= 0) return url
        return "$url?size=${proxySizePx(size)}"
    }

    private fun sizeQuery(size: Int?): String =
        size?.let { "?size=${proxySizePx(it)}" } ?: ""

    // ---------------------------------------------------------------------------------------
    // Animation gating
    // ---------------------------------------------------------------------------------------

    /** Discord marks animated image hashes with an `a_` prefix. */
    fun isImageHashAnimated(imageHash: String): Boolean = imageHash.startsWith("a_")

    fun isVideoAssetHash(imageHash: String): Boolean = imageHash.startsWith("v_")

    fun isDataUrlForGif(dataUrl: String): Boolean = dataUrl.startsWith("data:image/gif")

    /**
     * Resolves the CDN extension for [imageHash].
     *
     * [allowAnimation] is supplied by the caller from the user's animation preference
     * (`StoreUserSettings.observeIsAnimatedEmojisEnabled`, which already folds in reduced motion).
     * An animated hash only becomes a GIF when the user allows animation.
     */
    fun getImageExtension(imageHash: String, allowAnimation: Boolean): String = when {
        !allowAnimation || !isImageHashAnimated(imageHash) -> staticImageExtension
        staticImageExtension == WEBP_IMAGE_EXTENSION -> WEBP_IMAGE_EXTENSION
        else -> ANIMATED_IMAGE_EXTENSION
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
        size: Int? = AVATAR_SIZE_DP,
        isWebhook: Boolean = false,
    ): String? {
        if (userId == -1L) return userAvatar
        if (userId != null && userAvatar != null) {
            val extension = getImageExtension(userAvatar, allowAnimation)
            return "$CDN_HOST/avatars/$userId/$userAvatar.$extension" +
                assetQuery(size, userAvatar, allowAnimation, extension)
        }
        return getDefaultAvatar(getDefaultAvatarIndex(userId, discriminator, isWebhook))
    }

    @JvmStatic
    fun getForUser(user: User?, allowAnimation: Boolean = false, size: Int? = AVATAR_SIZE_DP): String? =
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
        return "$CDN_HOST/banners/$userId/$bannerHash.$extension" +
            assetQuery(size, bannerHash, allowAnimation, extension)
    }

    // ---------------------------------------------------------------------------------------
    // Per-guild member avatars and banners
    // ---------------------------------------------------------------------------------------

    fun getForGuildMember(
        guildMemberAvatar: String?,
        guildId: Long,
        userId: Long,
        size: Int? = AVATAR_SIZE_DP,
        allowAnimation: Boolean = false,
    ): String? {
        if (guildMemberAvatar.isNullOrBlank() || guildId == 0L || userId == 0L) return null
        val extension = getImageExtension(guildMemberAvatar, allowAnimation)
        return "$CDN_HOST/guilds/$guildId/users/$userId/avatars/$guildMemberAvatar.$extension" +
            assetQuery(size, guildMemberAvatar, allowAnimation, extension)
    }

    fun getForGuildMember(
        guildMember: GuildMember,
        size: Int? = AVATAR_SIZE_DP,
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
        size: Int? = AVATAR_SIZE_DP,
        allowAnimation: Boolean = false,
    ): String? =
        if (!FeatureFlags.disableServerProfilesEnabled() && guildMember != null && guildMember.hasAvatar()) {
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
        return "$CDN_HOST/guilds/$guildId/users/$userId/banners/$guildMemberBanner.$extension" +
            assetQuery(size, guildMemberBanner, allowAnimation, extension)
    }

    /**
     * A still banner is served as PNG, not WebP — the reference asks for the lossless format on the
     * one surface stretched widest. Only an animated banner takes the [getImageExtension] path.
     */
    private fun getBannerImageExtension(imageHash: String, allowAnimation: Boolean): String =
        if (allowAnimation && isImageHashAnimated(imageHash)) {
            getImageExtension(imageHash, allowAnimation)
        } else {
            PNG_IMAGE_EXTENSION
        }

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
        if (allowAnimation && isVideoAssetHash(icon)) {
            return "$CDN_HOST/icons/$guildId/$icon.$VIDEO_IMAGE_EXTENSION"
        }
        val extension = getImageExtension(icon, allowAnimation)
        return "$CDN_HOST/icons/$guildId/$icon.$extension" +
            assetQuery(size, icon, allowAnimation, extension)
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
        val extension = when {
            allowAnimation && isImageHashAnimated(banner) -> getImageExtension(banner, allowAnimation)
            staticImageExtension == WEBP_IMAGE_EXTENSION -> WEBP_IMAGE_EXTENSION
            else -> JPEG_IMAGE_EXTENSION
        }
        // JPEG has no lossless mode of its own, so the proxy is asked not to recompress.
        val quality = if (extension == JPEG_IMAGE_EXTENSION) "quality=lossless" else null
        return "$CDN_HOST/banners/$guildId/$banner.$extension" +
            assetQuery(size ?: GUILD_BANNER_SIZE_DP, banner, allowAnimation, extension, quality)
    }

    fun getBannerForGuild(
        guild: Guild?,
        size: Int? = null,
        allowAnimation: Boolean = false,
    ): String? = getBannerForGuild(guild?.id, guild?.banner, size, allowAnimation)

    fun getGuildSplashUrl(guildId: Long, splashHash: String, size: Int? = null): String =
        "$CDN_HOST/splashes/$guildId/$splashHash.$JPEG_IMAGE_EXTENSION" + sizeQuery(size)

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
        return withSize("$CDN_HOST/channel-icons/$channelId/$icon.$PNG_IMAGE_EXTENSION", size)
    }

    @JvmStatic
    fun getForChannel(channel: Channel?, size: Int? = AVATAR_SIZE_DP): String? {
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

    /**
     * The reference drops `quality=lossless` on Android once a size is given, for memory; it only
     * survives when no size narrows the request.
     */
    fun getRoleIconUrl(roleId: Long, iconHash: String, size: Int? = null): String {
        val query = if (size != null) "?size=${proxySizePx(size)}" else "?quality=lossless"
        return "$CDN_HOST/role-icons/$roleId/$iconHash.$staticImageExtension$query"
    }

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
    @JvmOverloads
    fun getForEmoji(emojiId: Long, animated: Boolean, size: Int = EMOJI_SIZE_DP, forcePng: Boolean = false): String {
        val webp = staticImageExtension == WEBP_IMAGE_EXTENSION
        val extension = when {
            forcePng -> PNG_IMAGE_EXTENSION
            !animated -> staticImageExtension
            webp -> WEBP_IMAGE_EXTENSION
            else -> ANIMATED_IMAGE_EXTENSION
        }
        // Emoji are drawn small and in bulk, so the rung below is accepted when it is close enough.
        val animatedParam = if (webp && animated) "&animated=true" else ""
        return "$CDN_HOST/emojis/$emojiId.$extension?size=${proxySizePx(size, allowUpscaling = true)}$animatedParam"
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
        "$CDN_HOST/app-icons/$applicationId/$imageId.$PNG_IMAGE_EXTENSION" +
            "?size=${proxySizePx(sizePx)}"

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
        return "$CDN_HOST/app-icons/$applicationId/$hash.$PNG_IMAGE_EXTENSION$query"
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
        setIcon(imageView, withRequestedSize(url, sizePx), sizePx, sizePx, true, transform, changeDetector)
    }

    /**
     * Rewrites a CDN link to ask for the pixels the view is about to decode it into.
     *
     * The requested size and the decode budget were previously set at opposite ends of a call — one
     * on the URL, one on the drawee — and nothing held them together, so they drifted apart
     * silently and the difference only ever showed up as a blurry icon. Deriving one from the other
     * is what stops that being expressible.
     *
     * [sizePx] is already in device pixels, so it is quantised directly rather than through
     * [proxySizePx], which is for the dp figures callers pass to the builders.
     */
    @JvmStatic
    @VisibleForTesting
    fun withRequestedSize(url: String?, sizePx: Int): String? {
        if (url == null || sizePx <= 0) return url
        if (!url.startsWith("http")) return url
        // A signed link is only valid in the exact form it was issued in.
        if (SIGNED_PARAM in url) return url
        return replaceParam(url, "size", getMediaProxySize(sizePx).toString())
    }

    private fun replaceParam(url: String, name: String, value: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return "$url?$name=$value"
        val params = url.substring(queryStart + 1)
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != name }
        return url.substring(0, queryStart) + "?" + (params + "$name=$value").joinToString("&")
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
        val url = if (!FeatureFlags.disableServerProfilesEnabled() && guildMember != null && guildMember.hasAvatar()) {
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
