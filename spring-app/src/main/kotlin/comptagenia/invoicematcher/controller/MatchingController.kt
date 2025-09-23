package comptagenia.invoicematcher.controller

import comptagenia.invoicematcher.model.BankStatementFile
import comptagenia.invoicematcher.model.InvoiceFile
import comptagenia.invoicematcher.model.InvoiceSummary
import comptagenia.invoicematcher.model.MatchingResponse
import comptagenia.invoicematcher.model.BankTransactionSummary
import comptagenia.invoicematcher.service.BankStatementService
import comptagenia.invoicematcher.service.InvoiceExtractionService
import comptagenia.invoicematcher.service.MatchingService
import comptagenia.invoicematcher.service.XmlExportService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@RestController
@RequestMapping("/api")
class MatchingController(
    private val invoiceExtractionService: InvoiceExtractionService,
    private val bankStatementService: BankStatementService,
    private val matchingService: MatchingService,
    private val xmlExportService: XmlExportService
) {

    private val logger = LoggerFactory.getLogger(MatchingController::class.java)

    @PostMapping(
        "/matching/run",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    fun runMatching(
        @RequestPart("invoices") invoices: List<MultipartFile>,
        @RequestPart("bankStatement") bankStatement: MultipartFile,
        @RequestParam(defaultValue = "0.02") amountTolerance: BigDecimal,
        @RequestParam(defaultValue = "90") dateToleranceDays: Long,
        @RequestParam(defaultValue = "false") useLlm: Boolean,
        @RequestParam(required = false) llmModel: String?
    ): MatchingResponse {
        if (invoices.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Au moins une facture est requise.")
        }
        if (bankStatement.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Le relevé bancaire est requis.")
        }

        val invoiceFiles = invoices.map { file ->
            InvoiceFile(file.originalFilename ?: "invoice.pdf", file.bytes)
        }
        val bankStatementFile = BankStatementFile(bankStatement.originalFilename ?: "statement", bankStatement.bytes)

        logger.info(
            "Matching lancé: {} facture(s), relevé {}, tolérance montant={}, tolérance jours={}, LLM={} ({})",
            invoiceFiles.size,
            bankStatementFile.filename,
            amountTolerance,
            dateToleranceDays,
            useLlm,
            llmModel ?: "défaut"
        )

        val extractedInvoices = invoiceExtractionService.extractInvoices(invoiceFiles, useLlm = useLlm, llmModel = llmModel)
        logger.info("Extraction factures terminée: {} résultat(s)", extractedInvoices.size)

        val transactions = bankStatementService.parse(
            bankStatementFile,
            useLlm = useLlm,
            llmModel = llmModel
        )
        val matches = matchingService.match(extractedInvoices, transactions, amountTolerance, dateToleranceDays)
        logger.info("Matching terminé: {} transaction(s) relevé, {} ligne(s) de sortie", transactions.size, matches.size)

        val matchedKeys = matches
            .filter { it.matched && it.bankDate != null && it.bankAmount != null && !it.bankDescription.isNullOrBlank() }
            .map { keyFor(it.bankDate!!, it.bankAmount!!, it.bankDescription!!) }
            .toSet()

        val transactionSummaries = transactions.map { tx ->
            BankTransactionSummary(
                date = tx.date,
                description = tx.description,
                amount = tx.amount,
                matched = matchedKeys.contains(keyFor(tx.date, tx.amount, tx.description))
            )
        }
        val xml = xmlExportService.invoicesToXml(extractedInvoices)

        val invoiceSummaries = extractedInvoices.map {
            InvoiceSummary(
                filename = it.filename,
                invoiceNumber = it.invoiceNumber,
                invoiceDate = it.invoiceDate,
                totalAmount = it.totalAmount,
                currency = it.currency
            )
        }

        val response = MatchingResponse(
            invoices = invoiceSummaries,
            matches = matches,
            transactions = transactionSummaries,
            xmlExport = xml
        )
        logger.info(
            "Réponse prête: {} factures, {} appariements trouvés, {} transactions ({} utilisées)",
            invoiceSummaries.size,
            matches.count { it.matched },
            transactionSummaries.size,
            transactionSummaries.count { it.matched }
        )
        return response
    }

    private fun keyFor(date: LocalDate, amount: BigDecimal, description: String): String {
        val normalizedAmount = amount.setScale(2, RoundingMode.HALF_UP)
        val normalizedDescription = description.lowercase().filterNot { it.isWhitespace() }
        return "$date|$normalizedAmount|$normalizedDescription"
    }
}
