package comptagenia.service

import org.apache.commons.lang3.StringUtils
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object ParsingUtils {
    private val datePatterns = listOf(
        "dd/MM/yyyy",
        "dd-MM-yyyy",
        "dd.MM.yyyy",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "dd MMM yyyy",
        "d MMM yyyy",
        "dd/MM/yy",
        "dd-MM-yy"
    ).map { DateTimeFormatter.ofPattern(it, Locale.FRENCH) }

    fun parseDate(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.trim()
        for (formatter in datePatterns) {
            try {
                val parsed = LocalDate.parse(cleaned, formatter)
                return parsed.toString()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    fun parseAmount(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        var cleaned = Normalizer.normalize(value, Normalizer.Form.NFKC)
        cleaned = cleaned.replace("€", "")
            .replace("\u00a0", "")
            .replace(" ", "")
        val commaCount = cleaned.count { it == ',' }
        val dotCount = cleaned.count { it == '.' }

        cleaned = when {
            commaCount > 0 && dotCount == 0 -> cleaned.replace(',', '.')
            commaCount > 0 && dotCount > 0 && cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.') ->
                cleaned.replace(".", "").replace(',', '.')
            else -> cleaned
        }
        cleaned = cleaned.replace("--", "-")
        return runCatching { BigDecimal(cleaned).toDouble() }.getOrNull()
    }

    fun normalizeHeader(header: String?): String {
        if (header.isNullOrBlank()) return ""
        val ascii = Normalizer.normalize(header, Normalizer.Form.NFD)
            .replace("[\\p{InCombiningDiacriticalMarks}]".toRegex(), "")
        return StringUtils.normalizeSpace(ascii).lowercase(Locale.getDefault())
    }
}
