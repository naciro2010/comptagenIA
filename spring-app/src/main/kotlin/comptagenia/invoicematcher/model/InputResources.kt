package comptagenia.invoicematcher.model

data class InvoiceFile(
    val filename: String,
    val bytes: ByteArray
)

data class BankStatementFile(
    val filename: String,
    val bytes: ByteArray
)
