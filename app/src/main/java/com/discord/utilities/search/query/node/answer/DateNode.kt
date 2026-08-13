package com.discord.utilities.search.query.node.answer

import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.validation.SearchData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The answer to `before:`, `after:` or `during:`.
 *
 * A date names a whole day, so it resolves to the half-open range `[startOfDay, startOfNextDay)`.
 * Which end reaches the wire is what distinguishes the three filters:
 * `during:` sends both bounds, `before:` sends only the start as `max_id`, and `after:` only the
 * end as `min_id` — so `after:2024-01-01` means "from 2024-01-02 onwards", matching the reference.
 */
data class DateNode(override val text: CharSequence) : AnswerNode() {

    /** Unix millis for `[start, end)`, or null when [text] is not a date this understands. */
    private val range: Pair<Long, Long>? get() = parseRange(text.toString())

    override val validFilters: Set<FilterType>
        get() = setOf(FilterType.BEFORE, FilterType.AFTER, FilterType.DURING)

    override fun isValid(searchData: SearchData): Boolean = range != null

    override fun updateQuery(
        queryBuilder: SearchQuery.Builder,
        searchData: SearchData,
        filterType: FilterType?,
    ) {
        if (filterType !in validFilters) return
        val (start, end) = range ?: return
        when (filterType) {
            FilterType.DURING -> {
                queryBuilder.appendParam(SearchQuery.PARAM_MIN_ID, snowflake(start))
                queryBuilder.appendParam(SearchQuery.PARAM_MAX_ID, snowflake(end))
            }
            FilterType.BEFORE -> queryBuilder.appendParam(SearchQuery.PARAM_MAX_ID, snowflake(start))
            FilterType.AFTER -> queryBuilder.appendParam(SearchQuery.PARAM_MIN_ID, snowflake(end))
            else -> Unit
        }
    }

    private fun snowflake(timestamp: Long) = SnowflakeUtils.fromTimestamp(timestamp).toString()

    companion object {
        const val DATE_FORMAT = "yyyy-MM-dd"

        /** `yyyy-MM-dd`, `yyyy-MM` and `yyyy` all name a range; anything else is not a date. */
        fun parseRange(value: String): Pair<Long, Long>? {
            val parts = value.split('-')
            if (parts.size > 3 || parts.any { it.isEmpty() || it.any { char -> !char.isDigit() } }) return null
            val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
            if (year < 2015 || year > 9999) return null
            val month = parts.getOrNull(1)?.toIntOrNull()
            val day = parts.getOrNull(2)?.toIntOrNull()
            if (month != null && month !in 1..12) return null

            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                clear()
                set(year, (month ?: 1) - 1, day ?: 1)
            }
            if (day != null && day > calendar.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
            val start = calendar.timeInMillis
            val field = when {
                day != null -> Calendar.DAY_OF_MONTH
                month != null -> Calendar.MONTH
                else -> Calendar.YEAR
            }
            calendar.add(field, 1)
            return start to calendar.timeInMillis
        }

        fun isDate(value: String): Boolean = parseRange(value) != null

        fun format(timestamp: Long): String =
            SimpleDateFormat(DATE_FORMAT, Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(timestamp))

        fun formatLocalDate(timestamp: Long): String =
            SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(java.util.Date(timestamp))
    }
}
