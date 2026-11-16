package com.discord.utilities.search.query.node.answer

import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.search.network.SearchQuery
import com.discord.utilities.search.query.FilterType
import com.discord.utilities.search.strings.SearchStringProvider
import com.discord.utilities.search.validation.SearchData
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The answer to `before:`, `after:` or `during:`.
 *
 * A date names a local-time range — a day, a month, a year, or one of RN's words (`today`,
 * `yesterday`, `week`, `month`, `year`, a month or weekday name) — as `[start, end)`.
 * Which end reaches the wire is what distinguishes the three filters:
 * `during:` sends both bounds, `before:` sends only the start as `max_id`, and `after:` only the
 * end as `min_id` — so `after:2024-01-01` means "from 2024-01-02 onwards", matching the reference.
 *
 * [range] is resolved when the node is made, since the words are localised and relative to now.
 */
data class DateNode(
    override val text: CharSequence,
    private val range: Pair<Long, Long>? = parseRange(text.toString()),
) : AnswerNode() {

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

            val calendar = Calendar.getInstance().apply {
                clear()
                set(year, (month ?: 1) - 1, day ?: 1)
            }
            if (day != null && day > calendar.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
            return calendar.rangeOf(
                when {
                    day != null -> Calendar.DAY_OF_MONTH
                    month != null -> Calendar.MONTH
                    else -> Calendar.YEAR
                },
            )
        }

        /** [parseRange], plus the localised date words RN accepts. */
        fun parseRange(
            value: String,
            strings: SearchStringProvider,
            now: Calendar = Calendar.getInstance(),
        ): Pair<Long, Long>? {
            parseRange(value)?.let { return it }
            val word = value.lowercase()
            val calendar = (now.clone() as Calendar).apply { startOf(Calendar.DAY_OF_MONTH) }
            val unit = when (word) {
                strings.todayShortcutString -> Calendar.DAY_OF_MONTH
                strings.yesterdayShortcutString -> Calendar.DAY_OF_MONTH.also { calendar.add(it, -1) }
                strings.weekShortcutString -> Calendar.WEEK_OF_YEAR.also { calendar.startOf(it) }
                strings.monthShortcutString -> Calendar.MONTH.also { calendar.startOf(it) }
                strings.yearShortcutString -> Calendar.YEAR.also { calendar.startOf(it) }
                else -> {
                    val symbols = DateFormatSymbols.getInstance()
                    val month = symbols.months.indexOfFirst { it.isNotEmpty() && it.lowercase() == word }
                    val weekday = symbols.weekdays.indexOfFirst { it.isNotEmpty() && it.lowercase() == word }
                    when {
                        // A month name is that month of this year; a weekday is that day of this week.
                        month >= 0 -> Calendar.MONTH.also {
                            calendar.set(Calendar.MONTH, month)
                            calendar.startOf(it)
                        }
                        weekday >= 0 -> Calendar.DAY_OF_MONTH.also { calendar.set(Calendar.DAY_OF_WEEK, weekday) }
                        else -> return null
                    }
                }
            }
            return calendar.rangeOf(unit)
        }

        /** Every date word [parseRange] understands, for building the parser's pattern. */
        fun words(strings: SearchStringProvider): List<String> {
            val symbols = DateFormatSymbols.getInstance()
            return listOf(
                strings.todayShortcutString,
                strings.yesterdayShortcutString,
                strings.weekShortcutString,
                strings.monthShortcutString,
                strings.yearShortcutString,
            ) + symbols.months.filter { it.isNotEmpty() } + symbols.weekdays.filter { it.isNotEmpty() }
        }

        private fun Calendar.startOf(unit: Int) {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            when (unit) {
                Calendar.WEEK_OF_YEAR -> set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                Calendar.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
                Calendar.YEAR -> set(Calendar.DAY_OF_YEAR, 1)
            }
        }

        private fun Calendar.rangeOf(unit: Int): Pair<Long, Long> {
            val start = timeInMillis
            add(unit, 1)
            return start to timeInMillis
        }

        fun formatLocalDate(timestamp: Long): String =
            SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(java.util.Date(timestamp))
    }
}
