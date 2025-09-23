package comptagenia.invoicematcher.service

import comptagenia.invoicematcher.model.BankStatementFile
import comptagenia.invoicematcher.model.BankTransaction
import comptagenia.invoicematcher.util.ParsingUtils
import comptagenia.invoicematcher.util.TextUtils
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.QuoteMode
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal

@Service
class BankStatementService(
    private val pdfTextExtractor: PdfTextExtractor,
    @Autowired(required = false) private val ollamaClient: OllamaClient? = null
) {

    private val logger = LoggerFactory.getLogger(BankStatementService::class.java)
    private val similarity = JaroWinklerSimilarity()

    fun parse(
        file: BankStatementFile,
        useLlm: Boolean = false,
        llmModel: String? = null
    ): List<BankTransaction> {
        val extension = file.filename.substringAfterLast('.', "").lowercase()
        logger.info("Lecture du relevé {} (extension: {})", file.filename, extension)

        val llmEnabled = useLlm && (ollamaClient?.isEnabled() == true)

        val transactions = when (extension) {
            "csv" -> parseCsv(file, llmEnabled, llmModel)
            "xlsx", "xls" -> parseExcel(file, llmEnabled, llmModel)
            "pdf" -> parsePdf(file, llmEnabled, llmModel)
            else -> throw IllegalArgumentException("Format non pris en charge pour le relevé ${file.filename}. (CSV, XLSX, XLS, PDF)")
        }
        if (transactions.isEmpty()) {
            logger.warn("Aucune transaction détectée dans {}", file.filename)
            throw IllegalArgumentException(
                "Aucune transaction détectée dans le relevé ${file.filename}. " +
                    "Vérifiez le séparateur ou la présence des colonnes date/montant."
            )
        }
        val sorted = transactions.sortedBy { it.date }
        logger.info("{} transactions détectées pour {}", sorted.size, file.filename)
        return sorted
    }

    private fun parseCsv(file: BankStatementFile, useLlm: Boolean, llmModel: String?): List<BankTransaction> {
        val separators = listOf(',', ';', '\t', '|')
        for (separator in separators) {
            parseCsvWithSeparator(file, separator, useLlm, llmModel)?.let {
                logger.info(
                    "Relevé {} lu avec le séparateur '{}' ({} lignes)",
                    file.filename,
                    separator,
                    it.size
                )
                return it
            }
        }
        throw IllegalArgumentException(
            "Impossible de lire le fichier CSV ${file.filename}. " +
                "Essayez d'enregistrer le fichier en UTF-8 ou de préciser le séparateur."
        )
    }

    private fun parseCsvWithSeparator(
        file: BankStatementFile,
        separator: Char,
        useLlm: Boolean,
        llmModel: String?
    ): List<BankTransaction>? {
        val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(file.bytes)))
        val format = CSVFormat.Builder.create()
            .setDelimiter(separator)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .setQuoteMode(QuoteMode.MINIMAL)
            .setHeader()
            .build()
        val parser = CSVParser(reader, format)
        if (parser.headerNames.isEmpty()) {
            logger.debug("CSV {} : aucune entête détectée avec le séparateur '{}'", file.filename, separator)
            return null
        }
        val headerNames = parser.headerNames
        val sampleRows = parser.records.take(15).map { record ->
            val map = linkedMapOf<String, String?>()
            headerNames.forEach { header -> map[header] = record.get(header) }
            map
        }
        val columnMapping = detectColumnsWithFallback(headerNames, sampleRows, useLlm, llmModel)
        val transactions = parser.records.mapNotNull { record ->
            val row = headerNames.associateWith { header -> record.get(header) }
            mapRecord(row, columnMapping)
        }
        logger.debug("CSV {} : {} transactions après mapping", file.filename, transactions.size)
        return transactions.takeIf { it.isNotEmpty() }
    }

    private fun parseExcel(file: BankStatementFile, useLlm: Boolean, llmModel: String?): List<BankTransaction> {
        ByteArrayInputStream(file.bytes).use { input ->
            WorkbookFactory.create(input).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val headerRow = sheet.findHeaderRow() ?: return emptyList()
                val headers = headerRow.asSequence().map { cell -> cellAsString(cell) ?: "" }.toList()
                val sampleRows = mutableListOf<Map<String, String?>>()
                for (row in sheet) {
                    if (row.rowNum <= headerRow.rowNum) continue
                    val rowMap = headers.mapIndexed { index, header ->
                        header to cellAsString(row.getCell(index))
                    }.toMap()
                    sampleRows.add(rowMap)
                    if (sampleRows.size >= 15) break
                }
                val columnMapping = detectColumnsWithFallback(headers, sampleRows, useLlm, llmModel)

                val transactions = mutableListOf<BankTransaction>()
                for (row in sheet) {
                    if (row.rowNum <= headerRow.rowNum) continue
                    val rowMap = headers.mapIndexed { index, header ->
                        header to cellAsString(row.getCell(index))
                    }.toMap()
                    mapRecord(rowMap, columnMapping)?.let { transactions.add(it) }
                }
                logger.info(
                    "Relevé {} (Excel) : {} transactions extraites sur {} lignes",
                    file.filename,
                    transactions.size,
                    sheet.physicalNumberOfRows
                )
                return transactions
            }
        }
    }

    private fun parsePdf(file: BankStatementFile, useLlm: Boolean, llmModel: String?): List<BankTransaction> {
        val text = pdfTextExtractor.extractText(file.bytes)
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val datePattern = Regex("^(\\d{1,2}[\\-/]\\d{1,2}[\\-/]\\d{2,4})\\s+(.*)")
        val amountPattern = Regex("([+-]?\\s*\\d[\\d\\s\\.,]*)$")
        val transactions = mutableListOf<BankTransaction>()

        for (line in lines) {
            val dateMatch = datePattern.find(line) ?: continue
            val dateStr = dateMatch.groupValues[1]
            val rest = dateMatch.groupValues[2]
            val amountMatch = amountPattern.find(rest) ?: continue
            val amountStr = amountMatch.groupValues[1]
            val description = rest.substring(0, amountMatch.range.first).trim()

            val date = ParsingUtils.parseDate(dateStr)
            val amount = ParsingUtils.parseAmount(amountStr)
            if (date != null && amount != null) {
                transactions.add(BankTransaction(date, description, amount))
            }
        }
        var result = transactions

        if (transactions.isEmpty() && useLlm) {
            val llmTransactions = ollamaClient?.extractBankTransactions(text, llmModel) ?: emptyList()
            if (llmTransactions.isNotEmpty()) {
                logger.info("Relevé {} (PDF) : {} transactions extraites via LLM", file.filename, llmTransactions.size)
                result = llmTransactions.mapNotNull { tx ->
                    val date = ParsingUtils.parseDate(tx.date)
                    val amount = tx.amount?.let { ParsingUtils.parseAmount(it) }
                    if (date != null && amount != null) {
                        BankTransaction(date, tx.description, amount)
                    } else null
                }.toMutableList()
            }
        }

        logger.info(
            "Relevé {} (PDF) : {} transactions extraites sur {} lignes de texte",
            file.filename,
            result.size,
            lines.size
        )
        return result
    }

    private fun mapRecord(row: Map<String, String?>, columns: DetectedColumns): BankTransaction? {
        val date = ParsingUtils.parseDate(row[columns.dateHeader]) ?: return null
        val description = row[columns.descriptionHeader]?.trim().takeUnless { it.isNullOrBlank() } ?: return null

        val amount = columns.amountHeader?.let { header ->
            ParsingUtils.parseAmount(row[header])
        } ?: run {
            val debit = columns.debitHeader?.let { header -> ParsingUtils.parseAmount(row[header]) }
            val credit = columns.creditHeader?.let { header -> ParsingUtils.parseAmount(row[header]) }
            when {
                debit != null && debit.compareTo(BigDecimal.ZERO) != 0 -> debit.abs().negate()
                credit != null && credit.compareTo(BigDecimal.ZERO) != 0 -> credit.abs()
                else -> null
            }
        }

        return amount?.let { BankTransaction(date, description, it) }
    }

    private fun detectColumnsWithFallback(
        headers: List<String>,
        sampleRows: List<Map<String, String?>>, 
        useLlm: Boolean,
        llmModel: String?
    ): DetectedColumns {
        return try {
            detectColumns(headers)
        } catch (ex: IllegalArgumentException) {
            if (useLlm) {
                val inference = ollamaClient?.inferBankColumns(headers, sampleRows, llmModel)
                if (inference != null) {
                    val date = matchColumnName(inference.dateColumn, headers)
                    val description = matchColumnName(inference.descriptionColumn, headers)
                    val amount = matchColumnName(inference.amountColumn, headers)
                    val debit = matchColumnName(inference.debitColumn, headers)
                    val credit = matchColumnName(inference.creditColumn, headers)
                    if (date != null && description != null && (amount != null || debit != null || credit != null)) {
                        logger.info(
                            "Colonnes détectées via LLM: date={}, description={}, amount={}, debit={}, credit={}",
                            date, description, amount, debit, credit
                        )
                        return DetectedColumns(
                            dateHeader = date,
                            descriptionHeader = description,
                            amountHeader = amount,
                            debitHeader = debit,
                            creditHeader = credit
                        )
                    } else {
                        logger.warn("LLM n'a pas fourni de mapping exploitable: {}", inference)
                    }
                } else {
                    logger.warn("LLM n'a pas pu inférer les colonnes")
                }
            }
            throw ex
        }
    }

    private fun matchColumnName(suggested: String?, headers: List<String>): String? {
        if (suggested.isNullOrBlank()) return null
        val normalizedHeaders = headers.associateWith { TextUtils.standardizeHeader(it) }
        val target = TextUtils.standardizeHeader(suggested)
        normalizedHeaders.forEach { (header, norm) ->
            if (norm == target) return header
        }
        val best = normalizedHeaders.maxByOrNull { (_, norm) -> similarity.apply(norm, target) }
        return best?.let { if (similarity.apply(it.value, target) >= 0.75) it.key else null }
    }

    private fun detectColumns(headers: List<String>): DetectedColumns {
        val normalized = headers.map { TextUtils.standardizeHeader(it) }

        fun find(vararg candidates: String): String? {
            candidates.forEach { candidate ->
                val idx = normalized.indexOf(candidate)
                if (idx >= 0) return headers[idx]
            }
            var bestScore = 0.0
            var bestHeader: String? = null
            for ((idx, header) in normalized.withIndex()) {
                for (candidate in candidates) {
                    val score = similarity.apply(header, candidate)
                    if (score > bestScore) {
                        bestScore = score
                        bestHeader = headers[idx]
                    }
                }
            }
            return bestHeader?.takeIf { bestScore >= 0.85 }
        }

        val dateHeader = find("date", "date operation", "operation date", "transaction date", "booking date", "valeur")
            ?: throw IllegalArgumentException("Impossible de détecter la colonne date.")
        val descriptionHeader = find("description", "libelle", "label", "details", "detail")
            ?: throw IllegalArgumentException("Impossible de détecter la colonne description.")
        val amountHeader = find("montant", "amount", "montant eur", "solde", "debit/credit", "debit credit")
        val debitHeader = find("debit", "retrait")
        val creditHeader = find("credit", "versement")

        if (amountHeader == null && debitHeader == null && creditHeader == null) {
            throw IllegalArgumentException("Impossible de détecter la colonne montant.")
        }

        return DetectedColumns(
            dateHeader = dateHeader,
            descriptionHeader = descriptionHeader,
            amountHeader = amountHeader,
            debitHeader = debitHeader,
            creditHeader = creditHeader
        )
    }

    private fun cellAsString(cell: Cell?): String? {
        if (cell == null) return null
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> cell.numericCellValue.toString()
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                CellType.STRING -> cell.stringCellValue
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                else -> cell.numericCellValue.toString()
            }
            else -> cell.toString()
        }
    }

    private fun Iterable<Row>.findHeaderRow(): Row? = this.firstOrNull { row ->
        row.asSequence().count { !cellAsString(it).isNullOrBlank() } >= 2
    }

    private fun Row.asSequence(): Sequence<Cell> = sequence {
        for (index in 0 until lastCellNum.coerceAtLeast(0)) {
            yield(getCell(index))
        }
    }

    private data class DetectedColumns(
        val dateHeader: String,
        val descriptionHeader: String,
        val amountHeader: String?,
        val debitHeader: String?,
        val creditHeader: String?
    )
}
