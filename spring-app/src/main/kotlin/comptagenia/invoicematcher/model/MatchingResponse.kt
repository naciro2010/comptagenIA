package comptagenia.invoicematcher.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Lightweight summary exposed via the REST API for clients that do not need the raw text.
 */
data class InvoiceSummary(
    val filename: String,
    val invoiceNumber: String?,
    val invoiceDate: LocalDate?,
    val totalAmount: BigDecimal?,
    val currency: String?
)

data class MatchingResponse(
    val invoices: List<InvoiceSummary>,
    val matches: List<InvoiceMatchResult>,
    val xmlExport: String
)
