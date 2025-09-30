package comptagenia.service

import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.TesseractException
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.imageio.ImageIO

@Service
class OcrService {
    private val logger = LoggerFactory.getLogger(OcrService::class.java)

    companion object {
        val SUPPORTED_IMAGE_FORMATS = setOf(".png", ".jpg", ".jpeg", ".tiff", ".tif", ".bmp", ".gif")
    }

    private val tesseract: Tesseract by lazy { buildTesseract() }

    fun extractText(content: ByteArray, filename: String): String {
        val lower = filename.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".pdf") -> extractFromPdf(content)
            SUPPORTED_IMAGE_FORMATS.any { lower.endsWith(it) } -> extractFromImage(content)
            else -> {
                logger.info("Unsupported extension for {}. Attempting OCR as image", filename)
                extractFromImage(content)
            }
        }
    }

    private fun extractFromPdf(content: ByteArray): String {
        PDDocument.load(ByteArrayInputStream(content)).use { document ->
            val stripperText = runCatching {
                val stripper = org.apache.pdfbox.text.PDFTextStripper().apply {
                    sortByPosition = true
                }
                stripper.getText(document).trim()
            }.getOrElse {
                logger.warn("PDF text extraction failed, will fallback to OCR", it)
                ""
            }

            if (stripperText.isNotBlank()) {
                return stripperText
            }

            val renderer = PDFRenderer(document)
            val sb = StringBuilder()
            for (page in 0 until document.numberOfPages) {
                val image = renderer.renderImageWithDPI(page, 300f)
                sb.append(runOcr(image))
                sb.append('\n')
            }
            return sb.toString().trim()
        }
    }

    private fun extractFromImage(content: ByteArray): String {
        val image = runCatching {
            ImageIO.read(ByteArrayInputStream(content))
        }.getOrNull()

        if (image != null) {
            return runOcr(image)
        }

        // Some formats may not be directly supported by ImageIO. Write to temp file.
        val temp = Files.createTempFile("ocr", ".bin")
        return try {
            Files.write(temp, content)
            runOcr(temp)
        } finally {
            try {
                Files.deleteIfExists(temp)
            } catch (ex: Exception) {
                logger.debug("Failed deleting temp file {}", temp, ex)
            }
        }
    }

    private fun runOcr(image: BufferedImage): String {
        return try {
            tesseract.doOCR(image).trim()
        } catch (ex: TesseractException) {
            logger.error("Tesseract OCR failed", ex)
            ""
        }
    }

    private fun runOcr(path: Path): String {
        return try {
            tesseract.doOCR(path.toFile()).trim()
        } catch (ex: TesseractException) {
            logger.error("Tesseract OCR failed for {}", path, ex)
            ""
        }
    }

    private fun buildTesseract(): Tesseract {
        val tess = Tesseract()
        val datapath = sequenceOf(
            System.getenv("TESSDATA_PREFIX"),
            System.getProperty("TESSDATA_PREFIX"),
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tesseract-ocr/tessdata"
        ).firstOrNull { !it.isNullOrBlank() && Files.exists(Path.of(it)) }

        if (!datapath.isNullOrBlank()) {
            tess.setDatapath(datapath)
        }

        tess.setLanguage("fra+eng")
        tess.setOcrEngineMode(1)
        tess.setPageSegMode(1)
        return tess
    }
}
