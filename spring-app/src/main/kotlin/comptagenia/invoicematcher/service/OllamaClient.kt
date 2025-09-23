package comptagenia.invoicematcher.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import comptagenia.invoicematcher.util.ParsingUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.RestClientException
import org.springframework.web.client.HttpClientErrorException
import java.math.BigDecimal
import java.time.LocalDate

@Service
class OllamaClient(
    builder: RestTemplateBuilder,
    private val objectMapper: ObjectMapper,
    @Value("\${ollama.base-url:http://localhost:11434}") private val baseUrl: String,
    @Value("\${ollama.model:mistral}") private val defaultModel: String,
    @Value("\${ollama.enabled:true}") private val enabled: Boolean,
) {

    private val restTemplate: RestTemplate = builder.build()

    fun isEnabled(): Boolean = enabled

    fun extractInvoiceFields(text: String, modelOverride: String?): LlmExtractionResult? {
        if (!enabled) return null

        val prompt = buildString {
            append("Tu es un extracteur de champs de facture. ")
            append("Retourne un JSON avec les clés: invoice_number, invoice_date (YYYY-MM-DD), total_amount (nombre), currency (ISO).\n")
            append("Texte facture:\n")
            append(text.take(6000))
            append("\nRéponds uniquement en JSON compact, sans commentaire.")
        }

        val jsonText = performOllamaRequest(prompt, modelOverride, temperature = 0.2) ?: return null
        return try {
            val node = objectMapper.readTree(jsonText)
            toResult(node)
        } catch (ex: Exception) {
            logger.warn("Ollama parsing failed: {}", ex.message)
            null
        }
    }

    fun inferBankColumns(
        headers: List<String>,
        sampleRows: List<Map<String, String?>>,
        modelOverride: String?
    ): BankColumnInference? {
        if (!enabled) return null
        if (headers.isEmpty()) return null

        val sampleJson = try {
            objectMapper.writeValueAsString(sampleRows.take(15))
        } catch (ex: Exception) {
            logger.warn("Impossible de sérialiser l'échantillon pour Ollama: {}", ex.message)
            "[]"
        }

        val prompt = buildString {
            append("Tu es un expert en relevés bancaires.\n")
            append("On te fournit la liste des en-têtes et un échantillon de lignes sous forme JSON.\n")
            append("Identifie les champs suivants: date_column, description_column, amount_column (montant signé si unique), debit_column (sortant), credit_column (entrant).\n")
            append("Si une colonne n'existe pas, retourne null. Utilise exactement le nom de colonne tel qu'il apparaît dans les en-têtes.\n")
            append("Réponds uniquement avec un objet JSON du type:\n")
            append("{\"date_column\":...,\"description_column\":...,\"amount_column\":...,\"debit_column\":...,\"credit_column\":...}\n\n")
            append("Headers: ${headers}\n\n")
            append("Sample rows (JSON): ${sampleJson}\n")
        }

        val jsonText = performOllamaRequest(prompt, modelOverride, temperature = 0.0) ?: return null
        return try {
            val node = objectMapper.readTree(jsonText)
            BankColumnInference(
                node.get("date_column")?.asText(),
                node.get("description_column")?.asText(),
                node.get("amount_column")?.asText(),
                node.get("debit_column")?.asText(),
                node.get("credit_column")?.asText()
            )
        } catch (ex: Exception) {
            logger.warn("Ollama colonne relevé KO: {}", ex.message)
            null
        }
    }

    fun extractBankTransactions(text: String, modelOverride: String?): List<BankTransactionCandidate> {
        if (!enabled) return emptyList()
        val prompt = buildString {
            append("Tu es un analyseur de relevé bancaire.\n")
            append("À partir du texte suivant, retourne la liste des transactions sous forme JSON.\n")
            append("Chaque transaction doit avoir: date (YYYY-MM-DD), description (texte), amount (nombre, négatif pour débit, positif pour crédit).\n")
            append("Réponds uniquement avec un objet JSON de la forme {\"transactions\":[{...}]} sans commentaire.\n\n")
            append("Texte du relevé:\n")
            append(text.take(6000))
        }

        val jsonText = performOllamaRequest(prompt, modelOverride, temperature = 0.0) ?: return emptyList()
        return try {
            val node = objectMapper.readTree(jsonText)
            val transactionsNode = when {
                node.has("transactions") -> node.get("transactions")
                node.isArray -> node
                else -> null
            } ?: return emptyList()
            transactionsNode.mapNotNull { item ->
                val date = item.get("date")?.asText()?.trim()
                val description = item.get("description")?.asText()?.trim()
                val amountNode = item.get("amount") ?: item.get("amount_text") ?: item.get("montant")
                val amount = when {
                    amountNode == null || amountNode.isNull -> null
                    amountNode.isNumber -> amountNode.numberValue().toString()
                    else -> amountNode.asText()
                }
                if (!date.isNullOrBlank() && !description.isNullOrBlank()) {
                    BankTransactionCandidate(date, description, amount?.trim())
                } else {
                    null
                }
            }
        } catch (ex: Exception) {
            logger.warn("Ollama extraction relevé KO: {}", ex.message)
            emptyList()
        }
    }

    private fun performOllamaRequest(
        prompt: String,
        modelOverride: String?,
        temperature: Double
    ): String? {
        val modelsToTry = buildList {
            val overrideModel = modelOverride?.trim()?.takeIf { it.isNotEmpty() }
            if (overrideModel != null) add(overrideModel)
            if (defaultModel.isNotBlank() && defaultModel !in this) add(defaultModel)
        }
        val url = "${baseUrl.trimEnd('/')}/api/generate"

        for (model in modelsToTry) {
            val payload = mapOf(
                "model" to model,
                "prompt" to prompt,
                "stream" to false,
                "options" to mapOf("temperature" to temperature)
            )
            try {
                val response: ResponseEntity<Map<*, *>> = restTemplate.postForEntity(url, payload, Map::class.java)
                val body = response.body ?: continue
                val rawResponse = body["response"] as? String ?: continue
                val jsonText = extractJson(rawResponse)
                if (jsonText != null) {
                    if (model != modelOverride && modelOverride != null) {
                        logger.info("Fallback sur le modèle '{}' faute de disponibilité de '{}'.", model, modelOverride)
                    }
                    return jsonText
                }
            } catch (ex: HttpClientErrorException.NotFound) {
                logger.warn("Modèle Ollama '{}' introuvable ({}).", model, ex.message)
                continue
            } catch (ex: RestClientException) {
                logger.warn("Ollama request failed ({}): {}", model, ex.message)
                return null
            }
        }

        logger.warn("Aucun modèle Ollama disponible parmi {}", modelsToTry)
        return null
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end >= start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    private fun toResult(node: JsonNode): LlmExtractionResult {
        val invoiceNumber = node.findValue("invoice_number")?.asText()?.takeIf { it.isNotBlank() }
        val invoiceDate = node.findValue("invoice_date")?.asText()?.let { ParsingUtils.parseDate(it) }
        val totalAmountNode = node.findValue("total_amount")
        val totalAmount = when {
            totalAmountNode == null || totalAmountNode.isNull -> null
            totalAmountNode.isNumber -> totalAmountNode.decimalValue()
            else -> ParsingUtils.parseAmount(totalAmountNode.asText())
        }
        val currency = node.findValue("currency")?.asText()?.takeIf { it.isNotBlank() }?.uppercase()

        return LlmExtractionResult(invoiceNumber, invoiceDate, totalAmount, currency)
    }

    data class LlmExtractionResult(
        val invoiceNumber: String?,
        val invoiceDate: LocalDate?,
        val totalAmount: BigDecimal?,
        val currency: String?,
    )

    data class BankColumnInference(
        val dateColumn: String?,
        val descriptionColumn: String?,
        val amountColumn: String?,
        val debitColumn: String?,
        val creditColumn: String?,
    )

    data class BankTransactionCandidate(
        val date: String,
        val description: String,
        val amount: String?
    )

    companion object {
        private val logger = LoggerFactory.getLogger(OllamaClient::class.java)
    }
}
