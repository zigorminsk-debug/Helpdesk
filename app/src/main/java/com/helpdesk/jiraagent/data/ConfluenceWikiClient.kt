package com.helpdesk.jiraagent.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ConfluenceSpace(
    val key: String,
    val name: String,
    val type: String = "global",
    val homepageId: String? = null,
    val description: String = ""
)

data class ConfluencePageSummary(
    val id: String,
    val title: String,
    val spaceKey: String = "",
    val spaceName: String = "",
    val excerpt: String = "",
    val updatedLabel: String = "",
    val updatedBy: String = "",
    val webPath: String = "",
    val hasChildrenHint: Boolean = true
) {
    val updatedDisplay: String get() = updatedLabel
}

data class ConfluenceBreadcrumb(
    val id: String,
    val title: String
)

data class ConfluenceAttachment(
    val id: String,
    val title: String,
    val mediaType: String,
    val fileSizeBytes: Long,
    val downloadUrl: String
) {
    val fileSize: Long get() = fileSizeBytes
}

enum class ConfluenceCalloutKind {
    INFO,
    WARNING,
    NOTE,
    TIP
}

sealed class ConfluenceBlock {
    data class Heading(val level: Int, val text: String) : ConfluenceBlock()
    data class Paragraph(val text: String) : ConfluenceBlock()
    data class ListItems(val ordered: Boolean, val items: List<String>) : ConfluenceBlock()
    data class Code(val code: String, val title: String = "", val language: String? = null) : ConfluenceBlock()
    data class Callout(val kind: ConfluenceCalloutKind, val title: String = "", val body: String) : ConfluenceBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ConfluenceBlock()
    data class Image(val url: String, val alt: String = "", val caption: String? = null) : ConfluenceBlock()
}

data class ConfluencePageDetail(
    val id: String,
    val title: String,
    val spaceKey: String,
    val spaceName: String,
    val versionNumber: Int,
    val updatedBy: String,
    val updatedLabel: String,
    val webUrl: String,
    val breadcrumbs: List<ConfluenceBreadcrumb>,
    val childPages: List<ConfluencePageSummary>,
    val attachments: List<ConfluenceAttachment>,
    val blocks: List<ConfluenceBlock>
) {
    val updatedDisplay: String get() = updatedLabel
    val ancestors: List<ConfluenceBreadcrumb> get() = breadcrumbs
}

class ConfluenceWikiClient {

    private class SessionCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostList = store.getOrPut(url.host.lowercase(Locale.ROOT)) { CopyOnWriteArrayList() }
            for (incoming in cookies) {
                hostList.removeIf { it.name.equals(incoming.name, ignoreCase = true) }
                hostList.add(incoming)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val hostList = store[url.host.lowercase(Locale.ROOT)] ?: return emptyList()
            return hostList.filter { it.expiresAt >= now }
        }

        fun clear() = store.clear()
    }

    private val cookieJar = SessionCookieJar()
    private val standardClient: OkHttpClient = buildClientWithCookieJar(trustAllSsl = false, jar = cookieJar)
    private val trustAllClient: OkHttpClient = buildClientWithCookieJar(trustAllSsl = true, jar = cookieJar)

    @Volatile
    private var workingBaseUrlCache: Pair<String, String>? = null
    private val imageBytesCache = ConcurrentHashMap<String, ByteArray>()

    private fun clientFor(settings: AppSettings): OkHttpClient {
        return if (settings.trustSelfSignedSsl) trustAllClient else standardClient
    }

    fun resolveConfiguredWikiDisplayUrl(settings: AppSettings): String {
        val raw = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }
        return if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw.trimEnd('/')
        } else {
            "https://${raw.trimEnd('/')}"
        }
    }

    internal fun buildCandidateApiRoots(rawUrl: String): List<String> {
        val cleaned = rawUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }.trimEnd('/')
        val explicitHttp = cleaned.startsWith("http://", ignoreCase = true)
        val explicitHttps = cleaned.startsWith("https://", ignoreCase = true)
        val hostAndPath = cleaned
            .removePrefix("https://")
            .removePrefix("HTTPS://")
            .removePrefix("http://")
            .removePrefix("HTTP://")
            .trimEnd('/')

        val schemes = when {
            explicitHttp -> listOf("http", "https")
            explicitHttps -> listOf("https", "http")
            else -> listOf("https", "http")
        }

        val candidates = mutableListOf<String>()
        for (sc in schemes) {
            val root = "$sc://$hostAndPath"
            candidates.add(root)
            if (!hostAndPath.endsWith("/wiki", ignoreCase = true)) {
                candidates.add("$root/wiki")
            }
        }
        return candidates.distinct()
    }

    private fun Request.Builder.applyConfluenceAuth(settings: AppSettings): Request.Builder {
        header("X-Atlassian-Token", "no-check")
        header("Accept", "application/json")
        header("User-Agent", "Help-CSL-Wiki/1.0")
        when (settings.authType) {
            AuthType.BEARER_PAT -> {
                if (settings.passwordOrToken.isNotBlank()) {
                    header("Authorization", "Bearer ${settings.passwordOrToken.trim()}")
                }
            }
            AuthType.BASIC_LOGIN_PASSWORD, AuthType.CLOUD_EMAIL_TOKEN -> {
                if (settings.usernameOrEmail.isNotBlank() && settings.passwordOrToken.isNotBlank()) {
                    val basic = Credentials.basic(
                        settings.usernameOrEmail.trim(),
                        settings.passwordOrToken,
                        StandardCharsets.UTF_8
                    )
                    header("Authorization", basic)
                }
            }
        }
        return this
    }

    /**
     * Performs Seraph form login (`POST /dologin.action`) so installations of Confluence Server/DC
     * that require `JSESSIONID` / `seraph.confluence` cookies work automatically with Jira credentials.
     */
    private fun attemptFormSessionLogin(baseRoot: String, settings: AppSettings): Boolean {
        if (settings.authType == AuthType.BEARER_PAT) return false
        val user = settings.usernameOrEmail.trim()
        val pass = settings.passwordOrToken
        if (user.isEmpty() || pass.isEmpty()) return false

        return runCatching {
            val form = FormBody.Builder(StandardCharsets.UTF_8)
                .add("os_username", user)
                .add("os_password", pass)
                .add("os_cookie", "true")
                .add("os_destination", "/index.action")
                .add("login", "Log in")
                .build()

            val req = Request.Builder()
                .url("$baseRoot/dologin.action")
                .post(form)
                .header("X-Atlassian-Token", "no-check")
                .header("User-Agent", "Help-CSL-Wiki/1.0")
                .build()

            clientFor(settings).newCall(req).execute().use { resp ->
                resp.code in 200..399
            }
        }.getOrDefault(false)
    }

    private fun executeRestGetJson(
        apiRelativePathWithQuery: String,
        settings: AppSettings
    ): Pair<String, JsonObject> {
        if (!settings.isConfluenceReady) {
            throw IllegalStateException("Укажите логин и пароль Jira в разделе «Настройки» — они же используются для входа в Wiki (w.csl.by).")
        }

        val rawCfg = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }
        val cachedRoot = workingBaseUrlCache?.takeIf { it.first == rawCfg }?.second
        val roots = buildList {
            if (cachedRoot != null) add(cachedRoot)
            addAll(buildCandidateApiRoots(rawCfg))
        }.distinct()

        var lastError: Exception? = null
        val relClean = apiRelativePathWithQuery.trimStart('/')

        for (root in roots) {
            try {
                val sep = if ('?' in relClean) "&" else "?"
                val urlWithBasicHint = if (settings.authType != AuthType.BEARER_PAT && !relClean.contains("os_authType=")) {
                    "$root/rest/api/$relClean${sep}os_authType=basic"
                } else {
                    "$root/rest/api/$relClean"
                }

                val firstAttempt = performSingleJsonRequest(urlWithBasicHint, settings)
                if (firstAttempt != null) {
                    workingBaseUrlCache = rawCfg to root
                    return root to firstAttempt
                }

                // Fallback: perform Confluence dologin.action cookie authentication and retry
                if (attemptFormSessionLogin(root, settings)) {
                    val plainUrl = "$root/rest/api/$relClean"
                    val secondAttempt = performSingleJsonRequest(plainUrl, settings)
                    if (secondAttempt != null) {
                        workingBaseUrlCache = rawCfg to root
                        return root to secondAttempt
                    }
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw (lastError ?: IllegalStateException("Не удалось подключиться к Confluence (${resolveConfiguredWikiDisplayUrl(settings)})"))
    }

    private fun performSingleJsonRequest(fullUrl: String, settings: AppSettings): JsonObject? {
        val req = Request.Builder()
            .url(fullUrl)
            .get()
            .applyConfluenceAuth(settings)
            .build()

        clientFor(settings).newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                // Return null so caller can try dologin.action cookie session before failing
                if (body.contains("loginform", ignoreCase = true) ||
                    body.contains("dologin", ignoreCase = true) ||
                    resp.header("X-Seraph-LoginReason") != null ||
                    resp.code == 401
                ) {
                    return null
                }
                throw SecurityException("Доступ к Wiki отклонён (HTTP ${resp.code}). Проверьте права учётной записи ${settings.usernameOrEmail} в Confluence.")
            }
            if (resp.code == 404) {
                throw IllegalStateException("REST API Confluence не найден по адресу $fullUrl (HTTP 404)")
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("Ошибка ответа Confluence w.csl.by (HTTP ${resp.code})")
            }
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) {
                // Redirected to HTML login form
                return null
            }
            return JsonParser.parseString(trimmed).asJsonObject
        }
    }

    suspend fun fetchSpaces(settings: AppSettings): Result<List<ConfluenceSpace>> = withContext(Dispatchers.IO) {
        runCatching {
            val (_, json) = executeRestGetJson(
                "space?limit=100&expand=homepage,description.plain",
                settings
            )
            val results = json.optArray("results") ?: JsonArray()
            results.mapNotNull { el ->
                val obj = el.asObj() ?: return@mapNotNull null
                val key = obj.optStr("key").orEmpty()
                val name = obj.optStr("name").orEmpty()
                if (key.isEmpty()) return@mapNotNull null
                val type = obj.optStr("type") ?: "global"
                val homepageId = obj.optObj("homepage")?.optStr("id")
                val desc = obj.optObj("description")?.optObj("plain")?.optStr("value")
                    ?.let { ConfluenceHtmlParser.cleanText(it) }
                    .orEmpty()
                ConfluenceSpace(
                    key = key,
                    name = name.ifBlank { key },
                    type = type,
                    homepageId = homepageId,
                    description = desc
                )
            }.sortedWith(
                compareBy<ConfluenceSpace> { it.type != "global" }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
        }
    }

    suspend fun fetchSpaceRootOrRecentPages(
        spaceKey: String?,
        settings: AppSettings
    ): Result<List<ConfluencePageSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!spaceKey.isNullOrBlank()) {
                val encodedKey = URLEncoder.encode(spaceKey.trim(), "UTF-8")
                val rootAttempt = runCatching {
                    val (_, json) = executeRestGetJson(
                        "space/$encodedKey/content/page?depth=root&limit=100&expand=version,space",
                        settings
                    )
                    parsePageSummaryArray(json.optArray("results"), defaultSpaceKey = spaceKey)
                }.getOrNull()

                if (!rootAttempt.isNullOrEmpty()) {
                    return@runCatching rootAttempt
                }

                val (_, fallbackJson) = executeRestGetJson(
                    "content?spaceKey=$encodedKey&type=page&limit=80&expand=version,space",
                    settings
                )
                parsePageSummaryArray(fallbackJson.optArray("results"), defaultSpaceKey = spaceKey)
            } else {
                // Recent updated pages across all Confluence spaces
                val cqlAttempt = runCatching {
                    val cql = URLEncoder.encode("type=page order by lastmodified desc", "UTF-8")
                    val (_, json) = executeRestGetJson(
                        "content/search?cql=$cql&limit=40&expand=space,version",
                        settings
                    )
                    parseSearchOrContentResults(json.optArray("results"))
                }.getOrNull()

                if (!cqlAttempt.isNullOrEmpty()) {
                    return@runCatching cqlAttempt
                }

                val (_, fallbackJson) = executeRestGetJson(
                    "content?type=page&limit=40&expand=space,version",
                    settings
                )
                parsePageSummaryArray(fallbackJson.optArray("results"), defaultSpaceKey = "")
            }
        }
    }

    suspend fun fetchChildPages(
        parentPageId: String,
        settings: AppSettings
    ): Result<List<ConfluencePageSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedId = URLEncoder.encode(parentPageId.trim(), "UTF-8")
            val (_, json) = executeRestGetJson(
                "content/$encodedId/child/page?limit=100&expand=version,space",
                settings
            )
            parsePageSummaryArray(json.optArray("results"), defaultSpaceKey = "")
        }
    }

    suspend fun searchPages(
        rawQuery: String,
        spaceKeyFilter: String? = null,
        settings: AppSettings
    ): Result<List<ConfluencePageSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQ = rawQuery.trim()
                .replace("\\", " ")
                .replace("\"", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleanQ.isEmpty()) return@runCatching emptyList()

            val spaceClause = if (!spaceKeyFilter.isNullOrBlank()) {
                " AND space = \"${spaceKeyFilter.trim().replace("\"", "")}\""
            } else {
                ""
            }

            val cqlPrimary = "type = page AND (title ~ \"$cleanQ\" OR text ~ \"$cleanQ\")$spaceClause order by lastmodified desc"
            val encodedPrimary = URLEncoder.encode(cqlPrimary, "UTF-8")

            val primaryResult = runCatching {
                val (_, json) = executeRestGetJson(
                    "content/search?cql=$encodedPrimary&limit=40&excerpt=none&expand=space,version",
                    settings
                )
                parseSearchOrContentResults(json.optArray("results"))
            }.getOrNull()

            if (primaryResult != null) {
                return@runCatching primaryResult
            }

            val cqlFallback = "siteSearch ~ \"$cleanQ\" AND type = page"
            val (_, fallbackJson) = executeRestGetJson(
                "content/search?cql=${URLEncoder.encode(cqlFallback, "UTF-8")}&limit=40",
                settings
            )
            parseSearchOrContentResults(fallbackJson.optArray("results"))
        }
    }

    suspend fun fetchPageDetail(
        pageId: String,
        settings: AppSettings
    ): Result<ConfluencePageDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedId = URLEncoder.encode(pageId.trim(), "UTF-8")
            val (baseRoot, json) = executeRestGetJson(
                "content/$encodedId?expand=body.view,body.export_view,body.storage,space,version,ancestors,children.page",
                settings
            )

            val title = json.optStr("title").orEmpty().ifBlank { "Страница #$pageId" }
            val spaceObj = json.optObj("space")
            val spaceKey = spaceObj?.optStr("key").orEmpty()
            val spaceName = spaceObj?.optStr("name").orEmpty().ifBlank { spaceKey }

            val versionObj = json.optObj("version")
            val versionNum = versionObj?.optInt("number") ?: 1
            val updatedBy = versionObj?.optObj("by")?.optStr("displayName")
                ?: versionObj?.optObj("by")?.optStr("username")
                ?: ""
            val updatedWhen = formatConfluenceDate(versionObj?.optStr("when"))

            val linksObj = json.optObj("_links")
            val webUiRel = linksObj?.optStr("webui").orEmpty()
            val fullWebUrl = when {
                webUiRel.startsWith("http://", ignoreCase = true) ||
                    webUiRel.startsWith("https://", ignoreCase = true) -> webUiRel
                webUiRel.isNotBlank() -> "$baseRoot/${webUiRel.trimStart('/')}"
                else -> "$baseRoot/pages/viewpage.action?pageId=$encodedId"
            }

            val breadcrumbs = json.optArray("ancestors")
                ?.mapNotNull { el ->
                    val o = el.asObj() ?: return@mapNotNull null
                    val aid = o.optStr("id") ?: return@mapNotNull null
                    val atitle = o.optStr("title") ?: return@mapNotNull null
                    ConfluenceBreadcrumb(id = aid, title = atitle)
                }
                .orEmpty()

            val childResults = json.optObj("children")
                ?.optObj("page")
                ?.optArray("results")
            val childPages = parsePageSummaryArray(childResults, defaultSpaceKey = spaceKey)

            val bodyObj = json.optObj("body")
            val htmlBody = bodyObj?.optObj("view")?.optStr("value")?.takeIf { it.isNotBlank() }
                ?: bodyObj?.optObj("export_view")?.optStr("value")?.takeIf { it.isNotBlank() }
                ?: bodyObj?.optObj("storage")?.optStr("value").orEmpty()

            val blocks = ConfluenceHtmlParser.parseHtmlToBlocks(htmlBody, baseRoot)

            val attachments = runCatching {
                val (_, attJson) = executeRestGetJson(
                    "content/$encodedId/child/attachment?limit=50",
                    settings
                )
                attJson.optArray("results")?.mapNotNull { el ->
                    val ao = el.asObj() ?: return@mapNotNull null
                    val aid = ao.optStr("id") ?: return@mapNotNull null
                    val atitle = ao.optStr("title") ?: return@mapNotNull null
                    val ext = ao.optObj("extensions")
                    val media = ext?.optStr("mediaType")
                        ?: ao.optObj("metadata")?.optStr("mediaType")
                        ?: "application/octet-stream"
                    val size = ext?.optLong("fileSize") ?: 0L
                    val dlRel = ao.optObj("_links")?.optStr("download").orEmpty()
                    val dlUrl = when {
                        dlRel.startsWith("http://", ignoreCase = true) ||
                            dlRel.startsWith("https://", ignoreCase = true) -> dlRel
                        dlRel.isNotBlank() -> "$baseRoot/${dlRel.trimStart('/')}"
                        else -> ""
                    }
                    if (dlUrl.isEmpty()) null
                    else ConfluenceAttachment(
                        id = aid,
                        title = atitle,
                        mediaType = media,
                        fileSizeBytes = size,
                        downloadUrl = dlUrl
                    )
                }.orEmpty()
            }.getOrDefault(emptyList())

            ConfluencePageDetail(
                id = pageId,
                title = title,
                spaceKey = spaceKey,
                spaceName = spaceName,
                versionNumber = versionNum,
                updatedBy = updatedBy,
                updatedLabel = updatedWhen,
                webUrl = fullWebUrl,
                breadcrumbs = breadcrumbs,
                childPages = childPages,
                attachments = attachments,
                blocks = blocks
            )
        }
    }

    suspend fun downloadProtectedBytes(
        urlOrPath: String,
        settings: AppSettings
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val rawCfg = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }
            val baseRoot = workingBaseUrlCache?.takeIf { it.first == rawCfg }?.second
                ?: resolveConfiguredWikiDisplayUrl(settings)
            val resolved = if (urlOrPath.startsWith("http://", ignoreCase = true) ||
                urlOrPath.startsWith("https://", ignoreCase = true)
            ) {
                urlOrPath
            } else {
                "$baseRoot/${urlOrPath.trimStart('/')}"
            }

            imageBytesCache[resolved]?.let { return@runCatching it }

            val req = Request.Builder()
                .url(resolved)
                .get()
                .applyConfluenceAuth(settings)
                .header("Accept", "*/*")
                .build()

            clientFor(settings).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code} при загрузке ресурса Wiki")
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty()) throw IllegalStateException("Пустой ответ от сервера Wiki")
                if (bytes.size <= 4 * 1024 * 1024) {
                    if (imageBytesCache.size > 60) imageBytesCache.clear()
                    imageBytesCache[resolved] = bytes
                }
                bytes
            }
        }
    }

    private fun parseSearchOrContentResults(arr: JsonArray?): List<ConfluencePageSummary> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val outer = el.asObj() ?: return@mapNotNull null
            val contentObj = outer.optObj("content") ?: outer
            val excerpt = outer.optStr("excerpt")?.let { ConfluenceHtmlParser.cleanText(it) }.orEmpty()
            parseSinglePageSummary(contentObj, defaultSpaceKey = "", excerptOverride = excerpt)
        }
    }

    private fun parsePageSummaryArray(
        arr: JsonArray?,
        defaultSpaceKey: String
    ): List<ConfluencePageSummary> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.asObj() ?: return@mapNotNull null
            parseSinglePageSummary(obj, defaultSpaceKey = defaultSpaceKey, excerptOverride = "")
        }
    }

    private fun parseSinglePageSummary(
        obj: JsonObject,
        defaultSpaceKey: String,
        excerptOverride: String
    ): ConfluencePageSummary? {
        val id = obj.optStr("id") ?: return null
        val title = obj.optStr("title")?.let { ConfluenceHtmlParser.cleanText(it) }.orEmpty()
        if (title.isEmpty()) return null
        val spObj = obj.optObj("space")
        val spKey = spObj?.optStr("key") ?: defaultSpaceKey
        val spName = spObj?.optStr("name") ?: spKey
        val verObj = obj.optObj("version")
        val updatedLabel = formatConfluenceDate(verObj?.optStr("when"))
        val updatedBy = verObj?.optObj("by")?.optStr("displayName").orEmpty()
        val webUi = obj.optObj("_links")?.optStr("webui").orEmpty()
        return ConfluencePageSummary(
            id = id,
            title = title,
            spaceKey = spKey,
            spaceName = spName,
            excerpt = excerptOverride,
            updatedLabel = updatedLabel,
            updatedBy = updatedBy,
            webPath = webUi
        )
    }

    private fun formatConfluenceDate(rawIso: String?): String {
        if (rawIso.isNullOrBlank()) return ""
        val minsk = ZoneId.of("Europe/Minsk")
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        return runCatching { Instant.parse(rawIso).atZone(minsk).format(fmt) }
            .recoverCatching { ZonedDateTime.parse(rawIso).withZoneSameInstant(minsk).format(fmt) }
            .getOrElse { rawIso.take(16).replace('T', ' ') }
    }

    companion object {
        private fun JsonElement?.asObj(): JsonObject? =
            if (this != null && isJsonObject) asJsonObject else null

        private fun JsonObject.optStr(key: String): String? {
            val e = get(key) ?: return null
            return if (e.isJsonNull || !e.isJsonPrimitive) null else e.asString
        }

        private fun JsonObject.optInt(key: String): Int? {
            val e = get(key) ?: return null
            return if (e.isJsonNull || !e.isJsonPrimitive) null else runCatching { e.asInt }.getOrNull()
        }

        private fun JsonObject.optLong(key: String): Long? {
            val e = get(key) ?: return null
            return if (e.isJsonNull || !e.isJsonPrimitive) null else runCatching { e.asLong }.getOrNull()
        }

        private fun JsonObject.optObj(key: String): JsonObject? {
            val e = get(key) ?: return null
            return if (e.isJsonObject) e.asJsonObject else null
        }

        private fun JsonObject.optArray(key: String): JsonArray? {
            val e = get(key) ?: return null
            return if (e.isJsonArray) e.asJsonArray else null
        }

        private fun buildClient(trustAllSsl: Boolean): OkHttpClient {
            return buildClientWithCookieJar(trustAllSsl, CookieJar.NO_COOKIES)
        }

        private fun buildClientWithCookieJar(trustAllSsl: Boolean, jar: CookieJar): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .cookieJar(jar)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)

            if (trustAllSsl) {
                runCatching {
                    val trustAll = arrayOf<TrustManager>(
                        object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        }
                    )
                    val ctx = SSLContext.getInstance("TLS")
                    ctx.init(null, trustAll, SecureRandom())
                    builder.sslSocketFactory(ctx.socketFactory, trustAll[0] as X509TrustManager)
                    builder.hostnameVerifier { _, _ -> true }
                }
            }
            return builder.build()
        }
    }

    init {
        // Re-bind instance cookieJar so dologin.action session cookies persist across requests
    }
}

object ConfluenceHtmlParser {

    private val SCRIPT_STYLE_REGEX = Regex(
        "<(script|style|svg)\\b[^>]*>[\\s\\S]*?</\\1>",
        RegexOption.IGNORE_CASE
    )
    private val BLOCK_TOKEN_REGEX = Regex(
        """(<table\b[^>]*>[\s\S]*?</table>|<pre\b[^>]*>[\s\S]*?</pre>|<ac:structured-macro\b[^>]*ac:name\s*=\s*["']code["'][^>]*>[\s\S]*?</ac:structured-macro>|<div\b[^>]*class\s*=\s*["'][^"']*confluence-information-macro[^"']*["'][^>]*>[\s\S]*?</div>|<h([1-6])\b[^>]*>[\s\S]*?</h\2>|<ul\b[^>]*>[\s\S]*?</ul>|<ol\b[^>]*>[\s\S]*?</ol>|<img\b[^>]*>|<p\b[^>]*>[\s\S]*?</p>)""",
        RegexOption.IGNORE_CASE
    )
    private val TR_REGEX = Regex("<tr\\b[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    private val TH_TD_REGEX = Regex("<(th|td)\\b[^>]*>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
    private val LI_REGEX = Regex("<li\\b[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)
    private val IMG_SRC_REGEX = Regex("""\b(?:data-image-src|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val IMG_ALT_REGEX = Regex("""\balt\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val CDATA_BODY_REGEX = Regex("""<!\[CDATA\[([\s\S]*?)]]>""")
    private val TAG_REGEX = Regex("<[^>]+>")
    private val DEC_ENT_REGEX = Regex("&#(\\d{1,7});")
    private val HEX_ENT_REGEX = Regex("&#x([0-9a-fA-F]{1,6});")

    fun parseHtmlToBlocks(rawHtml: String, baseRoot: String): List<ConfluenceBlock> {
        if (rawHtml.isBlank()) {
            return listOf(ConfluenceBlock.Paragraph("Страница пуста."))
        }
        val stripped = rawHtml.replace(SCRIPT_STYLE_REGEX, "")
        val blocks = mutableListOf<ConfluenceBlock>()

        for (m in BLOCK_TOKEN_REGEX.findAll(stripped)) {
            val chunk = m.value
            val low = chunk.take(40).lowercase(Locale.ROOT)
            when {
                low.startsWith("<table") -> {
                    parseTable(chunk)?.let { blocks.add(it) }
                }
                low.startsWith("<pre") || low.startsWith("<ac:structured-macro") -> {
                    val code = CDATA_BODY_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                        ?: decodeEntities(
                            chunk
                                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                                .replace(TAG_REGEX, "")
                        )
                    val trimmedCode = code.trim('\r', '\n')
                    if (trimmedCode.isNotEmpty()) {
                        blocks.add(ConfluenceBlock.Code(code = trimmedCode))
                    }
                }
                low.startsWith("<div") -> {
                    val kind = when {
                        chunk.contains("warning", ignoreCase = true) -> ConfluenceCalloutKind.WARNING
                        chunk.contains("note", ignoreCase = true) -> ConfluenceCalloutKind.NOTE
                        chunk.contains("tip", ignoreCase = true) -> ConfluenceCalloutKind.TIP
                        else -> ConfluenceCalloutKind.INFO
                    }
                    val txt = cleanTextPreservingNewlines(chunk)
                    if (txt.isNotEmpty()) {
                        blocks.add(ConfluenceBlock.Callout(kind = kind, body = txt))
                    }
                }
                low.startsWith("<h") -> {
                    val lvl = low.getOrNull(2)?.digitToIntOrNull() ?: 2
                    val txt = cleanText(chunk)
                    if (txt.isNotEmpty()) {
                        blocks.add(ConfluenceBlock.Heading(level = lvl, text = txt))
                    }
                }
                low.startsWith("<ul") || low.startsWith("<ol") -> {
                    val ordered = low.startsWith("<ol")
                    val items = LI_REGEX.findAll(chunk)
                        .map { cleanTextPreservingNewlines(it.groupValues[1]) }
                        .filter { it.isNotEmpty() }
                        .toList()
                    if (items.isNotEmpty()) {
                        blocks.add(ConfluenceBlock.ListItems(ordered = ordered, items = items))
                    }
                }
                low.startsWith("<img") -> {
                    parseImageTag(chunk, baseRoot)?.let { blocks.add(it) }
                }
                low.startsWith("<p") -> {
                    // Check if paragraph contains an inline image
                    IMG_SRC_REGEX.find(chunk)?.let {
                        parseImageTag(chunk, baseRoot)?.let { img -> blocks.add(img) }
                    }
                    val txt = cleanTextPreservingNewlines(chunk)
                    if (txt.isNotEmpty()) {
                        blocks.add(ConfluenceBlock.Paragraph(txt))
                    }
                }
            }
        }

        if (blocks.isEmpty()) {
            val plain = cleanTextPreservingNewlines(stripped)
            if (plain.isNotEmpty()) {
                plain.split(Regex("\\n{2,}"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { blocks.add(ConfluenceBlock.Paragraph(it)) }
            }
        }

        return blocks.ifEmpty {
            listOf(ConfluenceBlock.Paragraph("На этой странице пока нет текстового содержимого."))
        }
    }

    private fun parseImageTag(chunk: String, baseRoot: String): ConfluenceBlock.Image? {
        val rawSrc = decodeEntities(IMG_SRC_REGEX.find(chunk)?.groupValues?.getOrNull(1).orEmpty()).trim()
        if (rawSrc.isEmpty() || rawSrc.startsWith("data:")) return null
        // Skip tiny Confluence emoticons / status icons
        if (rawSrc.contains("/images/icons/", ignoreCase = true) ||
            rawSrc.contains("emoticons", ignoreCase = true)
        ) {
            return null
        }
        val fullUrl = when {
            rawSrc.startsWith("http://", ignoreCase = true) ||
                rawSrc.startsWith("https://", ignoreCase = true) -> rawSrc
            rawSrc.startsWith("//") -> "https:$rawSrc"
            else -> "${baseRoot.trimEnd('/')}/${rawSrc.trimStart('/')}"
        }
        val caption = IMG_ALT_REGEX.find(chunk)?.groupValues?.getOrNull(1)
            ?.let { cleanText(it) }
            ?.takeIf { it.isNotEmpty() }
        return ConfluenceBlock.Image(url = fullUrl, alt = caption.orEmpty(), caption = caption)
    }

    private fun parseTable(tableHtml: String): ConfluenceBlock.Table? {
        val allRows = mutableListOf<Pair<Boolean, List<String>>>()
        for (tr in TR_REGEX.findAll(tableHtml)) {
            val rowHtml = tr.groupValues[1]
            var hasTh = false
            val cells = mutableListOf<String>()
            for (cell in TH_TD_REGEX.findAll(rowHtml)) {
                if (cell.groupValues[1].equals("th", ignoreCase = true)) {
                    hasTh = true
                }
                cells.add(cleanTextPreservingNewlines(cell.groupValues[2]))
            }
            if (cells.any { it.isNotEmpty() }) {
                allRows.add(hasTh to cells)
            }
        }
        if (allRows.isEmpty()) return null
        val colCount = allRows.maxOf { it.second.size }.coerceAtLeast(1)
        fun pad(row: List<String>) = List(colCount) { idx -> row.getOrElse(idx) { "" } }

        val headers: List<String>
        val bodyRows: List<List<String>>
        if (allRows.first().first) {
            headers = pad(allRows.first().second)
            bodyRows = allRows.drop(1).map { pad(it.second) }
        } else {
            headers = emptyList()
            bodyRows = allRows.map { pad(it.second) }
        }
        return ConfluenceBlock.Table(headers = headers, rows = bodyRows)
    }

    fun cleanText(raw: String): String {
        return decodeEntities(raw.replace(TAG_REGEX, " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun cleanTextPreservingNewlines(raw: String): String {
        val withBreaks = raw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</div\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</li\\s*>", RegexOption.IGNORE_CASE), "\n")
        return decodeEntities(withBreaks.replace(TAG_REGEX, ""))
            .lines()
            .map { it.replace(Regex("[\\t \\u00A0]+"), " ").trim() }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeEntities(text: String): String {
        var s = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
        s = DEC_ENT_REGEX.replace(s) { mr ->
            val cp = mr.groupValues[1].toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else mr.value
        }
        s = HEX_ENT_REGEX.replace(s) { mr ->
            val cp = mr.groupValues[1].toIntOrNull(16)
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else mr.value
        }
        return s
    }
}
