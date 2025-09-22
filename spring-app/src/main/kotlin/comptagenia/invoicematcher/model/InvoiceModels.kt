package comptagenia.invoicematcher.model

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Represents the main structured fields extracted from an invoice PDF.
 */
data class InvoiceData(
    val filename: String,
    val invoiceNumber: String?,
    val invoiceDate: LocalDate?,
    val totalAmount: BigDecimal?,
    val currency: String?,
    val rawText: String
)

/**
 * Represents a single bank transaction from a statement.
 */
data class BankTransaction(
    val date: LocalDate,
    val description: String,
    val amount: BigDecimal
)

/**
 * Matching information for a single invoice against the bank statement.
 */
data class InvoiceMatchResult(
    val filename: String,
    val invoiceNumber: String?,
    val invoiceDate: LocalDate?,
    val totalAmount: BigDecimal?,
    val matched: Boolean,
    val matchScore: Double?,
    val bankDate: LocalDate?,
    val bankAmount: BigDecimal?,
    val bankDescription: String?
)
