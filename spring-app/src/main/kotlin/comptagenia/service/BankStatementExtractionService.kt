package comptagenia.service

import comptagenia.model.BankStatementExtractionResult
import comptagenia.model.BankTransaction
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.time.format.DateTimeFormatter

@Service
class BankStatementExtractionService(
    private val ocrService: OcrService
) {
    fun extract(file: MultipartFile): BankStatementExtractionResult {
        val filename = file.originalFilename ?: file.name
        val lower = filename.lowercase()
        val bytes = file.bytes
        val (transactions, rawText) = when {
            lower.endsWith(".pdf") -> parsePdf(bytes)
            OcrService.SUPPORTED_IMAGE_FORMATS.any { lower.endsWith(it) } -> parseImage(bytes, filename)
            lower.endsWith(".xlsx") || lower.endsWith(".xls") -> parseExcel(bytes)
            lower.endsWith(".csv") -> parseCsv(bytes)
            else -> parseCsv(bytes)
        }

        return BankStatementExtractionResult(filename, transactions, rawText)
    }

    private fun parsePdf(bytes: ByteArray): Pair<List<BankTransaction>, String> {
        val text = PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            stripper.getText(document)
        }
        val transactions = parseTextLines(text)
        return transactions to text
    }

    private fun parseImage(bytes: ByteArray, filename: String): Pair<List<BankTransaction>, String> {
        val text = ocrService.extractText(bytes, filename)
        val transactions = parseTextLines(text)
        return transactions to text
    }

    private fun parseCsv(bytes: ByteArray): Pair<List<BankTransaction>, String?> {
        val possibleDelimiters = listOf(',', ';', '\t', '|')
        val charsetCandidates = listOf(Charset.forName("UTF-8"), Charset.forName("ISO-8859-1"))
        for (charset in charsetCandidates) {
            for (delimiter in possibleDelimiters) {
                val result = runCatching {
                    CSVParser(
                        InputStreamReader(ByteArrayInputStream(bytes), charset),
                        CSVFormat.DEFAULT.builder()
                            .setDelimiter(delimiter)
                            .setIgnoreEmptyLines(true)
                            .setTrim(true)
                            .setSkipHeaderRecord(false)
                            .build()
                    ).use { parser ->
                        val records = parser.records
                        if (records.isEmpty()) return@use Pair(emptyList<BankTransaction>(), null)
                        val header = if (parser.headerNames.isNotEmpty()) {
                            parser.headerNames.toList()
                        } else {
                            records.first().map { it ?: "" }
                        }
                        val columnMap = findColumnMap(header) ?: return@use Pair(emptyList<BankTransaction>(), null)
                        val dataRecords = if (parser.headerNames.isNotEmpty()) records else records.drop(1)
                        val transactions = dataRecords.mapNotNull { record ->
                            toTransaction(
                                record.getOrNull(columnMap.date),
                                record.getOrNull(columnMap.description),
                                record.getOrNull(columnMap.amount),
                                record.getOrNull(columnMap.debit),
                                record.getOrNull(columnMap.credit)
                            )
                        }
                        if (transactions.isNotEmpty()) {
                            Pair(transactions, null)
                        } else {
                            Pair(emptyList(), null)
                        }
                    }
                }.getOrNull()
                if (result != null && result.first.isNotEmpty()) {
                    return result
                }
            }
        }
        return emptyList<BankTransaction>() to null
    }

    private fun parseExcel(bytes: ByteArray): Pair<List<BankTransaction>, String?> {
        val transactions = mutableListOf<BankTransaction>()
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val headerRow = findHeaderRow(sheet) ?: return emptyList<BankTransaction>() to null
            val headers = headerRow.second
            val columnMap = findColumnMap(headers)
                ?: return emptyList<BankTransaction>() to null
            for (rowIndex in headerRow.first + 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val transaction = toTransaction(
                    getCellString(row, columnMap.date),
                    getCellString(row, columnMap.description),
                    getCellString(row, columnMap.amount),
                    getCellString(row, columnMap.debit),
                    getCellString(row, columnMap.credit)
                )
                if (transaction != null) {
                    transactions += transaction
                }
            }
        }
        return transactions to null
    }

    private fun parseTextLines(text: String): List<BankTransaction> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val dateRegex = Regex("^(\\d{1,2}[\\-/]\\d{1,2}[\\-/]\\d{2,4})\\s+(.*)")
        val amountRegex = Regex("([+-]?\\s*\\d[\\d\\s.,]*)$")
        val transactions = mutableListOf<BankTransaction>()
        for (line in lines) {
            val dateMatch = dateRegex.find(line) ?: continue
            val dateStr = ParsingUtils.parseDate(dateMatch.groupValues[1]) ?: continue
            val rest = dateMatch.groupValues[2]
            val amountMatch = amountRegex.find(rest) ?: continue
            val amountStr = amountMatch.groupValues[1]
            val description = rest.substring(0, amountMatch.range.first).trim()
            val amount = ParsingUtils.parseAmount(amountStr) ?: continue
            transactions += BankTransaction(dateStr, description, amount)
        }
        return transactions
    }

    private fun toTransaction(
        rawDate: String?,
        rawDescription: String?,
        rawAmount: String?,
        rawDebit: String?,
        rawCredit: String?
    ): BankTransaction? {
        val date = ParsingUtils.parseDate(rawDate)
        val description = rawDescription?.takeIf { it.isNotBlank() }
        val amount = when {
            !rawAmount.isNullOrBlank() -> ParsingUtils.parseAmount(rawAmount)
            !rawDebit.isNullOrBlank() -> ParsingUtils.parseAmount(rawDebit)?.let { -kotlin.math.abs(it) }
            !rawCredit.isNullOrBlank() -> ParsingUtils.parseAmount(rawCredit)?.let { kotlin.math.abs(it) }
            else -> null
        }
        if (date == null || amount == null) return null
        return BankTransaction(date, description, amount)
    }

    private data class ColumnMap(
        val date: Int,
        val description: Int,
        val amount: Int?,
        val debit: Int?,
        val credit: Int?
    )

    private fun findColumnMap(headers: List<String>): ColumnMap? {
        val normalized = headers.map { ParsingUtils.normalizeHeader(it) }
        fun findIndex(vararg tokens: String): Int? {
            val idx = normalized.indexOfFirst { header -> tokens.any { header.contains(it) } }
            return if (idx >= 0) idx else null
        }

        val dateIdx = findIndex("date") ?: return null
        val descIdx = findIndex("description", "libelle", "label", "narration", "details") ?: return null
        val amountIdx = findIndex("montant", "amount", "solde")
        val debitIdx = findIndex("debit", "retrait")
        val creditIdx = findIndex("credit", "versement", "depot")
        if (amountIdx != null) {
            return ColumnMap(dateIdx, descIdx, amountIdx, null, null)
        }
        if (debitIdx != null || creditIdx != null) {
            return ColumnMap(dateIdx, descIdx, null, debitIdx, creditIdx)
        }
        return null
    }

    private fun findHeaderRow(sheet: org.apache.poi.ss.usermodel.Sheet): Pair<Int, List<String>>? {
        for (rowIndex in 0..minOf(sheet.lastRowNum, 20)) {
            val row = sheet.getRow(rowIndex) ?: continue
            val values = row.cellIterator().asSequence().map { cell -> getCellString(cell) }.toList()
            val nonEmpty = values.count { it.isNotBlank() }
            if (nonEmpty >= 2) {
                return rowIndex to values
            }
        }
        return null
    }

    private fun getCellString(row: Row, index: Int?): String? {
        if (index == null || index < 0) return null
        val cell = row.getCell(index) ?: return null
        val value = getCellString(cell)
        return value.takeIf { it.isNotBlank() }
    }

    private fun getCellString(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    DateTimeFormatter.ISO_LOCAL_DATE.format(cell.localDateTimeCellValue.toLocalDate())
                } else {
                    cell.numericCellValue.toBigDecimal().stripTrailingZeros().toPlainString()
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                CellType.STRING -> cell.stringCellValue
                CellType.NUMERIC -> cell.numericCellValue.toBigDecimal().stripTrailingZeros().toPlainString()
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                else -> ""
            }
            else -> ""
        }
    }

    private fun CSVRecord.getOrNull(index: Int?): String? {
        if (index == null || index < 0 || index >= size()) return null
        return this.get(index)
    }
}
