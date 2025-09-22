package comptagenia.invoicematcher.service

import comptagenia.invoicematcher.model.BankTransaction
import comptagenia.invoicematcher.model.InvoiceData
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class MatchingServiceTest {
    private val service = MatchingService()

    @Test
    fun `matches invoice with closest transaction`() {
        val invoice = InvoiceData(
            filename = "invoice.pdf",
            invoiceNumber = "INV-123",
            invoiceDate = LocalDate.of(2024, 5, 10),
            totalAmount = BigDecimal("100.00"),
            currency = "EUR",
            rawText = ""
        )
        val transactions = listOf(
            BankTransaction(LocalDate.of(2024, 5, 12), "Paiement INV-123", BigDecimal("100.00")),
            BankTransaction(LocalDate.of(2024, 5, 12), "Autre paiement", BigDecimal("50.00"))
        )

        val results = service.match(listOf(invoice), transactions, BigDecimal("0.02"), 30)
        val result = results.first()
        assertTrue(result.matched)
        assertTrue((result.matchScore ?: 0.0) > 10.0)
    }
}
