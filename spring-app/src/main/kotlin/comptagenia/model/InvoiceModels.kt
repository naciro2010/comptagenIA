package comptagenia.model

data class InvoiceExtractionResult(
    val filename: String,
    val invoiceNumber: String?,
    val invoiceDate: String?,
    val totalAmount: Double?,
    val currency: String?,
    val rawText: String
)

