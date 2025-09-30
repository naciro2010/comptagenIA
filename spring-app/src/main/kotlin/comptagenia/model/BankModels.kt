package comptagenia.model

data class BankTransaction(
    val date: String?,
    val description: String?,
    val amount: Double?
)

data class BankStatementExtractionResult(
    val filename: String,
    val transactions: List<BankTransaction>,
    val rawText: String?
)
