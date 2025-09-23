package comptagenia.invoicematcher.service

import comptagenia.invoicematcher.model.InvoiceData
import comptagenia.invoicematcher.model.InvoiceFile
import comptagenia.invoicematcher.util.ParsingUtils
import comptagenia.invoicematcher.util.TextUtils
import org.slf4j.LoggerFactory
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
    private val sectionStartPattern = Regex("^(?:re[çc]u\\s*n[°o]|facture|invoice)\\b", RegexOption.IGNORE_CASE)
    private val logger = LoggerFactory.getLogger(InvoiceExtractionService::class.java)

    fun extractInvoices(
        files: List<InvoiceFile>,
        useLlm: Boolean = false,
        llmModel: String? = null
    ): List<InvoiceData> {
        val results = mutableListOf<InvoiceData>()
        files.forEach { file ->
            val text = pdfTextExtractor.extractText(file.bytes)
            val sections = splitIntoSections(text)
            logger.info(
                "Extraction factures: fichier={} sections_detectees={} (LLM={})",
                file.filename,
                sections.size,
                useLlm
            )

            sections.forEachIndexed { index, section ->
                var parsed = heuristicParse(section)
                val filenameLabel = if (sections.size > 1) {
                    "${file.filename}#${index + 1}"
                } else {
                    file.filename
                }

                if (useLlm && ollamaClient?.isEnabled() == true) {
                    logger.info(
                        "Appel Ollama pour la section {} de {} (modele={})",
                        index + 1,
                        file.filename,
                        llmModel ?: "defaut"
                    )
                    val enriched = ollamaClient.extractInvoiceFields(section, llmModel)
                    parsed = mergeWithLlm(parsed, enriched)
                }

                val supplier = parsed.supplierName ?: inferSupplierName(section)
                val customer = parsed.customerName ?: inferCustomerName(section, supplier)
                val header = parsed.headerSnippet ?: buildHeaderSnippet(section)

                parsed = parsed.copy(
                    filename = filenameLabel,
                    rawText = section,
                    supplierName = supplier,
                    customerName = customer,
                    headerSnippet = header
                )

                results.add(parsed)
                logger.info(
                    "Facture détectée: fichier={} numero={} date={} montant={} fournisseur={} client={}",
                    filenameLabel,
                    parsed.invoiceNumber,
                    parsed.invoiceDate,
                    parsed.totalAmount,
                    parsed.supplierName,
                    parsed.customerName
                )
            }

            if (sections.isEmpty()) {
                logger.warn("Aucune section détectée dans {} (texte long={})", file.filename, text.length)
            }
        }
        return results
    }

    private fun heuristicParse(text: String): InvoiceData {
        val normalized = TextUtils.stripAccents(text)
        val lowered = normalized.lowercase()

        val invoiceNumber = findInvoiceNumber(normalized)
        val invoiceDate = findInvoiceDate(lowered)
        val totalAmount = findTotalAmount(lowered)
        val supplier = inferSupplierName(text)
        val customer = inferCustomerName(text, supplier)
        val header = buildHeaderSnippet(text)
        val currency = findCurrency(lowered)

        return InvoiceData(
            filename = "",
            invoiceNumber = invoiceNumber,
            invoiceDate = invoiceDate,
            totalAmount = totalAmount,
            currency = currency ?: "EUR",
            rawText = text,
            supplierName = supplier,
            customerName = customer,
            headerSnippet = header
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

    private fun splitIntoSections(text: String): List<String> {
        val normalized = text.replace("\r", "").trim()
        if (normalized.isEmpty()) return emptyList()

        val lines = normalized.lines()
        val sections = mutableListOf<StringBuilder>()
        var current = StringBuilder()

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                sections.add(current)
                current = StringBuilder()
            }
        }

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (sectionStartPattern.containsMatchIn(line) && current.isNotEmpty()) {
                flushCurrent()
            }
            current.append(line).append('\n')
        }
        flushCurrent()

        val cleaned = sections.map { it.toString().trim() }.filter { it.isNotEmpty() }
        val result = if (cleaned.isEmpty()) listOf(normalized) else cleaned

        logger.debug(
            "Découpage texte facture: sections={} longueurs={} premiers_mots={}",
            result.size,
            result.map { it.length },
            result.map { it.lines().firstOrNull()?.take(80) ?: "" }
        )
        return result
    }

    private fun inferSupplierName(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.firstOrNull { isLikelyEntityLine(it) }
    }

    private fun inferCustomerName(text: String, supplier: String?): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val supplierNormalized = supplier?.lowercase()
        return lines.drop(1).firstOrNull { line ->
            if (!isLikelyEntityLine(line)) return@firstOrNull false
            val lower = line.lowercase()
            if (supplierNormalized != null && lower.contains(supplierNormalized)) return@firstOrNull false
            !lower.contains("facture") && !lower.contains("reçu") && !lower.contains("invoice")
        }
    }

    private fun buildHeaderSnippet(text: String): String {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .joinToString(separator = " | ")
    }

    private fun isLikelyEntityLine(line: String): Boolean {
        if (line.length < 3) return false
        val alphaCount = line.count { it.isLetter() }
        if (alphaCount == 0) return false
        val upperCount = line.count { it.isLetter() && it.isUpperCase() }
        val ratio = upperCount.toDouble() / alphaCount
        return ratio >= 0.6
    }
}
