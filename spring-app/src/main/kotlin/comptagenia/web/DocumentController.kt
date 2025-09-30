package comptagenia.web

import comptagenia.model.BankStatementExtractionResult
import comptagenia.model.InvoiceExtractionResult
import comptagenia.service.BankStatementExtractionService
import comptagenia.service.InvoiceExtractionService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/documents")
class DocumentController(
    private val invoiceExtractionService: InvoiceExtractionService,
    private val bankStatementExtractionService: BankStatementExtractionService
) {

    @PostMapping("/invoices/extract", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun extractInvoices(@RequestParam("files") files: List<MultipartFile>): List<InvoiceExtractionResult> {
        require(files.isNotEmpty()) { "Vous devez fournir au moins un fichier" }
        return invoiceExtractionService.extract(files)
    }

    @PostMapping("/bank-statements/extract", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun extractBankStatement(@RequestParam("file") file: MultipartFile): BankStatementExtractionResult {
        return bankStatementExtractionService.extract(file)
    }
}
