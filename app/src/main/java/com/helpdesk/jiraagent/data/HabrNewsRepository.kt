package com.helpdesk.jiraagent.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

sealed class HabrArticleBlock {
    data class Heading(val level: Int, val text: String) : HabrArticleBlock()
    data class Paragraph(val text: String) : HabrArticleBlock()
    data class CodeBlock(val code: String, val language: String? = null) : HabrArticleBlock()
    data class Quote(val text: String) : HabrArticleBlock()
    data class BulletList(val ordered: Boolean, val items: List<String>) : HabrArticleBlock()
    data class Image(val url: String, val caption: String? = null) : HabrArticleBlock()
}

data class HabrArticleDetail(
    val url: String,
    val title: String,
    val author: String?,
    val publishedLabel: String,
    val readingTimeMinutes: Int?,
    val hubs: List<String>,
    val viewsCount: Int?,
    val score: Int?,
    val blocks: List<HabrArticleBlock>
)

object HabrRssParser {

    internal val MINSK_ZONE: ZoneId = ZoneId.of("Europe/Minsk")
    internal val TIME_ONLY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))
    internal val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))

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
                .take(3)
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

    internal fun unwrapCdata(value: String): String {
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

    internal fun cleanPlainText(raw: String): String {
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

    internal fun formatMinskDate(epochMillis: Long?, nowDateMinsk: LocalDate = LocalDate.now(MINSK_ZONE)): String {
        if (epochMillis == null) return ""
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(MINSK_ZONE)
        return when (zdt.toLocalDate()) {
            nowDateMinsk -> "Сегодня, ${zdt.format(TIME_ONLY_FMT)}"
            nowDateMinsk.minusDays(1) -> "Вчера, ${zdt.format(TIME_ONLY_FMT)}"
            else -> zdt.format(DATE_TIME_FMT)
        }
    }
}

object HabrArticleHtmlParser {

    private val SCRIPT_STYLE_REGEX = Regex("<(script|style|iframe|svg)\\b[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BLOCK_TOKEN_REGEX = Regex(
        "<(h[1-6]|pre|blockquote|ul|ol|figure|p|div|table|details)\\b([^>]*)>(.*?)</\\1>|<img\\b([^>]*)/?>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val LI_REGEX = Regex("<li\\b[^>]*>(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TR_REGEX = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TD_TH_REGEX = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val IMG_TAG_REGEX = Regex("<img\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private val FIGCAPTION_REGEX = Regex("<figcaption\\b[^>]*>(.*?)</figcaption>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val SUMMARY_REGEX = Regex("<summary\\b[^>]*>(.*?)</summary>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CODE_LANG_REGEX = Regex("class\\s*=\\s*[\"'][^\"']*?([a-zA-Z0-9_+#-]+)[\"']", RegexOption.IGNORE_CASE)
    private val INLINE_CODE_REGEX = Regex("<code\\b[^>]*>(.*?)</code>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val ANY_TAG_REGEX = Regex("<[^>]+>")
    private val MULTI_SPACE_REGEX = Regex("[ \\t\\x0B\\f\\r]+")

    fun extractArticleId(url: String): String? {
        val m = Regex("/(?:articles|news|post|posts|blog)/(\\d{4,10})(?:/|$|\\?)", RegexOption.IGNORE_CASE).find(url)
            ?: Regex("/(\\d{5,10})(?:/|$|\\?)").find(url)
        return m?.groupValues?.get(1)
    }

    fun normalizeImageUrl(raw: String?): String? {
        val clean = raw?.trim()?.let { HabrRssParser.decodeHtmlEntities(it) }.orEmpty()
        if (clean.isBlank() || clean.startsWith("data:")) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("/") -> "https://habr.com$clean"
            else -> null
        }
    }

    fun parseBlocks(rawHtml: String, leadImageUrl: String? = null): List<HabrArticleBlock> {
        val blocks = ArrayList<HabrArticleBlock>()
        val seenImages = HashSet<String>()

        normalizeImageUrl(leadImageUrl)?.let { cover ->
            seenImages.add(cover)
            blocks.add(HabrArticleBlock.Image(url = cover, caption = null))
        }

        if (rawHtml.isBlank()) return blocks
        val cleaned = rawHtml.replace(SCRIPT_STYLE_REGEX, "")

        parseHtmlSegmentInto(cleaned, blocks, seenImages, depth = 0)

        if (blocks.none { it !is HabrArticleBlock.Image }) {
            val fallbackText = inlineHtmlToReadableText(cleaned)
            if (fallbackText.isNotBlank()) {
                fallbackText.split(Regex("\\n{2,}"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { blocks.add(HabrArticleBlock.Paragraph(it)) }
            }
        }
        return blocks
    }

    private fun parseHtmlSegmentInto(
        html: String,
        out: MutableList<HabrArticleBlock>,
        seenImages: MutableSet<String>,
        depth: Int
    ) {
        var cursor = 0
        for (match in BLOCK_TOKEN_REGEX.findAll(html)) {
            if (match.range.first > cursor) {
                val between = inlineHtmlToReadableText(html.substring(cursor, match.range.first))
                if (between.isNotBlank()) {
                    out.add(HabrArticleBlock.Paragraph(between))
                }
            }
            cursor = match.range.last + 1

            val standaloneImgAttrs = match.groupValues[4]
            if (standaloneImgAttrs.isNotBlank()) {
                extractImageUrlFromAttrs(standaloneImgAttrs)?.let { url ->
                    if (seenImages.add(url)) {
                        out.add(HabrArticleBlock.Image(url = url, caption = extractAttr(standaloneImgAttrs, "alt")))
                    }
                }
                continue
            }

            val tag = match.groupValues[1].lowercase(Locale.ROOT)
            val attrs = match.groupValues[2]
            val inner = match.groupValues[3]

            when {
                tag.startsWith("h") && tag.length == 2 -> {
                    val level = tag[1].digitToIntOrNull() ?: 2
                    val text = inlineHtmlToReadableText(inner)
                    if (text.isNotBlank()) out.add(HabrArticleBlock.Heading(level, text))
                }

                tag == "pre" -> {
                    val lang = CODE_LANG_REGEX.find(inner)?.groupValues?.get(1)
                        ?: CODE_LANG_REGEX.find(attrs)?.groupValues?.get(1)
                    val codeRaw = inner
                        .replace(BR_REGEX, "\n")
                        .replace(ANY_TAG_REGEX, "")
                    val code = HabrRssParser.decodeHtmlEntities(codeRaw)
                        .replace('\u00A0', ' ')
                        .trim('\r', '\n')
                    if (code.isNotBlank()) {
                        out.add(HabrArticleBlock.CodeBlock(code = code, language = lang?.takeIf { !it.startsWith("tm-") }))
                    }
                }

                tag == "blockquote" -> {
                    extractEmbeddedImages(inner, out, seenImages)
                    val text = inlineHtmlToReadableText(inner)
                    if (text.isNotBlank()) out.add(HabrArticleBlock.Quote(text))
                }

                tag == "ul" || tag == "ol" -> {
                    val ordered = tag == "ol"
                    val items = LI_REGEX.findAll(inner)
                        .map { inlineHtmlToReadableText(it.groupValues[1]) }
                        .filter { it.isNotBlank() }
                        .toList()
                    if (items.isNotEmpty()) {
                        out.add(HabrArticleBlock.BulletList(ordered = ordered, items = items))
                    }
                }

                tag == "figure" -> {
                    val caption = FIGCAPTION_REGEX.find(inner)?.groupValues?.get(1)
                        ?.let { inlineHtmlToReadableText(it) }
                        ?.takeIf { it.isNotBlank() }
                    val imgMatch = IMG_TAG_REGEX.find(inner)
                    val imgUrl = imgMatch?.let { extractImageUrlFromAttrs(it.groupValues[1]) }
                    if (imgUrl != null && seenImages.add(imgUrl)) {
                        out.add(HabrArticleBlock.Image(url = imgUrl, caption = caption))
                    } else if (inner.contains("<pre", ignoreCase = true) && depth < 3) {
                        parseHtmlSegmentInto(inner, out, seenImages, depth + 1)
                    } else if (!caption.isNullOrBlank()) {
                        out.add(HabrArticleBlock.Quote(caption))
                    }
                }

                tag == "table" -> {
                    val rows = TR_REGEX.findAll(inner).mapNotNull { tr ->
                        val cells = TD_TH_REGEX.findAll(tr.groupValues[1])
                            .map { inlineHtmlToReadableText(it.groupValues[1]).replace('\n', ' ') }
                            .filter { it.isNotBlank() }
                            .toList()
                        if (cells.isEmpty()) null else cells.joinToString("  |  ")
                    }.toList()
                    if (rows.isNotEmpty()) {
                        out.add(HabrArticleBlock.CodeBlock(code = rows.joinToString("\n"), language = "таблица"))
                    }
                }

                tag == "details" -> {
                    val summary = SUMMARY_REGEX.find(inner)?.groupValues?.get(1)
                        ?.let { inlineHtmlToReadableText(it) }
                        ?.takeIf { it.isNotBlank() }
                    if (summary != null) {
                        out.add(HabrArticleBlock.Heading(level = 3, text = "▸ $summary"))
                    }
                    val bodyWithoutSummary = inner.replace(SUMMARY_REGEX, "")
                    if (depth < 4) {
                        parseHtmlSegmentInto(bodyWithoutSummary, out, seenImages, depth + 1)
                    }
                }

                tag == "div" -> {
                    if (depth < 4 && BLOCK_TOKEN_REGEX.containsMatchIn(inner)) {
                        parseHtmlSegmentInto(inner, out, seenImages, depth + 1)
                    } else {
                        extractEmbeddedImages(inner, out, seenImages)
                        val text = inlineHtmlToReadableText(inner)
                        if (text.isNotBlank()) out.add(HabrArticleBlock.Paragraph(text))
                    }
                }

                else -> {
                    // <p>
                    extractEmbeddedImages(inner, out, seenImages)
                    val text = inlineHtmlToReadableText(inner)
                    if (text.isNotBlank()) out.add(HabrArticleBlock.Paragraph(text))
                }
            }
        }

        if (cursor < html.length) {
            val tail = inlineHtmlToReadableText(html.substring(cursor))
            if (tail.isNotBlank()) {
                out.add(HabrArticleBlock.Paragraph(tail))
            }
        }
    }

    private fun extractEmbeddedImages(
        html: String,
        out: MutableList<HabrArticleBlock>,
        seenImages: MutableSet<String>
    ) {
        for (img in IMG_TAG_REGEX.findAll(html)) {
            val attrs = img.groupValues[1]
            val url = extractImageUrlFromAttrs(attrs) ?: continue
            if (seenImages.add(url)) {
                out.add(HabrArticleBlock.Image(url = url, caption = extractAttr(attrs, "alt")))
            }
        }
    }

    private fun extractImageUrlFromAttrs(attrs: String): String? {
        val dataSrc = extractAttr(attrs, "data-src")
        val src = extractAttr(attrs, "src")
        return normalizeImageUrl(dataSrc) ?: normalizeImageUrl(src)
    }

    private fun extractAttr(attrs: String, name: String): String? {
        val m = Regex("\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(attrs)
        return m?.groupValues?.get(1)?.let { HabrRssParser.decodeHtmlEntities(it).trim() }?.takeIf { it.isNotBlank() }
    }

    private fun inlineHtmlToReadableText(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val withCode = INLINE_CODE_REGEX.replace(rawHtml) { m ->
            val inner = HabrRssParser.cleanPlainText(m.groupValues[1])
            if (inner.isBlank()) "" else "`$inner`"
        }
        val withBreaks = withCode
            .replace(BR_REGEX, "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        val noTags = withBreaks.replace(ANY_TAG_REGEX, "")
        val decoded = HabrRssParser.decodeHtmlEntities(noTags).replace('\u00A0', ' ')
        return decoded.lines()
            .map { it.replace(MULTI_SPACE_REGEX, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }
}

object HabrNewsRepository {

    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private data class CachedFeed(
        val items: List<HabrNewsItem>,
        val fetchedAtMillis: Long
    )

    private val cache = ConcurrentHashMap<HabrFeedCategory, CachedFeed>()
    private val articleCache = ConcurrentHashMap<String, HabrArticleDetail>()
    private val imageCache = ConcurrentHashMap<String, ByteArray>()

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

    fun peekCachedArticle(url: String): HabrArticleDetail? = articleCache[url]

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

    /**
     * Loads the full Habr article for in-app reading (no external browser needed).
     * Uses Habr's JSON endpoint (`/kek/v2/articles/{id}/?fl=ru&hl=ru`) with HTML fallback.
     */
    suspend fun loadArticle(
        item: HabrNewsItem,
        forceRefresh: Boolean = false
    ): Result<HabrArticleDetail> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            articleCache[item.url]?.let { return@withContext Result.success(it) }
        }

        runCatching {
            val articleId = HabrArticleHtmlParser.extractArticleId(item.url)
            val detail = if (articleId != null) {
                runCatching { fetchArticleViaKekApi(articleId, item) }.getOrNull()
                    ?: fetchArticleViaHtmlPage(item)
            } else {
                fetchArticleViaHtmlPage(item)
            }

            if (articleCache.size > 40) articleCache.clear()
            articleCache[item.url] = detail
            detail
        }
    }

    suspend fun loadImageBytes(imageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        imageCache[imageUrl]?.let { return@withContext it }
        runCatching {
            val req = Request.Builder()
                .url(imageUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val bytes = resp.body?.bytes() ?: return@runCatching null
                if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) return@runCatching null
                if (imageCache.size > 40) imageCache.clear()
                imageCache[imageUrl] = bytes
                bytes
            }
        }.getOrNull()
    }

    private fun fetchArticleViaKekApi(articleId: String, fallbackItem: HabrNewsItem): HabrArticleDetail {
        val apiUrl = "https://habr.com/kek/v2/articles/$articleId/?fl=ru&hl=ru"
        val req = Request.Builder()
            .url(apiUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            .header("Accept", "application/json")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} от API Habr")
            }
            val bodyStr = resp.body?.string().orEmpty()
            val obj = JsonParser.parseString(bodyStr).asJsonObject

            val title = obj.optStr("titleHtml")
                ?.let { HabrRssParser.cleanPlainText(it) }
                ?.takeIf { it.isNotBlank() }
                ?: fallbackItem.title

            val authorObj = obj.optObj("author")
            val fullName = authorObj?.optStr("fullname")?.trim().orEmpty()
            val alias = authorObj?.optStr("alias")?.trim().orEmpty()
            val author = when {
                fullName.isNotBlank() && alias.isNotBlank() -> "$fullName (@$alias)"
                alias.isNotBlank() -> "@$alias"
                fullName.isNotBlank() -> fullName
                else -> fallbackItem.author
            }

            val publishedIso = obj.optStr("timePublished")
            val publishedEpoch = publishedIso?.let {
                runCatching { Instant.parse(it).toEpochMilli() }
                    .recoverCatching { ZonedDateTime.parse(it).toInstant().toEpochMilli() }
                    .getOrNull()
            } ?: fallbackItem.publishedAtEpochMillis
            val publishedLabel = HabrRssParser.formatMinskDate(publishedEpoch).ifBlank { fallbackItem.publishedLabel }

            val readingTime = obj.get("readingTime")?.takeIf { !it.isJsonNull }?.asInt

            val hubs = obj.getAsJsonArray("hubs")?.mapNotNull { el ->
                if (el.isJsonObject) el.asJsonObject.optStr("title")?.let { HabrRssParser.cleanPlainText(it) } else null
            }?.filter { it.isNotBlank() }?.take(5) ?: fallbackItem.tags

            val statsObj = obj.optObj("statistics")
            val viewsCount = statsObj?.get("readingCount")?.takeIf { !it.isJsonNull }?.asInt
            val score = statsObj?.get("score")?.takeIf { !it.isJsonNull }?.asInt

            val leadObj = obj.optObj("leadData")
            val leadImage = leadObj?.optStr("imageUrl")
                ?: leadObj?.optObj("image")?.optStr("url")

            val textHtml = obj.optStr("textHtml").orEmpty()
                .ifBlank { leadObj?.optStr("textHtml").orEmpty() }

            val blocks = HabrArticleHtmlParser.parseBlocks(textHtml, leadImage)
            if (blocks.isEmpty()) {
                throw IllegalStateException("Пустое тело статьи в ответе Habr API")
            }

            return HabrArticleDetail(
                url = fallbackItem.url,
                title = title,
                author = author,
                publishedLabel = publishedLabel,
                readingTimeMinutes = readingTime,
                hubs = hubs,
                viewsCount = viewsCount,
                score = score,
                blocks = blocks
            )
        }
    }

    private fun fetchArticleViaHtmlPage(item: HabrNewsItem): HabrArticleDetail {
        val req = Request.Builder()
            .url(item.url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} при загрузке статьи Habr")
            }
            val html = resp.body?.string().orEmpty()
            val bodySegment = extractHtmlArticleBody(html)
            val blocks = HabrArticleHtmlParser.parseBlocks(bodySegment)
                .ifEmpty {
                    listOf(HabrArticleBlock.Paragraph(item.summary.ifBlank { item.title }))
                }

            return HabrArticleDetail(
                url = item.url,
                title = item.title,
                author = item.author,
                publishedLabel = item.publishedLabel,
                readingTimeMinutes = null,
                hubs = item.tags,
                viewsCount = null,
                score = null,
                blocks = blocks
            )
        }
    }

    private fun extractHtmlArticleBody(fullHtml: String): String {
        val markers = listOf(
            "id=\"post-content-body\"",
            "class=\"article-formatted-body",
            "class=\"tm-article-body"
        )
        for (marker in markers) {
            val idx = fullHtml.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                val tagStart = fullHtml.lastIndexOf('<', idx).coerceAtLeast(0)
                val endArticle = fullHtml.indexOf("</article>", idx, ignoreCase = true)
                val endIdx = if (endArticle > idx) endArticle else (idx + 80_000).coerceAtMost(fullHtml.length)
                return fullHtml.substring(tagStart, endIdx)
            }
        }
        val articleMatch = Regex("<article\\b[^>]*>(.*?)</article>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(fullHtml)
        return articleMatch?.groupValues?.get(1).orEmpty()
    }

    private fun JsonObject.optStr(key: String): String? {
        val el = get(key) ?: return null
        return if (el.isJsonNull || !el.isJsonPrimitive) null else el.asString
    }

    private fun JsonObject.optObj(key: String): JsonObject? {
        val el = get(key) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
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
