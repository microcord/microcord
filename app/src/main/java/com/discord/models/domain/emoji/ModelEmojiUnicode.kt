package com.discord.models.domain.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcel
import android.os.Parcelable

class ModelEmojiUnicode(
    val names: List<String> = emptyList(),
    val surrogates: String = "",
    val hasDiversity: Boolean = false,
    val hasMultiDiversity: Boolean = false,
    val hasDiversityParent: Boolean = false,
    val hasMultiDiversityParent: Boolean = false,
    val diversityChildren: List<ModelEmojiUnicode>? = null,
) : Parcelable {
    val codePoints: String by lazy { surrogates.toCodePoints() }
    val firstName: String get() = names.first()
    val asDiverse: List<ModelEmojiUnicode> get() = diversityChildren.orEmpty()

    constructor(parcel: Parcel) : this(
        parcel.createStringArrayList().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readInt() != 0,
        parcel.readInt() != 0,
        parcel.readInt() != 0,
        parcel.readInt() != 0,
        parcel.createTypedArrayList(CREATOR),
    )

    fun getCommand(name: String? = null) = ":${name ?: firstName}:"
    fun getImageUri(allowAnimated: Boolean, imageSize: Int, context: Context) = getImageUri(codePoints, context)
    fun getChatInputText() = getCommand()
    fun getMessageContentReplacement() = surrogates
    fun getReactionKey() = surrogates
    fun getUniqueId() = surrogates
    fun isAvailable() = true
    fun isUsable() = true
    fun requiresColons() = true
    fun getRegex(name: String? = null) = Regex("([^\\\\]|^):${Regex.escape(name ?: firstName)}:").toPattern()

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeStringList(names)
        parcel.writeString(surrogates)
        parcel.writeInt(if (hasDiversity) 1 else 0)
        parcel.writeInt(if (hasMultiDiversity) 1 else 0)
        parcel.writeInt(if (hasDiversityParent) 1 else 0)
        parcel.writeInt(if (hasMultiDiversityParent) 1 else 0)
        parcel.writeTypedList(diversityChildren)
    }

    override fun describeContents() = 0

    override fun equals(other: Any?) = other is ModelEmojiUnicode &&
        names == other.names && surrogates == other.surrogates &&
        hasDiversity == other.hasDiversity && hasMultiDiversity == other.hasMultiDiversity &&
        hasDiversityParent == other.hasDiversityParent &&
        hasMultiDiversityParent == other.hasMultiDiversityParent && diversityChildren == other.diversityChildren

    override fun hashCode(): Int {
        var result = names.hashCode()
        result = 31 * result + surrogates.hashCode()
        result = 31 * result + hasDiversity.hashCode()
        result = 31 * result + hasMultiDiversity.hashCode()
        result = 31 * result + hasDiversityParent.hashCode()
        result = 31 * result + hasMultiDiversityParent.hashCode()
        return 31 * result + (diversityChildren?.hashCode() ?: 0)
    }

    override fun toString() = "ModelEmojiUnicode(names=$names, surrogates=$surrogates, " +
        "hasDiversity=$hasDiversity, hasMultiDiversity=$hasMultiDiversity, " +
        "hasDiversityParent=$hasDiversityParent, hasMultiDiversityParent=$hasMultiDiversityParent, " +
        "diversityChildren=$diversityChildren, codePoints=$codePoints)"

    companion object CREATOR : Parcelable.Creator<ModelEmojiUnicode> {
        override fun createFromParcel(parcel: Parcel) = ModelEmojiUnicode(parcel)
        override fun newArray(size: Int) = arrayOfNulls<ModelEmojiUnicode>(size)

        @SuppressLint("DiscouragedApi", "DefaultLocale")
        fun getImageUri(codePoints: String, context: Context): String {
            val id = context.resources.getIdentifier("emoji_$codePoints", "raw", context.packageName)
            return String.format("res:///%d", id)
        }
    }
}

internal fun String.toCodePoints(): String {
    val values = ArrayList<String>()
    var index = 0
    while (index < length) {
        val point = Character.codePointAt(this, index)
        values += Integer.toHexString(point)
        index += Character.charCount(point)
    }
    return values.joinToString("_")
}
