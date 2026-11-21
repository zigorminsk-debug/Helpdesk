package com.helpdesk.jiraagent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class HabrFeedCategory(
    val title: String,
    val primaryRssUrl: String,
    val fallbackRssUrl: String
) {
    NEWS(
        title = "Новости",
        primaryRssUrl = "https://habr.com/ru/rss/news/?fl=ru",
        fallbackRssUrl = "https://habr.com/ru/rss/articles/?fl=ru"
    ),
    SYSADMIN(
        title = "Сисадмин",
        primaryRssUrl = "https://habr.com/ru/rss/flows/admin/articles/?fl=ru",
        fallbackRssUrl = "https://habr.com/ru/rss/all/all/?fl=ru"
    ),
    TOP(
        title = "Лучшие",
        primaryRssUrl = "https://habr.com/ru/rss/articles/top/daily/?fl=ru",
        fallbackRssUrl = "https://habr.com/ru/rss/best/daily/?fl=ru"
    )
}

data class HabrNewsItem(
    val id: String,
    val title: String,
    val url: String,
    val summary: String,
    val publishedAtEpochMillis: Long?,
    val publishedLabel: String,
    val author: String?,
    val tags: List<String>
)

object HabrRssParser {

    private val MINSK_ZONE: ZoneId = ZoneId.of("Europe/Minsk")
    private val TIME_ONLY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))
    private val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))

    private val ITEM_REGEX = Regex("<item\\b[^>]*>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CATEGORY_REGEX = Regex("<category\\b[^>]*>(.*?)</category>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CDATA_REGEX = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    private val READ_MORE_ANCHOR_REGEX = Regex(
        "<a\\b[^>]*>\\s*(Читать далее|Читать дальше|Посмотреть результаты|Читать кейс|Читать полностью|Под кат[^<]*)\\s*</a>\\s*$",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val SCRIPT_STYLE_REGEX = Regex("<(script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val DEC_ENTITY_REGEX = Regex("&#(\\d{1,7});")
    private val HEX_ENTITY_REGEX = Regex("&#x([0-9a-fA-F]{1,6});")

    fun parse(
        rssXml: String,
        maxItems: Int = 15,
        nowDateMinsk: LocalDate = LocalDate.now(MINSK_ZONE)
    ): List<HabrNewsItem> {
        if (rssXml.isBlank()) return emptyList()

        val results = ArrayList<HabrNewsItem>(maxItems)
        for (match in ITEM_REGEX.findAll(rssXml)) {
            if (results.size >= maxItems) break
            val itemXml = match.groupValues[1]

            val rawTitle = extractTag(itemXml, "title") ?: continue
            val title = cleanPlainText(rawTitle)
            if (title.isBlank()) continue

            val rawGuid = extractTag(itemXml, "guid")
            val rawLink = extractTag(itemXml, "link")
            val cleanUrl = sanitizeHabrUrl(rawLink ?: rawGuid ?: "https://habr.com/ru/")

            val rawDesc = extractTag(itemXml, "description").orEmpty()
            val summary = cleanDescriptionSummary(rawDesc, maxLength = 220)

            val rawPubDate = extractTag(itemXml, "pubDate")
            val epochMillis = parseRfc1123Millis(rawPubDate)
            val publishedLabel = formatMinskDate(epochMillis, nowDateMinsk)

            val rawCreator = extractTag(itemXml, "dc:creator") ?: extractTag(itemXml, "author")
            val author = rawCreator?.let { cleanPlainText(it) }?.takeIf { it.isNotBlank() }

            val tags = CATEGORY_REGEX.findAll(itemXml)
                .map { cleanPlainText(unwrapCdata(it.groupValues[1])) }
                .filter { it.isNotBlank() && it.length <= 36 }
                .distinct()
                .take(2)
                .toList()

            val id = cleanUrl.ifBlank { "${title.hashCode()}_${epochMillis ?: 0L}" }
            results.add(
                HabrNewsItem(
                    id = id,
                    title = title,
                    url = cleanUrl,
                    summary = summary,
                    publishedAtEpochMillis = epochMillis,
                    publishedLabel = publishedLabel,
                    author = author,
                    tags = tags
                )
            )
        }
        return results
    }

    private fun extractTag(xml: String, tagName: String): String? {
        val escaped = Regex.escape(tagName)
        val regex = Regex("<$escaped(?:\\s[^>]*)?>(.*?)</$escaped>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val raw = regex.find(xml)?.groupValues?.get(1) ?: return null
        return unwrapCdata(raw).trim()
    }

    private fun unwrapCdata(value: String): String {
        val trimmed = value.trim()
        val match = CDATA_REGEX.find(trimmed)
        return if (match != null && match.range.first == 0 && match.range.last == trimmed.lastIndex) {
            match.groupValues[1]
        } else {
            trimmed.replace(CDATA_REGEX) { it.groupValues[1] }
        }
    }

    fun sanitizeHabrUrl(rawUrl: String): String {
        val decoded = decodeHtmlEntities(unwrapCdata(rawUrl)).trim()
        if (!decoded.startsWith("http://") && !decoded.startsWith("https://")) {
            return "https://habr.com/ru/"
        }
        val withoutFragment = decoded.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', "")
        if (query.isBlank()) return base

        val keptParams = query.split('&')
            .filter { param ->
                val key = param.substringBefore('=').lowercase(Locale.ROOT)
                key.isNotBlank() && !key.startsWith("utm_")
            }
        return if (keptParams.isEmpty()) base else "$base?${keptParams.joinToString("&")}"
    }

    private fun cleanDescriptionSummary(rawHtml: String, maxLength: Int): String {
        if (rawHtml.isBlank()) return ""
        val unwrapped = unwrapCdata(rawHtml).trim()
        val withoutReadMore = unwrapped.replace(READ_MORE_ANCHOR_REGEX, " ")
        val withoutScripts = withoutReadMore.replace(SCRIPT_STYLE_REGEX, " ")
        val withBreaks = withoutScripts
            .replace(Regex("</?(p|div|br|li|h[1-6])\\b[^>]*>", RegexOption.IGNORE_CASE), " ")
        val withoutTags = withBreaks.replace(HTML_TAG_REGEX, "")
        val decoded = decodeHtmlEntities(withoutTags)
            .replace('\u00A0', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .removeSuffix("Читать далее")
            .removeSuffix("Посмотреть результаты")
            .removeSuffix("Читать кейс")
            .trim()

        if (decoded.length <= maxLength) return decoded
        val cut = decoded.take(maxLength)
        val lastSpace = cut.lastIndexOf(' ')
        val neat = if (lastSpace > maxLength * 2 / 3) cut.substring(0, lastSpace) else cut
        return neat.trimEnd('.', ',', ';', ':', '-', '—') + "…"
    }

    private fun cleanPlainText(raw: String): String {
        val withoutTags = unwrapCdata(raw).replace(HTML_TAG_REGEX, "")
        return decodeHtmlEntities(withoutTags)
            .replace('\u00A0', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        var s = input
            .replace("&nbsp;", " ")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        s = DEC_ENTITY_REGEX.replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        s = HEX_ENTITY_REGEX.replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return s
    }

    private fun parseRfc1123Millis(rawPubDate: String?): Long? {
        if (rawPubDate.isNullOrBlank()) return null
        return runCatching {
            ZonedDateTime.parse(rawPubDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun formatMinskDate(epochMillis: Long?, nowDateMinsk: LocalDate): String {
        if (epochMillis == null) return ""
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(MINSK_ZONE)
        return when (zdt.toLocalDate()) {
            nowDateMinsk -> "Сегодня, ${zdt.format(TIME_ONLY_FMT)}"
            nowDateMinsk.minusDays(1) -> "Вчера, ${zdt.format(TIME_ONLY_FMT)}"
            else -> zdt.format(DATE_TIME_FMT)
        }
    }
}

object HabrNewsRepository {

    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private data class CachedFeed(
        val items: List<HabrNewsItem>,
        val fetchedAtMillis: Long
    )

    private val cache = ConcurrentHashMap<HabrFeedCategory, CachedFeed>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun peekCached(category: HabrFeedCategory): List<HabrNewsItem>? {
        return cache[category]?.items?.takeIf { it.isNotEmpty() }
    }

    suspend fun loadFeed(
        category: HabrFeedCategory,
        forceRefresh: Boolean = false
    ): Result<List<HabrNewsItem>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = cache[category]
        if (!forceRefresh && existing != null && (now - existing.fetchedAtMillis) < CACHE_TTL_MS && existing.items.isNotEmpty()) {
            return@withContext Result.success(existing.items)
        }

        val primaryAttempt = runCatching { fetchAndParseUrl(category.primaryRssUrl) }
        val items = primaryAttempt.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { fetchAndParseUrl(category.fallbackRssUrl) }.getOrElse { fallbackErr ->
                val err = primaryAttempt.exceptionOrNull() ?: fallbackErr
                return@withContext Result.failure(err)
            }

        if (items.isNotEmpty()) {
            cache[category] = CachedFeed(items = items, fetchedAtMillis = now)
        }
        Result.success(items)
    }

    private fun fetchAndParseUrl(url: String): List<HabrNewsItem> {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} от habr.com")
            }
            val body = response.body?.string().orEmpty()
            return HabrRssParser.parse(body, maxItems = 15)
        }
    }
}
