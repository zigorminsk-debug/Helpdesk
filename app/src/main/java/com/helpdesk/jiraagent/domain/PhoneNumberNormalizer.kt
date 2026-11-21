package com.helpdesk.jiraagent.domain

import com.helpdesk.jiraagent.model.PhoneMatch
import com.helpdesk.jiraagent.model.PhoneRegion

data class PhoneTextSpan(
    val startIndex: Int,
    val endIndex: Int,
    val match: PhoneMatch
)

object PhoneNumberNormalizer {

    private val BELARUS_AREA_PREFIXES_2 = setOf(
        "15", "16", "17", "21", "22", "23", "24", "25", "29", "33", "44"
    )

    private val BELARUS_MOBILE_AND_MINSK_2 = setOf(
        "17", "25", "29", "33", "44"
    )

    private val RUSSIA_SECOND_DIGITS = setOf('3', '4', '7', '8', '9')

    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val ISO_DATE_TIME_REGEX = Regex(
        """\b\d{4}-\d{2}-\d{2}(?:[T\s]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?\b"""
    )

    // Matches phone-like sequences in text (e.g. +375 (29) 123-45-67, 8 (029) 123-45-67, 89161234567)
    private val PHONE_CANDIDATE_REGEX = Regex(
        """(?<![A-Za-zА-Яа-яЁё0-9])(?:\+|00)?\d[\d\s().-]{6,20}\d(?![A-Za-zА-Яа-яЁё0-9])"""
    )

    /**
     * Normalizes a single raw phone value (e.g. from a dedicated Phone field or matched candidate)
     * into strict international E.164 format (+...) and a readable international display string.
     * Returns null if the input cannot be recognized as a valid phone number.
     */
    fun normalizeSingle(
        rawInput: String,
        region: PhoneRegion = PhoneRegion.AUTO_BY_RU,
        customCountryCode: String = "+375",
        strictForFreeText: Boolean = false
    ): Pair<String, String>? {
        val trimmed = rawInput
            .removePrefix("tel:")
            .removePrefix("TEL:")
            .trim()
        if (trimmed.isEmpty()) return null

        // Strip extension suffix like "доб. 123" or "ext 45" if present in dedicated field
        val withoutExt = trimmed.replace(
            Regex("""(?i)\s*(?:доб\.?|вн\.?|ext\.?|#)\s*\d+$"""),
            ""
        ).trim()

        if (looksLikeDateOrIp(withoutExt)) return null

        val hasLeadingPlus = withoutExt.startsWith("+")
        val digits = withoutExt.filter { it.isDigit() }
        if (digits.length < 9 || digits.length > 15) return null

        val e164: String? = when {
            // 1. Explicit '+' international prefix
            hasLeadingPlus -> {
                if (digits.length in 9..15 && digits[0] != '0') {
                    // Handle common typo "+80291234567" -> "+375291234567"
                    if (digits.length == 11 && digits.startsWith("80") &&
                        digits.substring(2, 4) in BELARUS_AREA_PREFIXES_2
                    ) {
                        "+375${digits.substring(2)}"
                    } else {
                        "+$digits"
                    }
                } else {
                    null
                }
            }

            // 2. Explicit '00' international dialing prefix (e.g. 00375291234567)
            withoutExt.startsWith("00") && digits.length in 11..17 -> {
                val stripped = digits.substring(2)
                if (stripped.length in 9..15 && stripped[0] != '0') "+$stripped" else null
            }

            // 3. 12-digit Belarus without '+': 375XXXXXXXXX
            digits.length == 12 && digits.startsWith("375") &&
                digits.substring(3, 5) in BELARUS_AREA_PREFIXES_2 -> {
                "+$digits"
            }

            // 4. 11-digit Belarus national format: 80XXXXXXXXX (e.g. 8 (029) 123-45-67, 80172345678)
            digits.length == 11 && digits.startsWith("80") &&
                digits.substring(2, 4) in BELARUS_AREA_PREFIXES_2 -> {
                "+375${digits.substring(2)}"
            }

            // 5. Custom region handling when explicitly chosen
            region == PhoneRegion.CUSTOM -> {
                normalizeWithCustomCode(digits, customCountryCode, strictForFreeText)
            }

            // 6. 10-digit Belarus local with leading '0': 0291234567, 0172345678
            digits.length == 10 && digits.startsWith("0") &&
                digits.substring(1, 3) in BELARUS_AREA_PREFIXES_2 &&
                (region == PhoneRegion.AUTO_BY_RU || region == PhoneRegion.BELARUS_375) -> {
                "+375${digits.substring(1)}"
            }

            // 7. 9-digit Belarus local without '80': (29) 123-45-67
            digits.length == 9 &&
                digits.substring(0, 2) in BELARUS_MOBILE_AND_MINSK_2 &&
                (region == PhoneRegion.AUTO_BY_RU || region == PhoneRegion.BELARUS_375) -> {
                // In free text, require formatting separators for short 9-digit numbers to avoid random 9-digit IDs
                val hasFormatting = withoutExt.any { it == '(' || it == ')' || it == '-' || it == ' ' }
                if (!strictForFreeText || hasFormatting) "+375$digits" else null
            }

            // 8. 11-digit Russia / Kazakhstan national format: 89XXXXXXXXX, 8495XXXXXXX, 8800XXXXXXX
            digits.length == 11 && digits.startsWith("8") &&
                digits[1] in RUSSIA_SECOND_DIGITS -> {
                "+7${digits.substring(1)}"
            }

            // 9. 11-digit Russia / Kazakhstan starting with '7' without '+': 79161234567
            digits.length == 11 && digits.startsWith("7") &&
                digits[1] in RUSSIA_SECOND_DIGITS -> {
                "+$digits"
            }

            // 10. 10-digit Russia mobile/area without '8'/'+7': (916) 123-45-67
            digits.length == 10 && (
                (region == PhoneRegion.RUSSIA_7 && digits[0] in RUSSIA_SECOND_DIGITS) ||
                    (region == PhoneRegion.AUTO_BY_RU && digits[0] == '9')
                ) -> {
                val hasFormatting = withoutExt.any { it == '(' || it == ')' || it == '-' || it == ' ' }
                if (!strictForFreeText || hasFormatting) "+7$digits" else null
            }

            // 11. Other well-known CIS/EU international codes written without '+' in dedicated fields
            !strictForFreeText && digits.length == 12 && (
                digits.startsWith("380") || digits.startsWith("998") || digits.startsWith("995")
                ) -> {
                "+$digits"
            }

            else -> null
        }

        if (e164 == null) return null
        return e164 to formatInternationalDisplay(e164)
    }

    private fun normalizeWithCustomCode(
        digits: String,
        customCountryCode: String,
        strictForFreeText: Boolean
    ): String? {
        val codeDigits = customCountryCode.filter { it.isDigit() }.ifEmpty { "375" }
        return when {
            digits.startsWith(codeDigits) && digits.length in (codeDigits.length + 7)..15 -> {
                "+$digits"
            }
            codeDigits == "375" && digits.length == 11 && digits.startsWith("80") -> {
                "+375${digits.substring(2)}"
            }
            digits.startsWith("8") && digits.length in 10..11 -> {
                "+$codeDigits${digits.substring(1)}"
            }
            digits.startsWith("0") && digits.length in 9..11 -> {
                "+$codeDigits${digits.substring(1)}"
            }
            !strictForFreeText && digits.length in 7..10 -> {
                "+$codeDigits$digits"
            }
            else -> null
        }
    }

    /**
     * Formats an E.164 phone number (+375291234567) into a clean human-readable international format
     * (+375 29 123-45-67) while preserving the '+' international prefix.
     */
    fun formatInternationalDisplay(e164: String): String {
        val digits = e164.filter { it.isDigit() }
        return when {
            // Belarus: +375 XX XXX-XX-XX
            digits.length == 12 && digits.startsWith("375") -> {
                val code = digits.substring(3, 5)
                val p1 = digits.substring(5, 8)
                val p2 = digits.substring(8, 10)
                val p3 = digits.substring(10, 12)
                "+375 $code $p1-$p2-$p3"
            }
            // Russia / Kazakhstan: +7 XXX XXX-XX-XX
            digits.length == 11 && digits.startsWith("7") -> {
                val code = digits.substring(1, 4)
                val p1 = digits.substring(4, 7)
                val p2 = digits.substring(7, 9)
                val p3 = digits.substring(9, 11)
                "+7 $code $p1-$p2-$p3"
            }
            // Ukraine: +380 XX XXX-XX-XX
            digits.length == 12 && digits.startsWith("380") -> {
                val code = digits.substring(3, 5)
                val p1 = digits.substring(5, 8)
                val p2 = digits.substring(8, 10)
                val p3 = digits.substring(10, 12)
                "+380 $code $p1-$p2-$p3"
            }
            // Poland: +48 XXX XXX XXX
            digits.length == 11 && digits.startsWith("48") -> {
                "+48 ${digits.substring(2, 5)} ${digits.substring(5, 8)} ${digits.substring(8, 11)}"
            }
            else -> "+$digits"
        }
    }

    /**
     * Finds all phone spans inside free text (description, summary, comments) for both extraction
     * and clickable inline rendering in the ticket detail screen.
     */
    fun findPhoneSpans(
        text: String,
        region: PhoneRegion = PhoneRegion.AUTO_BY_RU,
        customCountryCode: String = "+375",
        sourceLabel: String = "Описание"
    ): List<PhoneTextSpan> {
        if (text.isBlank()) return emptyList()

        val excludedRanges = mutableListOf<IntRange>()
        URL_REGEX.findAll(text).forEach { excludedRanges.add(it.range) }
        ISO_DATE_TIME_REGEX.findAll(text).forEach { excludedRanges.add(it.range) }

        val spans = mutableListOf<PhoneTextSpan>()

        for (matchResult in PHONE_CANDIDATE_REGEX.findAll(text)) {
            val start = matchResult.range.first
            val endInclusive = matchResult.range.last

            if (excludedRanges.any { start <= it.last && endInclusive >= it.first }) {
                continue
            }

            // Exclude Jira issue keys like "HD-80291234567" where '-' follows letters
            if (start >= 2 && text[start - 1] == '-' && text[start - 2].isLetter()) {
                continue
            }

            // Exclude decimal/IP-like chains where '.' precedes or follows digits
            if (start >= 2 && text[start - 1] == '.' && text[start - 2].isDigit()) {
                continue
            }
            if (endInclusive + 2 < text.length &&
                text[endInclusive + 1] == '.' &&
                text[endInclusive + 2].isDigit()
            ) {
                continue
            }

            val rawCandidate = matchResult.value.trim()
            val normalized = normalizeSingle(
                rawInput = rawCandidate,
                region = region,
                customCountryCode = customCountryCode,
                strictForFreeText = true
            ) ?: continue

            val trimLeading = matchResult.value.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val trimTrailing = matchResult.value.length - 1 -
                matchResult.value.indexOfLast { !it.isWhitespace() }.coerceAtLeast(0)

            val actualStart = start + trimLeading
            val actualEndExclusive = (endInclusive + 1) - trimTrailing

            spans.add(
                PhoneTextSpan(
                    startIndex = actualStart,
                    endIndex = actualEndExclusive,
                    match = PhoneMatch(
                        raw = text.substring(actualStart, actualEndExclusive),
                        e164 = normalized.first,
                        formattedDisplay = normalized.second,
                        source = sourceLabel
                    )
                )
            )
        }

        return spans
    }

    /**
     * Extracts deduplicated PhoneMatch entries from free text.
     */
    fun extractFromText(
        text: String,
        region: PhoneRegion = PhoneRegion.AUTO_BY_RU,
        customCountryCode: String = "+375",
        sourceLabel: String = "Описание"
    ): List<PhoneMatch> {
        return findPhoneSpans(text, region, customCountryCode, sourceLabel)
            .map { it.match }
            .distinctBy { it.e164 }
    }

    private fun looksLikeDateOrIp(value: String): Boolean {
        // DD.MM.YYYY or YYYY.MM.DD
        if (Regex("""^\d{2}\.\d{2}\.\d{4}.*$""").matches(value)) return true
        if (Regex("""^\d{4}[./-]\d{2}[./-]\d{2}.*$""").matches(value)) return true
        // IPv4 address
        if (Regex("""^\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?$""").matches(value)) return true
        return false
    }
}
