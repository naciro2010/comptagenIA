package comptagenia.invoicematcher.service

import comptagenia.invoicematcher.model.BankTransaction
import comptagenia.invoicematcher.model.InvoiceData
import comptagenia.invoicematcher.model.InvoiceMatchResult
import info.debatty.java.stringsimilarity.NormalizedLevenshtein
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

@Service
class MatchingService {
    private val similarity = NormalizedLevenshtein()

    fun match(
        invoices: List<InvoiceData>,
        transactions: List<BankTransaction>,
        amountTolerance: BigDecimal,
        maxDaysDelta: Long
    ): List<InvoiceMatchResult> {
        return invoices.map { invoice ->
            val best = findBestMatch(invoice, transactions, amountTolerance, maxDaysDelta)
            InvoiceMatchResult(
                filename = invoice.filename,
                invoiceNumber = invoice.invoiceNumber,
                invoiceDate = invoice.invoiceDate,
                totalAmount = invoice.totalAmount,
                matched = best != null,
                matchScore = best?.score,
                bankDate = best?.transaction?.date,
                bankAmount = best?.transaction?.amount,
                bankDescription = best?.transaction?.description
            )
        }
    }

    private fun findBestMatch(
        invoice: InvoiceData,
        transactions: List<BankTransaction>,
        amountTolerance: BigDecimal,
        maxDaysDelta: Long
    ): MatchCandidate? {
        val invoiceAmount = invoice.totalAmount ?: return null
        val invoiceDate = invoice.invoiceDate
        val tolerance = amountTolerance.abs()

        var bestCandidate: MatchCandidate? = null
        for (transaction in transactions) {
            if (!amountsMatch(invoiceAmount, transaction.amount, tolerance)) {
                continue
            }

            val withinDate = invoiceDate == null || (!transaction.date.isBefore(invoiceDate) &&
                ChronoUnit.DAYS.between(invoiceDate, transaction.date) <= maxDaysDelta)
            if (!withinDate) {
                continue
            }

            val score = computeScore(invoice.invoiceNumber, transaction.description, invoiceAmount, transaction.amount)
            if (bestCandidate == null || score > bestCandidate.score) {
                bestCandidate = MatchCandidate(transaction, score)
            }
        }
        return bestCandidate
    }

    private fun computeScore(invoiceNumber: String?, description: String, invoiceAmount: BigDecimal, transactionAmount: BigDecimal): Double {
        var score = 0.0
        if (!invoiceNumber.isNullOrBlank()) {
            val similarityScore = similarity.similarity(invoiceNumber.lowercase(), description.lowercase()) * 100
            score = similarityScore
        }
        if (amountsMatch(invoiceAmount, transactionAmount, BigDecimal("0.0000001"))) {
            score += 5
        }
        return score
    }

    private fun amountsMatch(expected: BigDecimal, actual: BigDecimal, tolerance: BigDecimal): Boolean {
        val diff = expected.abs().subtract(actual.abs()).abs()
        return diff.compareTo(tolerance) <= 0
    }

    private data class MatchCandidate(
        val transaction: BankTransaction,
        val score: Double
    )
}
