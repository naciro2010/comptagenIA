package comptagenia.invoicematcher.service

import comptagenia.invoicematcher.model.InvoiceData
import comptagenia.invoicematcher.model.InvoiceFile
import comptagenia.invoicematcher.util.ParsingUtils
import comptagenia.invoicematcher.util.TextUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

@Service
class InvoiceExtractionService(
    private val pdfTextExtractor: PdfTextExtractor,
    @Autowired(required = false) private val ollamaClient: OllamaClient? = null
) {
    private val invoiceNumberPatterns = listOf(
        Regex("facture\\s*(?:n[°o]|no|num(?:ero)?)?\\s*[:#-]?\\s*([a-z0-9\\-_/]{3,})", RegexOption.IGNORE_CASE),
        Regex("invoice\\s*(?:n[°o]|no|#)?\\s*[:#-]?\\s*([a-z0-9\\-_/]{3,})", RegexOption.IGNORE_CASE),
        Regex("\\b(?:facture|invoice)\\s*[:#-]?\\s*([a-z0-9\\-_/]{3,})", RegexOption.IGNORE_CASE)
    )

    private val datePatterns = listOf(
        Regex("date\\s*(?:de\\s*facture|facture|invoice)?\\s*[:#-]?\\s*([0-9]{1,2}[\\-/\\.][0-9]{1,2}[\\-/\\.][0-9]{2,4}|[0-9]{4}[\\-/\\.][0-9]{1,2}[\\-/\\.][0-9]{1,2}|\\d{1,2}\\s\\w+\\s\\d{4})", RegexOption.IGNORE_CASE)
    )

    private val totalPatterns = listOf(
        Regex("total\\s*(?:ttc)?\\s*[:#-]?\\s*([+-]?\\s*[0-9\\s.,]+)", RegexOption.IGNORE_CASE),
        Regex("montant\\s*(?:ttc|total)\\s*[:#-]?\\s*([+-]?\\s*[0-9\\s.,]+)", RegexOption.IGNORE_CASE)
    )

    private val currencyMarkers = listOf("eur", "€", "euro", "euros")

    fun extractInvoices(files: List<InvoiceFile>, useLlm: Boolean = false, llmModel: String? = null): List<InvoiceData> =
        files.map { extractSingleInvoice(it, useLlm, llmModel) }

    private fun extractSingleInvoice(file: InvoiceFile, useLlm: Boolean, llmModel: String?): InvoiceData {
        val text = pdfTextExtractor.extractText(file.bytes)
        var parsed = heuristicParse(text)
        if (useLlm && ollamaClient?.isEnabled() == true) {
            val enriched = ollamaClient.extractInvoiceFields(text, llmModel)
            parsed = mergeWithLlm(parsed, enriched)
        }
        return parsed.copy(
            filename = file.filename,
            rawText = text
        )
    }

    private fun heuristicParse(text: String): InvoiceData {
        val normalized = TextUtils.stripAccents(text)
        val lowered = normalized.lowercase()

        val invoiceNumber = findInvoiceNumber(normalized)
        val invoiceDate = findInvoiceDate(lowered)
        val totalAmount = findTotalAmount(lowered)
        val currency = findCurrency(lowered)

        return InvoiceData(
            filename = "",
            invoiceNumber = invoiceNumber,
            invoiceDate = invoiceDate,
            totalAmount = totalAmount,
            currency = currency ?: "EUR",
            rawText = text
        )
    }

    private fun findInvoiceNumber(normalizedText: String): String? {
        for (pattern in invoiceNumberPatterns) {
            val match = pattern.find(normalizedText)
            if (match != null) {
                return match.groupValues[1].trim { it <= ' ' || it in ":#-/" }
            }
        }
        return null
    }

    private fun findInvoiceDate(loweredText: String): LocalDate? {
        for (pattern in datePatterns) {
            val match = pattern.find(loweredText)
            if (match != null) {
                ParsingUtils.parseDate(match.groupValues[1])?.let { return it }
            }
        }
        val fallback = Regex("(\\d{1,2}[-/. ]\\d{1,2}[-/. ]\\d{2,4}|\\d{4}[-/. ]\\d{1,2}[-/. ]\\d{1,2})")
        val match = fallback.find(loweredText)
        if (match != null) {
            return ParsingUtils.parseDate(match.groupValues[1])
        }
        return null
    }

    private fun findTotalAmount(loweredText: String): BigDecimal? {
        for (pattern in totalPatterns) {
            val match = pattern.find(loweredText)
            if (match != null) {
                ParsingUtils.parseAmount(match.groupValues[1])?.let { return it }
            }
        }
        val amounts = Regex("[+-]?\\s*[\\d\\s.,]{2,}").findAll(loweredText)
            .mapNotNull { ParsingUtils.parseAmount(it.value) }
            .toList()
        return amounts.maxOrNull()
    }

    private fun findCurrency(loweredText: String): String? {
        for (marker in currencyMarkers) {
            if (loweredText.contains(marker)) {
                return "EUR"
            }
        }
        return null
    }

    private fun mergeWithLlm(heuristic: InvoiceData, llm: OllamaClient.LlmExtractionResult?): InvoiceData {
        if (llm == null) return heuristic
        return heuristic.copy(
            invoiceNumber = llm.invoiceNumber ?: heuristic.invoiceNumber,
            invoiceDate = llm.invoiceDate ?: heuristic.invoiceDate,
            totalAmount = llm.totalAmount ?: heuristic.totalAmount,
            currency = llm.currency ?: heuristic.currency
        )
    }
}
