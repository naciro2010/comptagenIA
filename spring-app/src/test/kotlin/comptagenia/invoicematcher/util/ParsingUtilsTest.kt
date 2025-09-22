package comptagenia.invoicematcher.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ParsingUtilsTest {

    @Test
    fun `parses french styled amount`() {
        val amount = ParsingUtils.parseAmount("1 234,56")
        assertEquals(BigDecimal("1234.56"), amount)
    }

    @Test
    fun `parses credit amount as positive`() {
        val amount = ParsingUtils.parseAmount("+ 987,00")
        assertEquals(BigDecimal("987.00"), amount)
    }

    @Test
    fun `returns null when amount missing`() {
        val amount = ParsingUtils.parseAmount(null)
        assertNull(amount)
    }

    @Test
    fun `parses date with european format`() {
        val date = ParsingUtils.parseDate("05/12/2024")
        assertEquals(LocalDate.of(2024, 12, 5), date)
    }

    @Test
    fun `parses written month date`() {
        val date = ParsingUtils.parseDate("15 janvier 2023")
        assertEquals(LocalDate.of(2023, 1, 15), date)
    }
}
