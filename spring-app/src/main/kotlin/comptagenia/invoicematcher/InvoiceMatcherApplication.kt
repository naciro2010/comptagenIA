package comptagenia.invoicematcher

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class InvoiceMatcherApplication

fun main(args: Array<String>) {
    runApplication<InvoiceMatcherApplication>(*args)
}
