package com.discord.widgets.guild_role_subscriptions.tier.model

import android.os.Parcel
import android.os.Parcelable
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefit
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefitType
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode

/**
 * One perk the creator is attaching to a subscription tier, while it is still being edited.
 *
 * This is the *draft* form of [GuildRoleSubscriptionBenefit]: it carries a resolved [Emoji] object
 * (so the editor can render it) rather than the `emojiId`/`emojiName` pair the API takes, and
 * [toGuildRoleSubscriptionBenefit] flattens it back for the wire.
 *
 * Equality deliberately ignores emoji *identity* and compares only the two fields the wire format
 * keeps — a custom emoji's id, or a unicode emoji's surrogates. Two [ModelEmojiCustom] instances
 * for the same emoji differ in transient fields (disambiguation index, availability), and the
 * benefit list is diffed on this equality, so comparing whole emoji objects would make every store
 * emission look like a change.
 */
sealed class Benefit : Parcelable {

    abstract val name: String
    abstract val emoji: Emoji
    abstract val description: String?

    abstract fun toGuildRoleSubscriptionBenefit(): GuildRoleSubscriptionBenefit

    override fun equals(other: Any?): Boolean {
        if (other !is Benefit) return false
        return name == other.name &&
            description == other.description &&
            emoji.customEmojiId == other.emoji.customEmojiId &&
            emoji.unicodeSurrogates == other.emoji.unicodeSurrogates
    }

    override fun hashCode(): Int = 31 * name.hashCode() + (description?.hashCode() ?: 0)

    /** A channel the subscriber gains access to. [channelId] is the referenced channel. */
    data class ChannelBenefit(
        override val name: String,
        override val emoji: Emoji,
        override val description: String? = null,
        val channelIconResId: Int? = null,
        val channelId: Long,
    ) : Benefit() {

        override fun toGuildRoleSubscriptionBenefit() = GuildRoleSubscriptionBenefit(
            emojiId = emoji.customEmojiId,
            emojiName = emoji.unicodeSurrogates,
            name = name,
            refType = GuildRoleSubscriptionBenefitType.CHANNEL,
            description = description,
            refId = channelId,
        )

        override fun equals(other: Any?): Boolean =
            super.equals(other) &&
                other is ChannelBenefit &&
                channelIconResId == other.channelIconResId &&
                channelId == other.channelId

        override fun hashCode(): Int {
            var result = 31 * name.hashCode() + emoji.hashCode()
            result = 31 * result + (description?.hashCode() ?: 0)
            result = 31 * result + (channelIconResId ?: 0)
            return 31 * result + channelId.hashCode()
        }

        override fun describeContents(): Int = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(name)
            parcel.writeParcelable(emoji, flags)
            parcel.writeString(description)
            // The reference writes a presence flag and the value in one slot each; a null icon
            // therefore writes a single 0 and no value.
            if (channelIconResId != null) {
                parcel.writeInt(1)
                parcel.writeInt(channelIconResId)
            } else {
                parcel.writeInt(0)
            }
            parcel.writeLong(channelId)
        }

        companion object CREATOR : Parcelable.Creator<ChannelBenefit> {
            override fun createFromParcel(parcel: Parcel): ChannelBenefit {
                val name = parcel.readString().orEmpty()
                val emoji = requireNotNull(
                    parcel.readParcelable<Emoji>(ChannelBenefit::class.java.classLoader),
                )
                val description = parcel.readString()
                val channelIconResId = if (parcel.readInt() != 0) parcel.readInt() else null
                return ChannelBenefit(name, emoji, description, channelIconResId, parcel.readLong())
            }

            override fun newArray(size: Int): Array<ChannelBenefit?> = arrayOfNulls(size)
        }
    }

    /** A perk with no in-app referent — merch, a shout-out, early access. */
    data class IntangibleBenefit(
        override val name: String,
        override val emoji: Emoji,
        override val description: String? = null,
    ) : Benefit() {

        override fun toGuildRoleSubscriptionBenefit() = GuildRoleSubscriptionBenefit(
            emojiId = emoji.customEmojiId,
            emojiName = emoji.unicodeSurrogates,
            name = name,
            refType = GuildRoleSubscriptionBenefitType.INTANGIBLE,
            description = description,
            refId = null,
        )

        override fun equals(other: Any?): Boolean =
            super.equals(other) && other is IntangibleBenefit

        override fun hashCode(): Int {
            val result = 31 * name.hashCode() + emoji.hashCode()
            return 31 * result + (description?.hashCode() ?: 0)
        }

        override fun describeContents(): Int = 0

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(name)
            parcel.writeParcelable(emoji, flags)
            parcel.writeString(description)
        }

        companion object CREATOR : Parcelable.Creator<IntangibleBenefit> {
            override fun createFromParcel(parcel: Parcel): IntangibleBenefit = IntangibleBenefit(
                parcel.readString().orEmpty(),
                requireNotNull(parcel.readParcelable(IntangibleBenefit::class.java.classLoader)),
                parcel.readString(),
            )

            override fun newArray(size: Int): Array<IntangibleBenefit?> = arrayOfNulls(size)
        }
    }
}

/** The id the API expects for a custom emoji, or `null` for a unicode one. */
internal val Emoji.customEmojiId: Long?
    get() = (this as? ModelEmojiCustom)?.id

/** The surrogate pair the API expects for a unicode emoji, or `null` for a custom one. */
internal val Emoji.unicodeSurrogates: String?
    get() = (this as? ModelEmojiUnicode)?.surrogates
