package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.PhoneNumberNormalizer
import com.helpdesk.jiraagent.model.PhoneRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberNormalizerTest {

    @Test
    fun normalizesBelarusFormatsToInternationalE164() {
        val cases = mapOf(
            "8 (029) 123-45-67" to "+375291234567",
            "80336543210" to "+375336543210",
            "8-044-777-88-99" to "+375447778899",
            "8 025 111 22 33" to "+375251112233",
            "8 (017) 222-33-44" to "+375172223344",
            "375291234567" to "+375291234567",
            "+375 (29) 123-45-67" to "+375291234567",
            "00375 29 123 45 67" to "+375291234567",
            "029 123-45-67" to "+375291234567",
            "(29) 123-45-67" to "+375291234567"
        )

        for ((raw, expectedE164) in cases) {
            val result = PhoneNumberNormalizer.normalizeSingle(raw, PhoneRegion.AUTO_BY_RU)
            assertNotNull("Expected $raw to normalize", result)
            assertEquals("Failed for $raw", expectedE164, result!!.first)
        }
    }

    @Test
    fun normalizesRussianFormatsToInternationalE164() {
        val cases = mapOf(
            "8 (916) 123-45-67" to "+79161234567",
            "89261112233" to "+79261112233",
            "8 (495) 234-56-78" to "+74952345678",
            "8 800 555-35-35" to "+78005553535",
            "79161234567" to "+79161234567",
            "+7 (916) 123-45-67" to "+79161234567",
            "(916) 123-45-67" to "+79161234567"
        )

        for ((raw, expectedE164) in cases) {
            val result = PhoneNumberNormalizer.normalizeSingle(raw, PhoneRegion.AUTO_BY_RU)
            assertNotNull("Expected $raw to normalize", result)
            assertEquals("Failed for $raw", expectedE164, result!!.first)
        }
    }

    @Test
    fun extractsPhonesFromFreeTextAndIgnoresDatesJiraKeysUrls() {
        val description = """
            Заявка связана с задачей HD-80291234567 от 2026-11-21T10:15:30.000+0300 (дата 21.11.2026).
            Сервер клиента: 192.168.100.25, ссылка https://jira.company.by/browse/HD-10?token=80299998877
            Контактный телефон клиента: 8 (029) 654-32-10, доп. номер в РФ: 8-916-555-01-99.
        """.trimIndent()

        val extracted = PhoneNumberNormalizer.extractFromText(description, PhoneRegion.AUTO_BY_RU)
        assertEquals(2, extracted.size)
        assertEquals("+375296543210", extracted[0].e164)
        assertEquals("+375 29 654-32-10", extracted[0].formattedDisplay)
        assertEquals("+79165550199", extracted[1].e164)
        assertEquals("+7 916 555-01-99", extracted[1].formattedDisplay)
    }

    @Test
    fun rejectsInvalidInputs() {
        assertNull(PhoneNumberNormalizer.normalizeSingle("2026-11-21"))
        assertNull(PhoneNumberNormalizer.normalizeSingle("192.168.1.1"))
        assertNull(PhoneNumberNormalizer.normalizeSingle("12345"))
    }
}
