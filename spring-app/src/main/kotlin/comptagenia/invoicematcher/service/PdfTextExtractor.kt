package comptagenia.invoicematcher.service

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Component

@Component
open class PdfTextExtractor {
    open fun extractText(bytes: ByteArray): String {
        PDDocument.load(bytes).use { document ->
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            return stripper.getText(document)
        }
    }
}
