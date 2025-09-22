package comptagenia.invoicematcher.controller

import comptagenia.invoicematcher.model.BankStatementFile
import comptagenia.invoicematcher.model.InvoiceFile
import comptagenia.invoicematcher.model.InvoiceSummary
import comptagenia.invoicematcher.model.MatchingResponse
import comptagenia.invoicematcher.service.BankStatementService
import comptagenia.invoicematcher.service.InvoiceExtractionService
import comptagenia.invoicematcher.service.MatchingService
import comptagenia.invoicematcher.service.XmlExportService
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

@RestController
@RequestMapping("/api")
class MatchingController(
    private val invoiceExtractionService: InvoiceExtractionService,
    private val bankStatementService: BankStatementService,
    private val matchingService: MatchingService,
    private val xmlExportService: XmlExportService
) {

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

        val extractedInvoices = invoiceExtractionService.extractInvoices(invoiceFiles, useLlm = useLlm, llmModel = llmModel)
        val transactions = bankStatementService.parse(bankStatementFile)
        val matches = matchingService.match(extractedInvoices, transactions, amountTolerance, dateToleranceDays)
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

        return MatchingResponse(
            invoices = invoiceSummaries,
            matches = matches,
            xmlExport = xml
        )
    }
}
