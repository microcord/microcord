package com.discord.models.domain.emoji

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import com.discord.api.emoji.GuildEmoji
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * A guild's uploaded emoji.
 *
 * Two guilds may upload the same name, so the picker rewrites collisions to `name-2`, `name-3`…
 * via [disambiguationIndex]; [nameDisambiguated] is what the user sees and types, while [name]
 * stays the raw server value used on the wire.
 */
open class ModelEmojiCustom : Emoji {

    var id: Long = 0
        protected set
    var idStr: String = "0"
        protected set
    val name: String
    private val rolesOrNull: List<Long>?
    var isRequireColons: Boolean = false
        protected set
    var isManaged: Boolean = false
        protected set
    var isAnimated: Boolean = false
        protected set
    val available: Boolean?
    var guildId: Long = 0
        protected set

    @Transient
    var disambiguationIndex: Int = 0
        protected set

    @Transient
    private var nameDisambiguated: String? = null

    @Transient
    private var usable: Boolean = false

    val roles: List<Long> get() = rolesOrNull ?: EMPTY_ROLES

    constructor() {
        name = ""
        rolesOrNull = ArrayList()
        usable = true
        available = true
    }

    constructor(
        id: Long,
        name: String,
        roles: List<Long>?,
        requireColons: Boolean,
        managed: Boolean,
        disambiguationIndex: Int,
        nameDisambiguated: String?,
        animated: Boolean,
        usable: Boolean,
        available: Boolean,
        guildId: Long,
    ) {
        this.id = id
        this.idStr = id.toString()
        this.name = name
        this.rolesOrNull = roles
        this.isRequireColons = requireColons
        this.isManaged = managed
        this.disambiguationIndex = disambiguationIndex
        this.nameDisambiguated = nameDisambiguated
        this.isAnimated = animated
        this.usable = usable
        this.available = available
        this.guildId = guildId
    }

    /** Re-stamps [other] with a new disambiguation index and usability for the current guild. */
    constructor(other: ModelEmojiCustom, disambiguationIndex: Int, usable: Boolean) {
        this.id = other.id
        this.idStr = other.idStr
        this.rolesOrNull = other.roles
        this.isRequireColons = other.isRequireColons
        this.isManaged = other.isManaged
        this.disambiguationIndex = disambiguationIndex
        this.name = other.name
        this.isAnimated = other.isAnimated
        this.usable = usable
        this.available = other.available
        this.guildId = other.guildId
        if (disambiguationIndex > 0) nameDisambiguated = "${other.name}-$disambiguationIndex"
    }

    constructor(emoji: GuildEmoji, guildId: Long) {
        this.id = emoji.id
        this.idStr = emoji.id.toString()
        this.name = emoji.name
        this.rolesOrNull = emoji.roles ?: ArrayList()
        this.isRequireColons = emoji.requireColons
        this.isManaged = emoji.managed
        this.isAnimated = emoji.animated
        this.available = emoji.available ?: true
        this.guildId = guildId
        this.usable = true
    }

    private constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readString().orEmpty(),
        parcel.readLongList(),
        parcel.readInt() > 0,
        parcel.readInt() > 0,
        parcel.readInt(),
        parcel.readString(),
        parcel.readInt() > 0,
        parcel.readInt() > 0,
        parcel.readInt() > 0,
        parcel.readLong(),
    )

    override val names: List<String> get() = listOf(nameDisambiguated())

    override val firstName: String get() = nameDisambiguated()

    override fun getCommand(name: String?): String = ":${nameDisambiguated()}:"

    override fun getChatInputText(): String = spoofedContent() ?: getCommand(nameDisambiguated())

    override fun getMessageContentReplacement(): String =
        spoofedContent() ?: if (isAnimated) "<a:$name:$id>" else "<:$name:$id>"

    override fun getReactionKey(): String = "$name:$id"

    override fun getUniqueId(): String = idStr

    override fun getImageUri(allowAnimated: Boolean, imageSize: Int, context: Context): String =
        getImageUri(id, isAnimated && allowAnimated, imageSize)

    override fun getRegex(name: String?): Pattern {
        val pattern = if (isRequireColons) colonRegex() else "${colonRegex()}|${noColonRegex()}"
        return try {
            Pattern.compile(pattern)
        } catch (e: PatternSyntaxException) {
            NEVER_MATCHES
        }
    }

    override fun isAvailable(): Boolean = FeatureFlags.nitroSpoofEnabled() || (available ?: true)

    override fun isUsable(): Boolean = FeatureFlags.nitroSpoofEnabled() || usable

    override fun requiresColons(): Boolean = true

    fun toApiEmoji(): GuildEmoji =
        GuildEmoji(id, name, rolesOrNull, isRequireColons, isManaged, isAnimated, available)

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(name)
        parcel.writeList(rolesOrNull?.let { ArrayList<Any?>(it) })
        parcel.writeInt(if (isRequireColons) 1 else 0)
        parcel.writeInt(if (isManaged) 1 else 0)
        parcel.writeInt(disambiguationIndex)
        parcel.writeString(nameDisambiguated)
        parcel.writeInt(if (isAnimated) 1 else 0)
        parcel.writeInt(if (usable) 1 else 0)
        parcel.writeInt(if (available != false) 1 else 0)
        parcel.writeLong(guildId)
    }

    override fun equals(other: Any?): Boolean = other is ModelEmojiCustom && other.canEqual(this) &&
        id == other.id && idStr == other.idStr && name == other.name && roles == other.roles &&
        isRequireColons == other.isRequireColons && isManaged == other.isManaged &&
        isAnimated == other.isAnimated && available == other.available &&
        guildId == other.guildId && disambiguationIndex == other.disambiguationIndex &&
        nameDisambiguated() == other.nameDisambiguated() && usable == other.usable

    override fun hashCode(): Int = listOf(
        id, idStr, name, roles, isRequireColons, isManaged, isAnimated, available, guildId,
        disambiguationIndex, nameDisambiguated(), usable,
    ).hashCode()

    override fun toString(): String = "ModelEmojiCustom(id=$id, idStr=$idStr, name=$name, " +
        "roles=$roles, requireColons=$isRequireColons, managed=$isManaged, isAnimated=$isAnimated, " +
        "available=$available, guildId=$guildId, disambiguationIndex=$disambiguationIndex, " +
        "nameDisambiguated=${nameDisambiguated()}, isUsable=$usable)"

    protected open fun canEqual(other: Any?): Boolean = other is ModelEmojiCustom

    private fun nameDisambiguated(): String = nameDisambiguated ?: name

    /**
     * Fake-Nitro: an emoji the viewer cannot normally use is emitted as a markdown hyperlink to its
     * CDN URL, so the server accepts it as a plain link and recipients render it (Vencord parity).
     * Returns null when the flag is off or the emoji is genuinely usable, in which case callers keep
     * the shortcode.
     *
     * `size=48` keeps the link at the standard emoji resolution — the CDN's default is the full
     * upload, which renders far too large in a message.
     */
    private fun spoofedContent(): String? {
        if (!FeatureFlags.nitroSpoofEnabled()) return null
        if (usable && (available ?: true)) return null
        val extension = if (isAnimated) "gif" else "png"
        val url = "${IconUtils.CDN_HOST}/emojis/$idStr.$extension?size=48&quality=lossless&name=$name"
        return "[$name]($url)"
    }

    private fun colonRegex(): String = "((?<!\\\\)):${nameDisambiguated()}:"

    private fun noColonRegex(): String = "([^\\\\:]|^)${nameDisambiguated()}\\b"

    companion object CREATOR : Parcelable.Creator<ModelEmojiCustom> {

        const val NORMAL_ALPHA = 255
        const val DISABLED_ALPHA = 100

        private val EMPTY_ROLES: List<Long> = ArrayList()
        private val NEVER_MATCHES: Pattern = Pattern.compile("$^")

        /** Set once at startup from the CDN host; see `IconUtils.CDN_HOST`. */
        @JvmStatic
        var emojiUriFormat: String = "${IconUtils.CDN_HOST}/emojis/%s.%s?size=%s&quality=lossless"
            private set

        @JvmStatic
        fun setCdnUri(cdnUri: String) {
            emojiUriFormat = "$cdnUri/emojis/%s.%s?size=%s&quality=lossless"
        }

        @JvmStatic
        fun getImageUri(id: Long, animated: Boolean, imageSize: Int): String = String.format(
            emojiUriFormat,
            id,
            if (animated) IconUtils.ANIMATED_IMAGE_EXTENSION else IconUtils.staticImageExtension,
            imageSize,
        )

        override fun createFromParcel(parcel: Parcel): ModelEmojiCustom = ModelEmojiCustom(parcel)

        override fun newArray(size: Int): Array<ModelEmojiCustom?> = arrayOfNulls(size)
    }
}

/** `Parcel.readList` is untyped; the roles list is always longs, so filter the read-back. */
private fun Parcel.readLongList(): List<Long> {
    val values = ArrayList<Any?>()
    readList(values, Long::class.java.classLoader)
    return values.filterIsInstance<Long>()
}
