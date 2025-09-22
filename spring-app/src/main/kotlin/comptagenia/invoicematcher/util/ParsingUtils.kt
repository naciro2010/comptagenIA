package comptagenia.invoicematcher.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

object ParsingUtils {
    private val amountRegex = Regex("([+-]?)\\s*([0-9]{1,3}(?:[\\s.,][0-9]{3})*|[0-9]+)(?:[.,]([0-9]{2}))?")

    private val datePatterns = listOf(
        "dd/MM/uuuu",
        "d/M/uuuu",
        "dd-MM-uuuu",
        "d-M-uuuu",
        "uuuu-MM-dd",
        "uuuu/MM/dd",
        "dd.MM.uuuu",
        "d MMM uuuu",
        "d MMMM uuuu"
    )
    private val dateFormatters: List<DateTimeFormatter> = buildList {
        val locales = listOf(Locale.FRENCH, Locale.ENGLISH, Locale.getDefault())
        for (pattern in datePatterns) {
            for (locale in locales) {
                add(DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(locale))
            }
        }
    }

    fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        for (formatter in dateFormatters) {
            try {
                return LocalDate.parse(trimmed, formatter)
            } catch (_: DateTimeParseException) {
                // try next
            }
        }
        // Try to parse day-month-year even if delimiters differ
        val cleaned = trimmed.replace('.', '/').replace('-', '/').replace("\\\\s+".toRegex(), "/")
        val fallbackFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendValue(ChronoField.DAY_OF_MONTH)
            .appendLiteral('/')
            .appendValue(ChronoField.MONTH_OF_YEAR)
            .appendLiteral('/')
            .appendValue(ChronoField.YEAR, 2, 4, java.time.format.SignStyle.NOT_NEGATIVE)
            .toFormatter()
        return try {
            LocalDate.parse(cleaned, fallbackFormatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun parseAmount(raw: String?): BigDecimal? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace("\u202f", " ").replace("\u00a0", " ").trim()
        val matcher = amountRegex.find(cleaned)
        if (matcher != null) {
            val sign = matcher.groupValues[1]
            var intPart = matcher.groupValues[2]
            val decimalPart = matcher.groupValues.getOrNull(3)

            intPart = intPart.replace("[\\s.,]".toRegex(), "")
            if (intPart.isBlank()) return null
            var result = BigDecimal(intPart)
            if (!decimalPart.isNullOrBlank()) {
                val decimal = BigDecimal(decimalPart).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                result = result.add(decimal)
            }
            if (sign == "-") {
                result = result.negate()
            }
            return result.setScale(2, RoundingMode.HALF_UP)
        }
        return null
    }
}
