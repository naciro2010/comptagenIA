package comptagenia.invoicematcher.service

import comptagenia.invoicematcher.model.BankStatementFile
import comptagenia.invoicematcher.model.BankTransaction
import comptagenia.invoicematcher.util.ParsingUtils
import comptagenia.invoicematcher.util.TextUtils
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.QuoteMode
import org.apache.commons.lang3.StringUtils
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.math.BigDecimal

@Service
class BankStatementService(
    private val pdfTextExtractor: PdfTextExtractor
) {

    fun parse(file: BankStatementFile): List<BankTransaction> {
        val extension = file.filename.substringAfterLast('.', "").lowercase()
        val transactions = when (extension) {
            "csv" -> parseCsv(file)
            "xlsx", "xls" -> parseExcel(file)
            "pdf" -> parsePdf(file)
            else -> throw IllegalArgumentException("Unsupported bank statement format: ${'$'}{file.filename}")
        }
        if (transactions.isEmpty()) {
            throw IllegalArgumentException("Aucune transaction détectée dans le relevé ${'$'}{file.filename}.")
        }
        return transactions.sortedBy { it.date }
    }

    private fun parseCsv(file: BankStatementFile): List<BankTransaction> {
        val separators = listOf(',', ';', '\t', '|')
        for (separator in separators) {
            parseCsvWithSeparator(file, separator)?.let { return it }
        }
        throw IllegalArgumentException("Impossible de lire le fichier CSV: ${'$'}{file.filename}")
    }

    private fun parseCsvWithSeparator(file: BankStatementFile, separator: Char): List<BankTransaction>? {
        val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(file.bytes)))
        val format = CSVFormat.Builder.create()
            .setDelimiter(separator)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .setQuoteMode(QuoteMode.MINIMAL)
            .setHeader()
            .build()
        val parser = CSVParser(reader, format)
        if (parser.headerNames.isEmpty()) return null
        val columnMapping = detectColumns(parser.headerNames)
        val transactions = parser.records.mapNotNull { record ->
            val row = record.toMap()
            mapRecord(row, columnMapping)
        }
        return transactions.takeIf { it.isNotEmpty() }
    }

    private fun parseExcel(file: BankStatementFile): List<BankTransaction> {
        ByteArrayInputStream(file.bytes).use { input ->
            WorkbookFactory.create(input).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val headerRow = sheet.findHeaderRow() ?: return emptyList()
                val headers = headerRow.asSequence().map { cell -> cellAsString(cell) ?: "" }.toList()
                val columnMapping = detectColumns(headers)

                val transactions = mutableListOf<BankTransaction>()
                for (row in sheet) {
                    if (row.rowNum <= headerRow.rowNum) continue
                    val rowMap = headers.mapIndexed { index, header ->
                        header to cellAsString(row.getCell(index))
                    }.toMap()
                    mapRecord(rowMap, columnMapping)?.let { transactions.add(it) }
                }
                return transactions
            }
        }
    }

    private fun parsePdf(file: BankStatementFile): List<BankTransaction> {
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
        return transactions
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
                    val score = org.apache.commons.text.similarity.JaroWinklerSimilarity().apply { }.
                        apply(header, candidate)
                    if (score != null && score > bestScore) {
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
