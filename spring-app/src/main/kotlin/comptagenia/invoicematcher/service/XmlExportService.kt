package comptagenia.invoicematcher.service

import comptagenia.invoicematcher.model.InvoiceData
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@Service
class XmlExportService {
    fun invoicesToXml(invoices: List<InvoiceData>): String {
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document = documentBuilder.newDocument()
        val root = document.createElement("invoices")
        document.appendChild(root)

        for (invoice in invoices) {
            val invoiceElement = document.createElement("invoice")
            root.appendChild(invoiceElement)

            invoiceElement.appendChild(document.createElement("filename").apply {
                textContent = invoice.filename
            })
            invoiceElement.appendChild(document.createElement("invoice_number").apply {
                textContent = invoice.invoiceNumber ?: ""
            })
            invoiceElement.appendChild(document.createElement("invoice_date").apply {
                textContent = invoice.invoiceDate?.toString() ?: ""
            })
            invoiceElement.appendChild(document.createElement("total_amount").apply {
                textContent = invoice.totalAmount?.setScale(2, RoundingMode.HALF_UP)?.toString() ?: ""
            })
            invoiceElement.appendChild(document.createElement("currency").apply {
                textContent = invoice.currency ?: ""
            })
        }

        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        val writer = StringWriter()
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
}
