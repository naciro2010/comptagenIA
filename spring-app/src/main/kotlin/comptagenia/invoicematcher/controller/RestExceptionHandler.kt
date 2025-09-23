package comptagenia.invoicematcher.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException

@ControllerAdvice
class RestExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUpload(ex: MaxUploadSizeExceededException): ResponseEntity<Map<String, Any?>> {
        val body = mapOf(
            "error" to "Fichiers trop volumineux. Limite: 120 Mo par fichier (130 Mo par requête).",
            "details" to ex.message
        )
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(ex: NoResourceFoundException): ResponseEntity<Map<String, Any?>> {
        logger.debug("Ressource statique introuvable: {}", ex.resourcePath)
        val body = mapOf(
            "error" to "Ressource non trouvée",
            "details" to ex.resourcePath
        )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<Map<String, Any?>> {
        logger.warn("Requête invalide: {}", ex.message)
        val body = mapOf(
            "error" to (ex.message ?: "Requête invalide."),
            "details" to ex.javaClass.simpleName
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<Map<String, Any?>> {
        logger.error("Unhandled error", ex)
        val body = mapOf(
            "error" to "Erreur interne inattendue.",
            "details" to (ex.message ?: "")
        )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RestExceptionHandler::class.java)
    }
}
