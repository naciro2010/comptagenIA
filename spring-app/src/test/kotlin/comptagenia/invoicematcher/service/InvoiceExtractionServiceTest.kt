package comptagenia.invoicematcher.service

import comptagenia.invoicematcher.model.InvoiceFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class InvoiceExtractionServiceTest {

    private val sampleText = """
        Facture N° INV-2024-001
        Date de facture : 05/01/2024
        Total TTC : 1 234,56 EUR
    """.trimIndent()

    private val service = InvoiceExtractionService(object : PdfTextExtractor() {
        override fun extractText(bytes: ByteArray): String = sampleText
    })

    @Test
    fun `extracts invoice number date and total`() {
        val invoice = service.extractInvoices(listOf(InvoiceFile("facture.pdf", byteArrayOf()))).first()
        assertEquals("INV-2024-001", invoice.invoiceNumber)
        assertEquals(2024, invoice.invoiceDate?.year)
        assertEquals("EUR", invoice.currency)
        assertNotNull(invoice.totalAmount)
    }
}
