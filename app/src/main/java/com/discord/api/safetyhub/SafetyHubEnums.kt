package com.discord.api.safetyhub

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

@JsonAdapter(AccountStandingStateTypeAdapter::class)
enum class AccountStandingState(val apiValue: Int) {
    ALL_GOOD(100),
    LIMITED(200),
    VERY_LIMITED(300),
    AT_RISK(400),
    SUSPENDED(500),
    ;

    companion object {
        fun fromApiValue(value: Int): AccountStandingState =
            entries.firstOrNull { it.apiValue == value } ?: ALL_GOOD
    }
}

@JsonAdapter(SafetyActionTypeTypeAdapter::class)
enum class SafetyActionType(val apiValue: Int) {
    BAN(0),
    TEMP_BAN(1),
    GLOBAL_QUARANTINE(2),
    REQUIRE_VERIFICATION(3),
    USER_WARNING(4),
    USER_SPAMMER(5),
    CHANNEL_SPAM(6),
    MESSAGE_SPAM(7),
    DISABLE_SUSPICIOUS_ACTIVITY(8),
    LIMITED_ACCESS(9),
    CHANNEL_SCHEDULE_DELETE(10),
    MESSAGE_CONTENT_REMOVAL(11),
    GUILD_DISABLE_INVITE(12),
    USER_CONTENT_REMOVAL(13),
    USER_USERNAME_MANGLE(14),
    GUILD_LIMITED_ACCESS(15),
    USER_MESSAGE_REMOVAL(16),
    GUILD_DELETE(20),
    USER_PROFILE_MANGLE(22),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromApiValue(value: Int): SafetyActionType =
            entries.firstOrNull { it.apiValue == value } ?: UNKNOWN
    }
}

@JsonAdapter(AppealStatusTypeTypeAdapter::class)
enum class AppealStatusType(val apiValue: Int) {
    REVIEW_PENDING(1),
    CLASSIFICATION_UPHELD(2),
    CLASSIFICATION_INVALIDATED(3),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromApiValue(value: Int): AppealStatusType =
            entries.firstOrNull { it.apiValue == value } ?: UNKNOWN
    }
}

@JsonAdapter(AppealEligibilityTypeAdapter::class)
enum class AppealEligibility(val apiValue: Int) {
    DSA(1),
    IN_APP(2),
    AGE_VERIFY(3),
    AGE_VERIFY_GLOBAL(4),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromApiValue(value: Int): AppealEligibility =
            entries.firstOrNull { it.apiValue == value } ?: UNKNOWN
    }
}

@JsonAdapter(AppealIngestionTypeTypeAdapter::class)
enum class AppealIngestionType(val apiValue: Int) {
    WEBFORM(0),
    AGE_VERIFY(1),
    IN_APP(2),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromApiValue(value: Int): AppealIngestionType =
            entries.firstOrNull { it.apiValue == value } ?: UNKNOWN
    }
}

/** Why the user thinks the violation is wrong, picked from a fixed list before the free-text step. */
@JsonAdapter(AppealIngestionSignalTypeAdapter::class)
enum class AppealIngestionSignal(val apiValue: Int) {
    DIDNT_VIOLATE_POLICY(0),
    TOO_STRICT_UNFAIR(1),
    DONT_AGREE_PENALTY(2),
    SOMETHING_ELSE(3),
    ;

    companion object {
        fun fromApiValue(value: Int): AppealIngestionSignal =
            entries.firstOrNull { it.apiValue == value } ?: SOMETHING_ELSE
    }
}

@JsonAdapter(SafetyGuildMemberTypeTypeAdapter::class)
enum class SafetyGuildMemberType(val apiValue: Int) {
    OWNER(1),
    MEMBER(2),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromApiValue(value: Int): SafetyGuildMemberType =
            entries.firstOrNull { it.apiValue == value } ?: UNKNOWN
    }
}

abstract class ApiValueTypeAdapter<T : Any>(
    private val fromApiValue: (Int) -> T,
    private val toApiValue: (T) -> Int,
) : TypeAdapter<T>() {
    override fun read(reader: JsonReader): T? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return fromApiValue(reader.nextInt())
    }

    override fun write(writer: JsonWriter, value: T?) {
        if (value == null) writer.nullValue() else writer.value(toApiValue(value))
    }
}

class AccountStandingStateTypeAdapter : ApiValueTypeAdapter<AccountStandingState>(
    AccountStandingState::fromApiValue,
    AccountStandingState::apiValue,
)

class SafetyActionTypeTypeAdapter : ApiValueTypeAdapter<SafetyActionType>(
    SafetyActionType::fromApiValue,
    SafetyActionType::apiValue,
)

class AppealStatusTypeTypeAdapter : ApiValueTypeAdapter<AppealStatusType>(
    AppealStatusType::fromApiValue,
    AppealStatusType::apiValue,
)

class AppealEligibilityTypeAdapter : ApiValueTypeAdapter<AppealEligibility>(
    AppealEligibility::fromApiValue,
    AppealEligibility::apiValue,
)

class AppealIngestionTypeTypeAdapter : ApiValueTypeAdapter<AppealIngestionType>(
    AppealIngestionType::fromApiValue,
    AppealIngestionType::apiValue,
)

class SafetyGuildMemberTypeTypeAdapter : ApiValueTypeAdapter<SafetyGuildMemberType>(
    SafetyGuildMemberType::fromApiValue,
    SafetyGuildMemberType::apiValue,
)

class AppealIngestionSignalTypeAdapter : ApiValueTypeAdapter<AppealIngestionSignal>(
    AppealIngestionSignal::fromApiValue,
    AppealIngestionSignal::apiValue,
)
