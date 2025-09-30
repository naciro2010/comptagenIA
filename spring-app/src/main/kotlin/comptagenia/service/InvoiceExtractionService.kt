package comptagenia.service

import comptagenia.model.InvoiceExtractionResult
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class InvoiceExtractionService(
    private val ocrService: OcrService
) {
    private val invoiceNumberPatterns = listOf(
        Regex("facture\\s*(?:n[°o]|no|num(?:éro)?)\\s*[:#-]?\\s*([A-Za-z0-9\\-_/]{3,})", RegexOption.IGNORE_CASE),
        Regex("invoice\\s*(?:n[°o]|no|#)?\\s*[:#-]?\\s*([A-Za-z0-9\\-_/]{3,})", RegexOption.IGNORE_CASE),
        Regex("\\b(?:facture|invoice)\\s*[:#-]?\\s*([A-Za-z0-9\\-_/]{3,})", RegexOption.IGNORE_CASE)
    )

    private val datePatterns = listOf(
        Regex("date\\s*(?:de\\s*facture|facture|invoice)?\\s*[:#-]?\\s*([0-9]{1,2}[\\-/\\.][0-9]{1,2}[\\-/\\.][0-9]{2,4}|[0-9]{4}[\\-/\\.][0-9]{1,2}[\\-/\\.][0-9]{1,2}|\\d{1,2}\\s\\w+\\s\\d{4})", RegexOption.IGNORE_CASE)
    )

    private val totalPatterns = listOf(
        Regex("total\\s*(?:ttc)?\\s*[:#-]?\\s*([+-]?\\s*[0-9\\s\\.,]+)", RegexOption.IGNORE_CASE),
        Regex("montant\\s*(?:ttc|total)\\s*[:#-]?\\s*([+-]?\\s*[0-9\\s\\.,]+)", RegexOption.IGNORE_CASE)
    )

    private val currencyMarkers = listOf("eur", "€", "euro", "euros")

    fun extract(files: List<MultipartFile>, useOcr: Boolean = true): List<InvoiceExtractionResult> {
        return files.map { file ->
            val filename = file.originalFilename ?: file.name
            val text = if (useOcr) {
                ocrService.extractText(file.bytes, filename)
            } else {
                ""
            }
            val parsed = parseInvoice(text)
            InvoiceExtractionResult(
                filename = filename,
                invoiceNumber = parsed.invoiceNumber,
                invoiceDate = parsed.invoiceDate,
                totalAmount = parsed.totalAmount,
                currency = parsed.currency,
                rawText = text
            )
        }
    }

    private fun parseInvoice(text: String): ParsedInvoice {
        val lower = text.lowercase()
        val invoiceNumber = invoiceNumberPatterns.firstNotNullOfOrNull { regex ->
            regex.find(lower)?.groupValues?.getOrNull(1)?.trim(' ', ':', '#', '-', '/', '\\')
        }

        val invoiceDate = datePatterns.firstNotNullOfOrNull { regex ->
            regex.find(lower)?.groupValues?.getOrNull(1)?.let { ParsingUtils.parseDate(it) }
        } ?: Regex("(\\d{1,2}[-/. ]\\d{1,2}[-/. ]\\d{2,4}|\\d{4}[-/. ]\\d{1,2}[-/. ]\\d{1,2})")
            .find(lower)
            ?.groupValues?.getOrNull(1)
            ?.let { ParsingUtils.parseDate(it) }

        val totalAmount = totalPatterns.firstNotNullOfOrNull { regex ->
            regex.find(lower)?.groupValues?.getOrNull(1)?.let { ParsingUtils.parseAmount(it) }
        } ?: Regex("[+-]?\\s*[\\d\\s.,]{2,}")
            .findAll(lower)
            .mapNotNull { ParsingUtils.parseAmount(it.value) }
            .maxOrNull()

        val currency = currencyMarkers.firstOrNull { lower.contains(it) }?.let { "EUR" } ?: "EUR"

        return ParsedInvoice(invoiceNumber, invoiceDate, totalAmount, currency)
    }

    private data class ParsedInvoice(
        val invoiceNumber: String?,
        val invoiceDate: String?,
        val totalAmount: Double?,
        val currency: String?
    )
}
