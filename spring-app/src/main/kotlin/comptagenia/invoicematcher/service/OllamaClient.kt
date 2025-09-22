package comptagenia.invoicematcher.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import comptagenia.invoicematcher.util.ParsingUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate

@Service
class OllamaClient(
    builder: RestTemplateBuilder,
    private val objectMapper: ObjectMapper,
    @Value("\${ollama.base-url:http://localhost:11434}") private val baseUrl: String,
    @Value("\${ollama.model:mistral}") private val defaultModel: String,
    @Value("\${ollama.enabled:true}") private val enabled: Boolean,
) {

    private val restTemplate: RestTemplate = builder
        .setConnectTimeout(Duration.ofSeconds(5))
        .setReadTimeout(Duration.ofSeconds(60))
        .build()

    fun isEnabled(): Boolean = enabled

    fun extractInvoiceFields(text: String, modelOverride: String?): LlmExtractionResult? {
        if (!enabled) return null
        val model = modelOverride?.takeIf { it.isNotBlank() } ?: defaultModel
        val url = "${baseUrl.trimEnd('/')}/api/generate"

        val prompt = buildString {
            append("Tu es un extracteur de champs de facture. ")
            append("Retourne un JSON avec les clés: invoice_number, invoice_date (YYYY-MM-DD), total_amount (nombre), currency (ISO).\n")
            append("Texte facture:\n")
            append(text.take(6000))
            append("\nRéponds uniquement en JSON compact, sans commentaire.")
        }

        val payload = mapOf(
            "model" to model,
            "prompt" to prompt,
            "stream" to false,
            "options" to mapOf("temperature" to 0.2)
        )

        return try {
            val response: ResponseEntity<Map<*, *>> = restTemplate.postForEntity(url, payload, Map::class.java)
            val body = response.body ?: return null
            val rawResponse = body["response"] as? String ?: return null
            val jsonText = extractJson(rawResponse) ?: return null
            val node = objectMapper.readTree(jsonText)
            toResult(node)
        } catch (ex: RestClientException) {
            logger.warn("Ollama request failed: {}", ex.message)
            null
        } catch (ex: Exception) {
            logger.warn("Ollama parsing failed: {}", ex.message)
            null
        }
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

    companion object {
        private val logger = LoggerFactory.getLogger(OllamaClient::class.java)
    }
}
