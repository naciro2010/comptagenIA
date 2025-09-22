package comptagenia.invoicematcher.util

import java.text.Normalizer

object TextUtils {
    fun stripAccents(input: String?): String {
        val normalized = Normalizer.normalize(input ?: "", Normalizer.Form.NFD)
        val builder = StringBuilder()
        for (ch in normalized) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    fun standardizeHeader(header: String?): String {
        val lowered = stripAccents(header).lowercase().trim()
        val normalized = lowered.replace("[^a-z0-9]+".toRegex(), " ")
        return normalized.trim()
    }

    fun safeLower(input: String?): String = input?.lowercase() ?: ""
}
