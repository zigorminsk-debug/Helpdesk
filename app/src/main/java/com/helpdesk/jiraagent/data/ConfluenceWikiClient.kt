package com.helpdesk.jiraagent.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.Collator
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
    val downloadUrl: String,
    val versionNumber: Int = 0
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

data class ConfluenceInlineTab(
    val id: String,
    val title: String,
    val blocks: List<ConfluenceBlock>
)

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
    val inlineTabs: List<ConfluenceInlineTab> = emptyList(),
    val attachments: List<ConfluenceAttachment>,
    val blocks: List<ConfluenceBlock>,
    val hasSubstantialBody: Boolean = true
) {
    val updatedDisplay: String get() = updatedLabel
    val ancestors: List<ConfluenceBreadcrumb> get() = breadcrumbs
}

class ConfluenceTwoFactorAuthException(message: String) : SecurityException(message)

class ConfluenceAccountRevokedException(message: String) : SecurityException(message)

sealed class ConfluenceAccessCheckResult {
    data class Authorized(val username: String) : ConfluenceAccessCheckResult()
    data class OfflineUnreachable(val reason: String) : ConfluenceAccessCheckResult()
    data class RequiresInitialWeb2fa(val message: String) : ConfluenceAccessCheckResult()
    data class AccountDisabledOrUnauthorized(val reason: String) : ConfluenceAccessCheckResult()
}

class ConfluenceWikiClient {

    /**
     * Optional callback supplied by Android `MainViewModel` to read live browser session cookies
     * (`CookieManager.getInstance().getCookie(url)`) from the embedded WebView after 2FA/SSO login.
     */
    @Volatile
    var externalCookieProvider: ((String) -> String?)? = null

    /**
     * Optional same-origin browser `fetch()` bridge supplied by Android WebView when authenticated
     * on `w.csl.by` (fallback if reverse-proxy / SameSite cookies are bound to the WebView context).
     */
    @Volatile
    var externalWebRestFetcher: ((String) -> String?)? = null

    private enum class AuthAttemptMode {
        CONFLUENCE_PAT,
        COOKIE_SESSION_ONLY,
        JIRA_BEARER_PAT,
        BASIC_CREDENTIALS
    }

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

        fun hasCookiesForUrl(rawUrl: String): Boolean {
            val httpUrl = rawUrl.toHttpUrlOrNull() ?: return false
            return loadForRequest(httpUrl).isNotEmpty()
        }

        fun clear() = store.clear()
    }

    private val cookieJar = SessionCookieJar()
    private val standardClient: OkHttpClient = buildBaseClientWithCookieJar(jar = cookieJar)
    private val trustAllClient: OkHttpClient = buildTrustAllWikiOverlay(standardClient)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val twoFactorHosts = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var workingBaseUrlCache: Pair<String, String>? = null
    private val imageBytesCache = ConcurrentHashMap<String, ByteArray>()

    private fun clientFor(settings: AppSettings): OkHttpClient {
        return if (settings.trustSelfSignedSsl) trustAllClient else standardClient
    }

    fun clearSessionCache() {
        cookieJar.clear()
        imageBytesCache.clear()
        workingBaseUrlCache = null
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

    private fun buildMergedCookieHeader(targetUrl: String): String? {
        val httpUrl = targetUrl.toHttpUrlOrNull()
        val originRoot = if (httpUrl != null) {
            val defaultPort = (httpUrl.scheme == "https" && httpUrl.port == 443) ||
                (httpUrl.scheme == "http" && httpUrl.port == 80)
            if (defaultPort) "${httpUrl.scheme}://${httpUrl.host}" else "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
        } else null

        val map = LinkedHashMap<String, String>()
        val jarCookies = httpUrl?.let { cookieJar.loadForRequest(it) }.orEmpty()
        for (c in jarCookies) {
            map[c.name] = c.value
        }

        fun parseCookieString(raw: String?) {
            if (raw.isNullOrBlank()) return
            raw.split(';').forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val k = part.substring(0, idx).trim()
                    val v = part.substring(idx + 1).trim()
                    if (k.isNotEmpty()) map[k] = v
                }
            }
        }

        if (originRoot != null) {
            parseCookieString(runCatching { externalCookieProvider?.invoke("$originRoot/") }.getOrNull())
            parseCookieString(runCatching { externalCookieProvider?.invoke(originRoot) }.getOrNull())
        }
        parseCookieString(runCatching { externalCookieProvider?.invoke(targetUrl) }.getOrNull())

        if (map.isEmpty()) return null
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun Request.Builder.applyModeHeaders(
        targetUrl: String,
        settings: AppSettings,
        mode: AuthAttemptMode,
        acceptHeader: String = "application/json"
    ): Request.Builder {
        header("X-Atlassian-Token", "no-check")
        header("Accept", acceptHeader)
        header("User-Agent", BROWSER_USER_AGENT)
        when (mode) {
            AuthAttemptMode.CONFLUENCE_PAT -> {
                header("Authorization", "Bearer ${settings.confluenceToken.trim()}")
            }
            AuthAttemptMode.COOKIE_SESSION_ONLY -> {
                // Intentionally NO Authorization header — Confluence 2FA rejects Basic Auth with HTTP 403!
                buildMergedCookieHeader(targetUrl)?.let { header("Cookie", it) }
            }
            AuthAttemptMode.JIRA_BEARER_PAT -> {
                header("Authorization", "Bearer ${settings.passwordOrToken.trim()}")
            }
            AuthAttemptMode.BASIC_CREDENTIALS -> {
                val basic = Credentials.basic(
                    settings.usernameOrEmail.trim(),
                    settings.passwordOrToken,
                    StandardCharsets.UTF_8
                )
                header("Authorization", basic)
            }
        }
        return this
    }

    /**
     * Establishes a Confluence browser session (`JSESSIONID` + `seraph.confluence`) without
     * triggering Confluence Data Center's Basic-Auth 2FA blocker.
     * Supports both Atlassian Two-Step Verification (`/rest/tsv/1.0/authenticate`) and
     * classic Seraph (`/dologin.action`), trying both full login (`ziv@csl.by`) and short AD login (`ziv`).
     */
    private fun attemptInteractiveFreeSessionLogin(
        baseRoot: String,
        testApiUrl: String,
        settings: AppSettings
    ): Pair<JsonObject?, Boolean> {
        if (settings.authType == AuthType.BEARER_PAT && settings.usernameOrEmail.isBlank()) {
            return null to false
        }
        val rawUser = settings.usernameOrEmail.trim()
        val pass = settings.passwordOrToken
        if (rawUser.isEmpty() || pass.isEmpty()) return null to false

        val candidateUsers = buildList {
            add(rawUser)
            if ('@' in rawUser) {
                val shortAd = rawUser.substringBefore('@').trim()
                if (shortAd.isNotEmpty()) add(shortAd)
            }
        }.distinct()

        var requiresInteractiveOtp = false

        // Warm up JSESSIONID cookie from /login.action (no Basic header!)
        runCatching {
            val warmReq = Request.Builder()
                .url("$baseRoot/login.action")
                .get()
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .build()
            clientFor(settings).newCall(warmReq).execute().close()
        }

        for (u in candidateUsers) {
            // 1. Try Confluence Data Center 8.2+/9.x Two-Step Verification login endpoint
            runCatching {
                val payload = JsonObject().apply {
                    addProperty("username", u)
                    addProperty("password", pass)
                    addProperty("rememberMe", true)
                }
                val tsvReq = Request.Builder()
                    .url("$baseRoot/rest/tsv/1.0/authenticate")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .header("X-Atlassian-Token", "no-check")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Origin", baseRoot)
                    .header("Referer", "$baseRoot/login.action")
                    .build()

                clientFor(settings).newCall(tsvReq).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (respBody.contains("twoStepVerification", ignoreCase = true) ||
                        respBody.contains("elevatedSecurity", ignoreCase = true) ||
                        respBody.contains("otp", ignoreCase = true) ||
                        respBody.contains("captcha", ignoreCase = true)
                    ) {
                        requiresInteractiveOtp = true
                    }
                }
            }

            performSingleJsonRequest(testApiUrl, settings, AuthAttemptMode.COOKIE_SESSION_ONLY).first?.let { okJson ->
                return okJson to false
            }

            // 2. Try standard Seraph /dologin.action (no Basic header!)
            runCatching {
                val form = FormBody.Builder(StandardCharsets.UTF_8)
                    .add("os_username", u)
                    .add("os_password", pass)
                    .add("os_cookie", "true")
                    .add("os_destination", "/index.action")
                    .add("login", "Log in")
                    .build()

                val dologinReq = Request.Builder()
                    .url("$baseRoot/dologin.action")
                    .post(form)
                    .header("X-Atlassian-Token", "no-check")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Origin", baseRoot)
                    .header("Referer", "$baseRoot/login.action")
                    .build()

                clientFor(settings).newCall(dologinReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (isTwoFactorBlockedResponse(body)) {
                        requiresInteractiveOtp = true
                    }
                }
            }

            performSingleJsonRequest(testApiUrl, settings, AuthAttemptMode.COOKIE_SESSION_ONLY).first?.let { okJson ->
                return okJson to false
            }
        }

        return null to requiresInteractiveOtp
    }

    private fun isTwoFactorBlockedResponse(body: String): Boolean {
        return body.contains("двухфакторн", ignoreCase = true) ||
            body.contains("two-factor", ignoreCase = true) ||
            body.contains("two-step", ignoreCase = true) ||
            body.contains("twoStepVerification", ignoreCase = true) ||
            (body.contains("<warning>", ignoreCase = true) && body.contains("<message>", ignoreCase = true))
    }

    private fun executeRestGetJson(
        apiRelativePathWithQuery: String,
        settings: AppSettings
    ): Pair<String, JsonObject> {
        if (!settings.isConfluenceReady) {
            throw IllegalStateException("Укажите логин и пароль Jira в разделе «Сервер» — они же используются для входа в Wiki (w.csl.by).")
        }

        val rawCfg = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }
        val cachedRoot = workingBaseUrlCache?.takeIf { it.first == rawCfg }?.second
        val roots = buildList {
            if (cachedRoot != null) add(cachedRoot)
            addAll(buildCandidateApiRoots(rawCfg))
        }.distinct()

        var lastError: Exception? = null
        var detected2faBlock = false
        val relClean = apiRelativePathWithQuery.trimStart('/')

        for (root in roots) {
            val hostKey = root.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty()
            val plainUrl = "$root/rest/api/$relClean"

            try {
                // 1. Dedicated Confluence Personal Access Token (Bearer PAT — works natively with 2FA!)
                if (settings.confluenceToken.isNotBlank()) {
                    val (patJson, pat2fa) = performSingleJsonRequest(plainUrl, settings, AuthAttemptMode.CONFLUENCE_PAT)
                    if (patJson != null) {
                        workingBaseUrlCache = rawCfg to root
                        return root to patJson
                    }
                    if (pat2fa) detected2faBlock = true
                }

                // 2. Existing WebView / CookieJar session (NO Basic Auth header — avoids 2FA HTTP 403!)
                if (buildMergedCookieHeader(plainUrl) != null || cookieJar.hasCookiesForUrl(plainUrl)) {
                    val (cookieJson, cookie2fa) = performSingleJsonRequest(
                        plainUrl,
                        settings,
                        AuthAttemptMode.COOKIE_SESSION_ONLY
                    )
                    if (cookieJson != null) {
                        workingBaseUrlCache = rawCfg to root
                        return root to cookieJson
                    }
                    if (cookie2fa) detected2faBlock = true
                }

                // 2b. Same-origin WebView fetch() bridge fallback (if WebView is active & authenticated)
                externalWebRestFetcher?.let { webFetcher ->
                    val rawWebJson = runCatching { webFetcher(relClean)?.trim() }.getOrNull()
                    if (!rawWebJson.isNullOrEmpty() && rawWebJson.startsWith("{")) {
                        val parsed = runCatching { JsonParser.parseString(rawWebJson).asJsonObject }.getOrNull()
                        if (parsed != null) {
                            workingBaseUrlCache = rawCfg to root
                            return root to parsed
                        }
                    }
                }

                // 3. Jira Bearer PAT (in case same SSO token works on Confluence)
                if (settings.authType == AuthType.BEARER_PAT && settings.passwordOrToken.isNotBlank()) {
                    val (bearerJson, bearer2fa) = performSingleJsonRequest(
                        plainUrl,
                        settings,
                        AuthAttemptMode.JIRA_BEARER_PAT
                    )
                    if (bearerJson != null) {
                        workingBaseUrlCache = rawCfg to root
                        return root to bearerJson
                    }
                    if (bearer2fa) detected2faBlock = true
                }

                // 4. Cookie-based form login (/rest/tsv/1.0/authenticate + /dologin.action, NO Basic header!)
                if (settings.usernameOrEmail.isNotBlank() && settings.passwordOrToken.isNotBlank()) {
                    val (sessionJson, otpNeeded) = attemptInteractiveFreeSessionLogin(root, plainUrl, settings)
                    if (sessionJson != null) {
                        workingBaseUrlCache = rawCfg to root
                        return root to sessionJson
                    }
                    if (otpNeeded) {
                        detected2faBlock = true
                        if (hostKey.isNotEmpty()) twoFactorHosts.add(hostKey)
                    }
                }

                // 5. Fallback HTTP Basic Auth ONLY if host hasn't flagged 2FA blocking
                if (settings.authType != AuthType.BEARER_PAT &&
                    settings.usernameOrEmail.isNotBlank() &&
                    settings.passwordOrToken.isNotBlank() &&
                    hostKey !in twoFactorHosts
                ) {
                    val sep = if ('?' in relClean) "&" else "?"
                    val basicUrl = if (!relClean.contains("os_authType=")) {
                        "$root/rest/api/$relClean${sep}os_authType=basic"
                    } else plainUrl

                    val (basicJson, basic2fa) = performSingleJsonRequest(
                        basicUrl,
                        settings,
                        AuthAttemptMode.BASIC_CREDENTIALS
                    )
                    if (basicJson != null) {
                        workingBaseUrlCache = rawCfg to root
                        return root to basicJson
                    }
                    if (basic2fa) {
                        detected2faBlock = true
                        if (hostKey.isNotEmpty()) twoFactorHosts.add(hostKey)
                    }
                }
            } catch (e: ConfluenceTwoFactorAuthException) {
                detected2faBlock = true
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (detected2faBlock) {
            throw ConfluenceTwoFactorAuthException(
                "На сервере w.csl.by включена двухфакторная аутентификация (2FA), которая блокирует прямой Basic-вход по паролю. " +
                    "Войдите один раз через вкладку «🌐 Web» (или создайте Personal Access Token в профиле w.csl.by)."
            )
        }

        throw (lastError ?: IllegalStateException(
            "Не удалось войти в Confluence (${resolveConfiguredWikiDisplayUrl(settings)}). Откройте режим «🌐 Web» или проверьте токен Confluence."
        ))
    }

    /**
     * Returns `(parsedJsonOrNull, sawTwoFactorBlock)`.
     * Never throws on 401/403 so caller can seamlessly try Cookie-session / PAT modes!
     */
    private fun performSingleJsonRequest(
        fullUrl: String,
        settings: AppSettings,
        mode: AuthAttemptMode
    ): Pair<JsonObject?, Boolean> {
        val req = Request.Builder()
            .url(fullUrl)
            .get()
            .applyModeHeaders(fullUrl, settings, mode)
            .build()

        clientFor(settings).newCall(req).execute().use { resp ->
            val aUser = resp.header("X-AUSERNAME")?.trim().orEmpty()
            val body = resp.body?.string().orEmpty()
            val saw2fa = isTwoFactorBlockedResponse(body)
            if (resp.code == 401 || resp.code == 403) {
                return null to saw2fa
            }
            if (resp.code == 404) {
                throw IllegalStateException("REST API Confluence не найден по адресу $fullUrl (HTTP 404)")
            }
            if (!resp.isSuccessful) {
                if (saw2fa) return null to true
                return null to false
            }
            if (aUser.equals("anonymous", ignoreCase = true)) {
                return null to saw2fa
            }
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) {
                return null to saw2fa
            }
            val parsed = JsonParser.parseString(trimmed).asJsonObject
            return parsed to false
        }
    }

    private fun fetchPaginatedPages(
        pathWithoutStartAndLimit: String,
        defaultSpaceKey: String,
        settings: AppSettings,
        pageSize: Int = 100,
        maxTotal: Int = 2000
    ): List<ConfluencePageSummary> {
        val collected = LinkedHashMap<String, ConfluencePageSummary>()
        var start = 0
        val sep = if ('?' in pathWithoutStartAndLimit) '&' else '?'
        while (start < maxTotal) {
            val (_, json) = executeRestGetJson(
                "$pathWithoutStartAndLimit${sep}limit=$pageSize&start=$start",
                settings
            )
            val results = json.optArray("results") ?: break
            val batch = parsePageSummaryArray(results, defaultSpaceKey = defaultSpaceKey)
            for (p in batch) {
                if (!collected.containsKey(p.id)) {
                    collected[p.id] = p
                }
            }
            val rawCount = results.size()
            val hasNextLink = json.optObj("_links")?.optStr("next")?.isNotBlank() == true
            if (rawCount < pageSize && !hasNextLink) break
            if (rawCount == 0) break
            start += rawCount
        }
        return collected.values.toList()
    }

    suspend fun fetchSpaces(settings: AppSettings): Result<List<ConfluenceSpace>> = withContext(Dispatchers.IO) {
        runCatching {
            val allSpaces = LinkedHashMap<String, ConfluenceSpace>()
            var start = 0
            val limit = 100
            while (start < 1000) {
                val (_, json) = executeRestGetJson(
                    "space?limit=$limit&start=$start&expand=homepage,description.plain",
                    settings
                )
                val results = json.optArray("results") ?: JsonArray()
                for (el in results) {
                    val obj = el.asObj() ?: continue
                    val key = obj.optStr("key").orEmpty()
                    val name = obj.optStr("name").orEmpty()
                    if (key.isEmpty()) continue
                    val type = obj.optStr("type") ?: "global"
                    val homepageId = obj.optObj("homepage")?.optStr("id")
                    val desc = obj.optObj("description")?.optObj("plain")?.optStr("value")
                        ?.let { ConfluenceHtmlParser.cleanText(it) }
                        .orEmpty()
                    allSpaces[key] = ConfluenceSpace(
                        key = key,
                        name = name.ifBlank { key },
                        type = type,
                        homepageId = homepageId,
                        description = desc
                    )
                }
                val hasNext = json.optObj("_links")?.optStr("next")?.isNotBlank() == true
                if (results.size() < limit && !hasNext) break
                if (results.size() == 0) throw IllegalStateException("Пустая страница списка пространств Wiki")
                start += results.size()
                if (start >= 1000) throw IllegalStateException("Достигнут предел 1000 пространств Wiki; синхронизация неполная")
            }

            val ruCollator = Collator.getInstance(Locale("ru", "RU")).apply {
                strength = Collator.PRIMARY
            }
            allSpaces.values.sortedWith { a, b ->
                val aPrimary = a.key.equals("CSL", ignoreCase = true)
                val bPrimary = b.key.equals("CSL", ignoreCase = true)
                if (aPrimary != bPrimary) {
                    if (aPrimary) -1 else 1
                } else {
                    val aGlobal = a.type == "global"
                    val bGlobal = b.type == "global"
                    if (aGlobal != bGlobal) {
                        if (aGlobal) -1 else 1
                    } else {
                        ruCollator.compare(
                            normalizeAlphabetSortKey(a.name.ifBlank { a.key }),
                            normalizeAlphabetSortKey(b.name.ifBlank { b.key })
                        )
                    }
                }
            }
        }
    }

    suspend fun fetchSpaceRootOrRecentPages(
        spaceKey: String?,
        settings: AppSettings,
        homepageIdHint: String? = null
    ): Result<List<ConfluencePageSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!spaceKey.isNullOrBlank() && spaceKey != RECENT_PSEUDO_SPACE_KEY) {
                val cleanKey = spaceKey.trim()
                val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")

                // 1. Resolve space homepage ID (in Confluence Data Center, top-level sidebar items are children of homepage!)
                val homepageId = homepageIdHint?.takeIf { it.isNotBlank() } ?: runCatching {
                    val (_, spJson) = executeRestGetJson("space/$encodedKey?expand=homepage", settings)
                    spJson.optObj("homepage")?.optStr("id")
                }.getOrNull()

                val combined = LinkedHashMap<String, ConfluencePageSummary>()

                if (!homepageId.isNullOrBlank()) {
                    val encodedHome = URLEncoder.encode(homepageId, "UTF-8")
                    val homeChildren = runCatching {
                        fetchPaginatedPages(
                            pathWithoutStartAndLimit = "content/$encodedHome/child/page?expand=version,space",
                            defaultSpaceKey = cleanKey,
                            settings = settings
                        )
                    }.getOrDefault(emptyList())
                    for (p in homeChildren) {
                        combined[p.id] = p
                    }
                }

                val rootPages = runCatching {
                    fetchPaginatedPages(
                        pathWithoutStartAndLimit = "space/$encodedKey/content/page?depth=root&expand=version,space",
                        defaultSpaceKey = cleanKey,
                        settings = settings
                    )
                }.getOrDefault(emptyList())

                for (rp in rootPages) {
                    if (rp.id != homepageId && !combined.containsKey(rp.id)) {
                        combined[rp.id] = rp
                    }
                }

                // If the space only has 1 single container page (e.g. a single root homepage or "Клиенты" wrapper),
                // automatically unpack its children so all client records appear immediately like on the site!
                if (combined.size == 1) {
                    val single = combined.values.first()
                    val unpacked = runCatching {
                        val encId = URLEncoder.encode(single.id, "UTF-8")
                        fetchPaginatedPages(
                            pathWithoutStartAndLimit = "content/$encId/child/page?expand=version,space",
                            defaultSpaceKey = cleanKey,
                            settings = settings
                        )
                    }.getOrDefault(emptyList())
                    if (unpacked.isNotEmpty()) {
                        return@runCatching sortPagesAlphabetically(unpacked)
                    }
                }

                if (combined.isNotEmpty()) {
                    return@runCatching sortPagesAlphabetically(combined.values.toList())
                }

                if (rootPages.isNotEmpty()) {
                    // Space has only homepage with 0 child pages — still unpack if homepage has children
                    if (rootPages.size == 1) {
                        val encId = URLEncoder.encode(rootPages.first().id, "UTF-8")
                        val sub = runCatching {
                            fetchPaginatedPages(
                                pathWithoutStartAndLimit = "content/$encId/child/page?expand=version,space",
                                defaultSpaceKey = cleanKey,
                                settings = settings
                            )
                        }.getOrDefault(emptyList())
                        if (sub.isNotEmpty()) return@runCatching sortPagesAlphabetically(sub)
                    }
                    return@runCatching sortPagesAlphabetically(rootPages)
                }

                val fallback = fetchPaginatedPages(
                    pathWithoutStartAndLimit = "content?spaceKey=$encodedKey&type=page&expand=version,space",
                    defaultSpaceKey = cleanKey,
                    settings = settings
                )
                sortPagesAlphabetically(fallback)
            } else {
                // Recent updated pages across all Confluence spaces (only when explicitly requested)
                val cqlAttempt = runCatching {
                    val cql = URLEncoder.encode("type=page order by lastmodified desc", "UTF-8")
                    val (_, json) = executeRestGetJson(
                        "content/search?cql=$cql&limit=50&expand=space,version",
                        settings
                    )
                    parseSearchOrContentResults(json.optArray("results"))
                }.getOrNull()

                if (!cqlAttempt.isNullOrEmpty()) {
                    return@runCatching cqlAttempt
                }

                val (_, fallbackJson) = executeRestGetJson(
                    "content?type=page&limit=50&expand=space,version",
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
            val children = fetchPaginatedPages(
                pathWithoutStartAndLimit = "content/$encodedId/child/page?expand=version,space",
                defaultSpaceKey = "",
                settings = settings
            )
            sortPagesAlphabetically(children)
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

            val effectiveSpace = spaceKeyFilter?.takeIf { it.isNotBlank() && it != RECENT_PSEUDO_SPACE_KEY }
            val spaceClause = if (effectiveSpace != null) {
                " AND space = \"${effectiveSpace.trim().replace("\"", "")}\""
            } else {
                ""
            }

            val cqlPrimary = "type = page AND (title ~ \"$cleanQ\" OR text ~ \"$cleanQ\")$spaceClause"
            val encodedPrimary = URLEncoder.encode(cqlPrimary, "UTF-8")

            val primaryResult = runCatching {
                val (_, json) = executeRestGetJson(
                    "content/search?cql=$encodedPrimary&limit=80&excerpt=none&expand=space,version",
                    settings
                )
                parseSearchOrContentResults(json.optArray("results"))
            }.getOrNull()

            if (primaryResult != null) {
                return@runCatching sortPagesAlphabetically(primaryResult)
            }

            val cqlFallback = "siteSearch ~ \"$cleanQ\" AND type = page"
            val (_, fallbackJson) = executeRestGetJson(
                "content/search?cql=${URLEncoder.encode(cqlFallback, "UTF-8")}&limit=80",
                settings
            )
            sortPagesAlphabetically(parseSearchOrContentResults(fallbackJson.optArray("results")))
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
            val inlineExpandedChildren = parsePageSummaryArray(childResults, defaultSpaceKey = spaceKey)
            val fullChildren = runCatching {
                fetchPaginatedPages(
                    pathWithoutStartAndLimit = "content/$encodedId/child/page?expand=version,space",
                    defaultSpaceKey = spaceKey,
                    settings = settings
                )
            }.getOrNull()
            val childPages = sortPagesAlphabetically(
                if (!fullChildren.isNullOrEmpty()) fullChildren else inlineExpandedChildren
            )

            val bodyObj = json.optObj("body")
            val htmlBody = bodyObj?.optObj("view")?.optStr("value")?.takeIf { it.isNotBlank() }
                ?: bodyObj?.optObj("export_view")?.optStr("value")?.takeIf { it.isNotBlank() }
                ?: bodyObj?.optObj("storage")?.optStr("value").orEmpty()

            val inlineTabs = ConfluenceHtmlParser.parseInlineTabs(htmlBody, baseRoot)
            val parsedBlocks = ConfluenceHtmlParser.parseHtmlToBlocks(
                rawHtml = htmlBody,
                baseRoot = baseRoot,
                hasNativeChildPages = childPages.isNotEmpty()
            )

            val attachments = fetchPageAttachments(pageId, settings).getOrDefault(emptyList())

            val hasRealBody = attachments.isNotEmpty() || inlineTabs.isNotEmpty() || parsedBlocks.any { b ->
                when (b) {
                    is ConfluenceBlock.Paragraph ->
                        b.text != ConfluenceHtmlParser.EMPTY_PAGE_PLACEHOLDER &&
                            b.text != ConfluenceHtmlParser.NO_TEXT_PLACEHOLDER
                    else -> true
                }
            }

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
                inlineTabs = inlineTabs,
                attachments = attachments,
                blocks = parsedBlocks,
                hasSubstantialBody = hasRealBody
            )
        }
    }

    /**
     * Checks whether `w.csl.by` is genuinely reachable AND whether the current user's account
     * is authorized.
     * - If `w.csl.by` is unreachable (offline, no VPN, DNS/TCP/5xx error, captive portal): returns [ConfluenceAccessCheckResult.OfflineUnreachable].
     * - If `w.csl.by` IS reachable (confirmed Confluence instance) and authentication fails
     *   (account disabled, credentials/token revoked, HTTP 401/403, Seraph `AUTHENTICATED_FAILED` / `AUTHENTICATION_DENIED`,
     *   or anonymous fallback when an offline database already exists): returns [ConfluenceAccessCheckResult.AccountDisabledOrUnauthorized].
     */
    suspend fun verifyConfluenceAccountAccess(
        settings: AppSettings,
        hasExistingOfflineDatabase: Boolean
    ): ConfluenceAccessCheckResult = withContext(Dispatchers.IO) {
        if (!settings.isConfluenceReady) {
            return@withContext ConfluenceAccessCheckResult.OfflineUnreachable("Учётные данные не настроены")
        }

        val rawCfg = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }
        val cachedRoot = workingBaseUrlCache?.takeIf { it.first == rawCfg }?.second
        val roots = buildList {
            if (cachedRoot != null) add(cachedRoot)
            addAll(buildCandidateApiRoots(rawCfg))
        }.distinct()

        var confirmedConfluenceReachable = false
        var explicitAuthDenialReason: String? = null
        var sawInteractive2faChallenge = false

        for (root in roots) {
            val spaceProbeUrl = "$root/rest/api/space?limit=5"

            fun checkSpaceProbe(mode: AuthAttemptMode): String? {
                val req = Request.Builder()
                    .url(spaceProbeUrl)
                    .get()
                    .applyModeHeaders(spaceProbeUrl, settings, mode)
                    .build()
                clientFor(settings).newCall(req).execute().use { resp ->
                    if (resp.code in 500..599) return null
                    val seraph = resp.header("X-Seraph-LoginReason").orEmpty()
                    val aUserRaw = resp.header("X-AUSERNAME")?.trim().orEmpty()
                    val hasConfluenceHdr = resp.header("X-Confluence-Request-Time") != null ||
                        seraph.isNotEmpty() ||
                        aUserRaw.isNotEmpty()
                    val body = resp.body?.string().orEmpty()

                    if (hasConfluenceHdr || body.contains("Confluence", ignoreCase = true) || body.contains("\"results\"", ignoreCase = true)) {
                        if (resp.code != 404) {
                            confirmedConfluenceReachable = true
                        }
                    }
                    if (isTwoFactorBlockedResponse(body)) {
                        sawInteractive2faChallenge = true
                    }
                    if (seraph.contains("AUTHENTICATION_DENIED", ignoreCase = true) ||
                        seraph.contains("AUTHENTICATED_FAILED", ignoreCase = true)
                    ) {
                        explicitAuthDenialReason = "Сервер w.csl.by отклонил учётную запись ($seraph)"
                        return null
                    }
                    if (resp.code == 401 || resp.code == 403) {
                        if (mode == AuthAttemptMode.CONFLUENCE_PAT || mode == AuthAttemptMode.JIRA_BEARER_PAT) {
                            explicitAuthDenialReason = "Токен доступа Confluence отклонён или учётная запись отключена (HTTP ${resp.code})"
                        }
                        return null
                    }
                    if (!resp.isSuccessful) return null
                    if (aUserRaw.equals("anonymous", ignoreCase = true)) return null

                    val trimmed = body.trim()
                    if (!trimmed.startsWith("{")) return null
                    val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull() ?: return null
                    val results = obj.optArray("results") ?: return null
                    val decodedUser = runCatching { URLDecoder.decode(aUserRaw, "UTF-8") }.getOrDefault(aUserRaw)
                    if (decodedUser.isNotBlank() || results.size() > 0) {
                        return decodedUser.ifBlank { settings.usernameOrEmail.trim().ifBlank { "authorized" } }
                    }
                    return null
                }
            }

            try {
                // 1. Confluence PAT
                if (settings.confluenceToken.isNotBlank()) {
                    checkSpaceProbe(AuthAttemptMode.CONFLUENCE_PAT)?.let { u ->
                        workingBaseUrlCache = rawCfg to root
                        return@withContext ConfluenceAccessCheckResult.Authorized(u)
                    }
                }

                // 2. WebView / CookieJar session (used after 2FA login in Web tab!)
                if (buildMergedCookieHeader(spaceProbeUrl) != null || cookieJar.hasCookiesForUrl(spaceProbeUrl)) {
                    checkSpaceProbe(AuthAttemptMode.COOKIE_SESSION_ONLY)?.let { u ->
                        workingBaseUrlCache = rawCfg to root
                        return@withContext ConfluenceAccessCheckResult.Authorized(u)
                    }
                }

                // 2b. Same-origin WebView fetch() bridge fallback
                externalWebRestFetcher?.let { webFetcher ->
                    val rawWebJson = runCatching { webFetcher("space?limit=5")?.trim() }.getOrNull()
                    if (!rawWebJson.isNullOrEmpty() && rawWebJson.startsWith("{")) {
                        val parsed = runCatching { JsonParser.parseString(rawWebJson).asJsonObject }.getOrNull()
                        val results = parsed?.optArray("results")
                        if (results != null && results.size() > 0) {
                            workingBaseUrlCache = rawCfg to root
                            return@withContext ConfluenceAccessCheckResult.Authorized(
                                settings.usernameOrEmail.trim().ifBlank { "authorized" }
                            )
                        }
                    }
                }

                // 3. Jira Bearer PAT
                if (settings.authType == AuthType.BEARER_PAT && settings.passwordOrToken.isNotBlank()) {
                    checkSpaceProbe(AuthAttemptMode.JIRA_BEARER_PAT)?.let { u ->
                        workingBaseUrlCache = rawCfg to root
                        return@withContext ConfluenceAccessCheckResult.Authorized(u)
                    }
                }

                // 4. Interactive-free session login (/rest/tsv/1.0/authenticate + /dologin.action)
                if (settings.usernameOrEmail.isNotBlank() && settings.passwordOrToken.isNotBlank()) {
                    val (sessionJson, otpNeeded) = attemptInteractiveFreeSessionLogin(root, spaceProbeUrl, settings)
                    if (sessionJson != null) {
                        workingBaseUrlCache = rawCfg to root
                        return@withContext ConfluenceAccessCheckResult.Authorized(settings.usernameOrEmail.trim())
                    }
                    if (otpNeeded) {
                        sawInteractive2faChallenge = true
                    }

                    // Check explicit Seraph login reason on /dologin.action to detect disabled accounts
                    val rawUser = settings.usernameOrEmail.trim()
                    val form = FormBody.Builder(StandardCharsets.UTF_8)
                        .add("os_username", rawUser)
                        .add("os_password", settings.passwordOrToken)
                        .add("os_cookie", "true")
                        .add("os_destination", "/index.action")
                        .add("login", "Log in")
                        .build()
                    val loginReq = Request.Builder()
                        .url("$root/dologin.action")
                        .post(form)
                        .header("X-Atlassian-Token", "no-check")
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .header("Origin", root)
                        .header("Referer", "$root/login.action")
                        .build()
                    clientFor(settings).newCall(loginReq).execute().use { resp ->
                        if (resp.code !in 500..599 && resp.code != 404) {
                            val seraph = resp.header("X-Seraph-LoginReason").orEmpty()
                            val body = resp.body?.string().orEmpty()
                            val finalUrl = resp.request.url.toString()
                            if (seraph.isNotEmpty() || body.contains("Confluence", ignoreCase = true)) {
                                confirmedConfluenceReachable = true
                            }
                            if (seraph.contains("AUTHENTICATION_DENIED", ignoreCase = true) ||
                                seraph.contains("AUTHENTICATED_FAILED", ignoreCase = true) ||
                                body.contains("aui-message-error", ignoreCase = true) ||
                                body.contains("login-error", ignoreCase = true)
                            ) {
                                explicitAuthDenialReason = "Учётная запись отключена или пароль отклонён на w.csl.by"
                            }
                            if (isTwoFactorBlockedResponse(body) ||
                                finalUrl.contains("two-step", ignoreCase = true) ||
                                finalUrl.contains("elevatedsecurity", ignoreCase = true)
                            ) {
                                sawInteractive2faChallenge = true
                            }
                        }
                    }

                    checkSpaceProbe(AuthAttemptMode.COOKIE_SESSION_ONLY)?.let { okUser ->
                        workingBaseUrlCache = rawCfg to root
                        return@withContext ConfluenceAccessCheckResult.Authorized(okUser)
                    }

                    val hostKey = root.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty()
                    if (settings.authType != AuthType.BEARER_PAT && hostKey !in twoFactorHosts && !sawInteractive2faChallenge) {
                        checkSpaceProbe(AuthAttemptMode.BASIC_CREDENTIALS)?.let { okUser ->
                            workingBaseUrlCache = rawCfg to root
                            return@withContext ConfluenceAccessCheckResult.Authorized(okUser)
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // Try next candidate root; if all fail, confirmedConfluenceReachable stays false
            } catch (_: Exception) {
                // Continue evaluating remaining roots
            }
        }

        if (!confirmedConfluenceReachable) {
            val firstRoot = roots.firstOrNull()
            val statusReachable = if (firstRoot != null) {
                runCatching {
                    val req = Request.Builder()
                        .url("$firstRoot/status")
                        .get()
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        resp.code !in 500..599 && resp.body?.string().orEmpty().contains("\"RUNNING\"", ignoreCase = true)
                    }
                }.getOrDefault(false)
            } else false

            if (!statusReachable) {
                return@withContext ConfluenceAccessCheckResult.OfflineUnreachable(
                    "Сервер w.csl.by сейчас недоступен (работает офлайн-режим)"
                )
            }
        }

        if (!hasExistingOfflineDatabase && explicitAuthDenialReason == null && sawInteractive2faChallenge) {
            return@withContext ConfluenceAccessCheckResult.RequiresInitialWeb2fa(
                "Для первой загрузки офлайн-базы w.csl.by войдите один раз через вкладку «🌐 Web» или укажите PAT-токен."
            )
        }

        return@withContext ConfluenceAccessCheckResult.AccountDisabledOrUnauthorized(
            explicitAuthDenialReason
                ?: "Сервер w.csl.by доступен, но авторизация учётной записи не прошла (учётная запись отключена или сессия отозвана)."
        )
    }

    /**
     * Bulk-downloads ALL pages in a Confluence space with full HTML body, ancestors, and attachments
     * for offline storage, reconstructing the alphabetical parent -> child hierarchy locally.
     */
    suspend fun fetchAllSpacePagesWithDetails(
        spaceKey: String,
        spaceNameHint: String,
        settings: AppSettings,
        onBatchProgress: (downloadedInSpace: Int, totalInSpace: Int) -> Unit = { _, _ -> }
    ): Result<List<ConfluencePageDetail>> = withContext(Dispatchers.IO) {
        runCatching {
            val encKey = URLEncoder.encode(spaceKey.trim(), "UTF-8")
            val expectedSummaries = runCatching {
                fetchPaginatedPages(
                    pathWithoutStartAndLimit = "content?spaceKey=$encKey&type=page&status=current",
                    defaultSpaceKey = spaceKey,
                    settings = settings,
                    pageSize = 100,
                    maxTotal = 5000
                )
            }.getOrDefault(emptyList())
            val expectedTotal = expectedSummaries.size

            val rawPagesById = LinkedHashMap<String, ConfluencePageDetail>()
            val batchLimit = 20
            var start = 0
            onBatchProgress(0, expectedTotal)

            while (start < 5000) {
                val (baseRoot, json) = try {
                    executeRestGetJson(
                        "content?spaceKey=$encKey&type=page&status=current&expand=body.view,version,ancestors,space,children.attachment&limit=$batchLimit&start=$start",
                        settings
                    )
                } catch (_: Exception) {
                    executeRestGetJson(
                        "content?spaceKey=$encKey&type=page&status=current&expand=body.storage,version,ancestors,space,children.attachment&limit=$batchLimit&start=$start",
                        settings
                    )
                }

                val results = json.optArray("results") ?: break
                val rawCount = results.size()
                if (rawCount == 0) break

                for (el in results) {
                    val obj = el.asObj() ?: continue
                    val pid = obj.optStr("id") ?: continue
                    val title = obj.optStr("title").orEmpty().ifBlank { "Страница #$pid" }
                    val spObj = obj.optObj("space")
                    val resolvedSpaceKey = spObj?.optStr("key")?.takeIf { it.isNotBlank() } ?: spaceKey
                    val resolvedSpaceName = spObj?.optStr("name")?.takeIf { it.isNotBlank() }
                        ?: spaceNameHint.ifBlank { resolvedSpaceKey }

                    val verObj = obj.optObj("version")
                    val verNum = verObj?.optInt("number") ?: 1
                    val updatedBy = verObj?.optObj("by")?.optStr("displayName")
                        ?: verObj?.optObj("by")?.optStr("username")
                        ?: ""
                    val updatedWhen = formatConfluenceDate(verObj?.optStr("when"))

                    val webUiRel = obj.optObj("_links")?.optStr("webui").orEmpty()
                    val fullWebUrl = when {
                        webUiRel.startsWith("http://", ignoreCase = true) ||
                            webUiRel.startsWith("https://", ignoreCase = true) -> webUiRel
                        webUiRel.isNotBlank() -> "$baseRoot/${webUiRel.trimStart('/')}"
                        else -> "$baseRoot/pages/viewpage.action?pageId=$pid"
                    }

                    val breadcrumbs = obj.optArray("ancestors")
                        ?.mapNotNull { aEl ->
                            val ao = aEl.asObj() ?: return@mapNotNull null
                            val aid = ao.optStr("id") ?: return@mapNotNull null
                            val atitle = ao.optStr("title") ?: return@mapNotNull null
                            ConfluenceBreadcrumb(id = aid, title = atitle)
                        }
                        .orEmpty()

                    val bodyObj = obj.optObj("body")
                    val htmlBody = bodyObj?.optObj("view")?.optStr("value")?.takeIf { it.isNotBlank() }
                        ?: bodyObj?.optObj("export_view")?.optStr("value")?.takeIf { it.isNotBlank() }
                        ?: bodyObj?.optObj("storage")?.optStr("value").orEmpty()

                    val inlineTabs = ConfluenceHtmlParser.parseInlineTabs(htmlBody, baseRoot)
                    // Parse temporarily with hasNativeChildPages = true for childpages-macro cleanup if children exist later
                    val tempBlocks = ConfluenceHtmlParser.parseHtmlToBlocks(
                        rawHtml = htmlBody,
                        baseRoot = baseRoot,
                        hasNativeChildPages = true
                    )

                    val attachments = obj.optObj("children")
                        ?.optObj("attachment")
                        ?.optArray("results")
                        ?.mapNotNull { attEl ->
                            val ao = attEl.asObj() ?: return@mapNotNull null
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
                                downloadUrl = dlUrl,
                                versionNumber = ao.optObj("version")?.optInt("number") ?: 0
                            )
                        }
                        .orEmpty()

                    rawPagesById[pid] = ConfluencePageDetail(
                        id = pid,
                        title = title,
                        spaceKey = resolvedSpaceKey,
                        spaceName = resolvedSpaceName,
                        versionNumber = verNum,
                        updatedBy = updatedBy,
                        updatedLabel = updatedWhen,
                        webUrl = fullWebUrl,
                        breadcrumbs = breadcrumbs,
                        childPages = emptyList(),
                        inlineTabs = inlineTabs,
                        attachments = attachments,
                        blocks = tempBlocks,
                        hasSubstantialBody = true
                    )
                }

                onBatchProgress(rawPagesById.size, expectedTotal.coerceAtLeast(rawPagesById.size))
                val hasNext = json.optObj("_links")?.optStr("next")?.isNotBlank() == true
                if (rawCount < batchLimit && !hasNext) break
                start += rawCount
                if (start >= 5000) throw IllegalStateException("Достигнут предел 5000 страниц в пространстве $spaceKey; синхронизация неполная")
            }

            // Reconstruct direct children map from ancestors and sort strictly alphabetically
            val childrenByParentId = HashMap<String, MutableList<ConfluencePageSummary>>()
            for (detail in rawPagesById.values) {
                val directParentId = detail.breadcrumbs.lastOrNull()?.id ?: continue
                childrenByParentId.getOrPut(directParentId) { mutableListOf() }.add(
                    ConfluencePageSummary(
                        id = detail.id,
                        title = detail.title,
                        spaceKey = detail.spaceKey,
                        spaceName = detail.spaceName,
                        updatedLabel = detail.updatedLabel,
                        updatedBy = detail.updatedBy,
                        webPath = detail.webUrl
                    )
                )
            }

            rawPagesById.values.map { detail ->
                val sortedChildren = sortPagesAlphabetically(childrenByParentId[detail.id].orEmpty())
                val hasRealBody = detail.attachments.isNotEmpty() ||
                    detail.inlineTabs.isNotEmpty() ||
                    detail.blocks.any { b ->
                        when (b) {
                            is ConfluenceBlock.Paragraph ->
                                b.text != ConfluenceHtmlParser.EMPTY_PAGE_PLACEHOLDER &&
                                    b.text != ConfluenceHtmlParser.NO_TEXT_PLACEHOLDER
                            else -> true
                        }
                    }
                detail.copy(
                    childPages = sortedChildren,
                    hasSubstantialBody = hasRealBody
                )
            }
        }
    }

    /** Fetch the complete child attachment list; expanded children are often paginated by Confluence. */
    suspend fun fetchPageAttachments(pageId: String, settings: AppSettings): Result<List<ConfluenceAttachment>> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(pageId, "UTF-8")
            val found = LinkedHashMap<String, ConfluenceAttachment>()
            val limit = 100
            var start = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val (baseRoot, json) = executeRestGetJson("content/$encoded/child/attachment?expand=version&limit=$limit&start=$start", settings)
                val results = json.optArray("results") ?: throw IllegalStateException("Нет списка вложений страницы $pageId")
                for (el in results) {
                    val ao = el.asObj() ?: continue
                    val id = ao.optStr("id") ?: throw IllegalStateException("Вложение без ID на странице $pageId")
                    val title = ao.optStr("title") ?: throw IllegalStateException("Вложение без имени на странице $pageId")
                    val ext = ao.optObj("extensions")
                    val rel = ao.optObj("_links")?.optStr("download").orEmpty()
                    if (rel.isBlank()) throw IllegalStateException("Нет ссылки на вложение $title")
                    val url = if (rel.startsWith("http://", true) || rel.startsWith("https://", true)) rel else "$baseRoot/${rel.trimStart('/')}"
                    found[id] = ConfluenceAttachment(id, title, ext?.optStr("mediaType") ?: ao.optObj("metadata")?.optStr("mediaType") ?: "application/octet-stream",
                        ext?.optLong("fileSize") ?: 0L, url, ao.optObj("version")?.optInt("number") ?: 0)
                }
                val count = results.size()
                val total = json.optInt("totalSize") ?: json.optInt("total")
                val hasNext = json.optObj("_links")?.optStr("next")?.isNotBlank() == true || (total != null && start + count < total)
                if (count == 0 && hasNext) throw IllegalStateException("Пустая страница списка вложений $pageId")
                if (count < limit && !hasNext) break
                start += count
                if (start >= 100_000) throw IllegalStateException("Слишком много вложений на странице $pageId")
            }
            found.values.toList()
        }
    }

    /** Stream a protected attachment to a temporary file, never into an unbounded byte array. */
    suspend fun downloadAttachmentToFile(att: ConfluenceAttachment, settings: AppSettings, target: File, maxBytes: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val rawCfg = settings.confluenceUrl.trim().ifBlank { AppSettings.DEFAULT_CONFLUENCE_URL }
            val base = workingBaseUrlCache?.takeIf { it.first == rawCfg }?.second ?: resolveConfiguredWikiDisplayUrl(settings)
            val origin = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("Некорректный адрес Confluence")
            var url = origin.resolve(att.downloadUrl) ?: throw IllegalArgumentException("Некорректная ссылка вложения")
            fun isTrusted(u: HttpUrl) = u.scheme == origin.scheme && u.host == origin.host && u.port == origin.port
            require(isTrusted(url)) { "Вложение ведёт на другой сервер; учётные данные не отправлены" }
            require(!rawCfg.startsWith("https://", true) || origin.scheme == "https") { "Загрузка по незащищённому HTTP запрещена для HTTPS Confluence" }
            if (att.fileSizeBytes > maxBytes) throw IllegalStateException("Файл превышает лимит ${maxBytes / 1024 / 1024} МБ")
            val modes = buildList {
                if (settings.confluenceToken.isNotBlank()) add(AuthAttemptMode.CONFLUENCE_PAT)
                if (buildMergedCookieHeader(url.toString()) != null || cookieJar.hasCookiesForUrl(url.toString())) add(AuthAttemptMode.COOKIE_SESSION_ONLY)
                if (settings.authType == AuthType.BEARER_PAT && settings.passwordOrToken.isNotBlank()) add(AuthAttemptMode.JIRA_BEARER_PAT)
                if (settings.authType != AuthType.BEARER_PAT && settings.usernameOrEmail.isNotBlank() && settings.passwordOrToken.isNotBlank() && url.host.lowercase(Locale.ROOT) !in twoFactorHosts) add(AuthAttemptMode.BASIC_CREDENTIALS)
                if (isEmpty()) add(AuthAttemptMode.COOKIE_SESSION_ONLY)
            }
            var lastCode = 0
            val noRedirectClient = clientFor(settings).newBuilder().followRedirects(false).followSslRedirects(false).build()
            for (mode in modes) {
                url = origin.resolve(att.downloadUrl) ?: throw IllegalArgumentException("Некорректная ссылка вложения")
                var modeFinished = false
                repeat(6) {
                    if (modeFinished) return@repeat
                    currentCoroutineContext().ensureActive()
                    val request = Request.Builder().url(url).get().applyModeHeaders(url.toString(), settings, mode, "*/*").build()
                    noRedirectClient.newCall(request).execute().use { resp ->
                        lastCode = resp.code
                        if (resp.isRedirect) {
                            val next = resp.header("Location")?.let { url.resolve(it) } ?: throw IllegalStateException("Некорректное перенаправление Wiki")
                            require(isTrusted(next)) { "Перенаправление на другой сервер заблокировано" }
                            require(!next.encodedPath.contains("login", true)) { "Требуется повторный вход в Confluence" }
                            url = next
                        } else if (resp.isSuccessful) {
                            val body = resp.body ?: throw IllegalStateException("Пустой ответ Wiki")
                            val contentType = body.contentType()?.toString().orEmpty()
                            if (contentType.startsWith("text/html", true) && !att.mediaType.startsWith("text/html", true)) throw IllegalStateException("Вместо файла сервер вернул HTML-страницу входа")
                            if (body.contentLength() > maxBytes) throw IllegalStateException("Файл превышает лимит ${maxBytes / 1024 / 1024} МБ")
                            var written = 0L
                            target.outputStream().buffered().use { out ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        written += count
                                        if (written > maxBytes) throw IllegalStateException("Файл превышает лимит ${maxBytes / 1024 / 1024} МБ")
                                        out.write(buffer, 0, count)
                                    }
                                }
                            }
                            if (att.fileSizeBytes > 0 && written != att.fileSizeBytes) throw IllegalStateException("Размер загруженного файла не совпадает с Confluence")
                            return@runCatching Unit
                        } else {
                            modeFinished = true
                        }
                    }
                }
            }
            throw IllegalStateException("HTTP $lastCode при загрузке вложения Wiki")
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

            val modes = buildList {
                if (settings.confluenceToken.isNotBlank()) add(AuthAttemptMode.CONFLUENCE_PAT)
                if (buildMergedCookieHeader(resolved) != null || cookieJar.hasCookiesForUrl(resolved)) {
                    add(AuthAttemptMode.COOKIE_SESSION_ONLY)
                }
                if (settings.authType == AuthType.BEARER_PAT && settings.passwordOrToken.isNotBlank()) {
                    add(AuthAttemptMode.JIRA_BEARER_PAT)
                }
                val hostKey = resolved.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty()
                if (settings.authType != AuthType.BEARER_PAT &&
                    settings.usernameOrEmail.isNotBlank() &&
                    settings.passwordOrToken.isNotBlank() &&
                    hostKey !in twoFactorHosts
                ) {
                    add(AuthAttemptMode.BASIC_CREDENTIALS)
                }
                if (isEmpty()) add(AuthAttemptMode.COOKIE_SESSION_ONLY)
            }

            var lastCode = 0
            for (mode in modes) {
                val req = Request.Builder()
                    .url(resolved)
                    .get()
                    .applyModeHeaders(resolved, settings, mode, acceptHeader = "*/*")
                    .build()

                val bytes = clientFor(settings).newCall(req).execute().use { resp ->
                    lastCode = resp.code
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    if (bytes.size <= 4 * 1024 * 1024) {
                        if (imageBytesCache.size > 60) imageBytesCache.clear()
                        imageBytesCache[resolved] = bytes
                    }
                    return@runCatching bytes
                }
            }
            throw IllegalStateException("HTTP $lastCode при загрузке ресурса Wiki")
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
        const val RECENT_PSEUDO_SPACE_KEY = "__RECENT__"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun normalizeAlphabetSortKey(raw: String): String {
            return raw.trim()
                .trimStart('«', '»', '"', '\'', '„', '“', '”', '(', '[', '—', '-', '•', ' ')
                .trim()
                .lowercase(Locale("ru", "RU"))
                .replace('ё', 'е')
        }

        fun firstAlphabetLetter(title: String): String {
            val cleaned = normalizeAlphabetSortKey(title)
            val ch = cleaned.firstOrNull { it.isLetterOrDigit() } ?: return "#"
            return if (ch.isDigit()) "0-9" else ch.uppercaseChar().toString()
        }

        fun sortPagesAlphabetically(pages: List<ConfluencePageSummary>): List<ConfluencePageSummary> {
            if (pages.size <= 1) return pages
            val collator = Collator.getInstance(Locale("ru", "RU")).apply {
                strength = Collator.PRIMARY
            }
            return pages.sortedWith { a, b ->
                val ka = normalizeAlphabetSortKey(a.title)
                val kb = normalizeAlphabetSortKey(b.title)
                val c = collator.compare(ka, kb)
                if (c != 0) c else a.title.compareTo(b.title, ignoreCase = true)
            }
        }

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

        private fun buildBaseClientWithCookieJar(jar: CookieJar): OkHttpClient {
            return OkHttpClient.Builder()
                .connectionPool(okhttp3.ConnectionPool(4, 2, TimeUnit.MINUTES))
                .cookieJar(jar)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        private fun buildTrustAllWikiOverlay(base: OkHttpClient): OkHttpClient {
            val builder = base.newBuilder()
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
            return builder.build()
        }
    }

    init {
        // Re-bind instance cookieJar so dologin.action session cookies persist across requests
    }
}

object ConfluenceHtmlParser {

    const val EMPTY_PAGE_PLACEHOLDER = "Страница пуста."
    const val NO_TEXT_PLACEHOLDER = "На этой странице пока нет текстового содержимого."

    private val SCRIPT_STYLE_REGEX = Regex(
        "<(script|style|svg)\\b[^>]*>[\\s\\S]*?</\\1>",
        RegexOption.IGNORE_CASE
    )
    private val CHILDREN_MACRO_REGEX = Regex(
        """<ul\b[^>]*class\s*=\s*["'][^"']*(?:childpages-macro|plugin_pagetree_children_list)[^"']*["'][^>]*>[\s\S]*?</ul>""",
        RegexOption.IGNORE_CASE
    )
    private val TAB_MENU_LINK_REGEX = Regex(
        """<li\b[^>]*class\s*=\s*["'][^"']*menu-item[^"']*["'][^>]*>[\s\S]*?<a\b[^>]*href\s*=\s*["']#([^"']+)["'][^>]*>([\s\S]*?)</a>[\s\S]*?</li>""",
        RegexOption.IGNORE_CASE
    )
    private val TAB_PANE_OPEN_REGEX = Regex(
        """<div\b([^>]*\bclass\s*=\s*["'][^"']*\btabs-pane\b[^"']*["'][^>]*)>""",
        RegexOption.IGNORE_CASE
    )
    private val STORAGE_LOCALTAB_REGEX = Regex(
        """<ac:structured-macro\b[^>]*ac:name\s*=\s*["'](?:localtab|ui-tab|tab-page|auitabspage)["'][^>]*>([\s\S]*?)</ac:structured-macro>""",
        RegexOption.IGNORE_CASE
    )
    private val STORAGE_TITLE_PARAM_REGEX = Regex(
        """<ac:parameter\b[^>]*ac:name\s*=\s*["']title["'][^>]*>([\s\S]*?)</ac:parameter>""",
        RegexOption.IGNORE_CASE
    )
    private val STORAGE_RICH_BODY_REGEX = Regex(
        """<ac:rich-text-body\b[^>]*>([\s\S]*?)</ac:rich-text-body>""",
        RegexOption.IGNORE_CASE
    )
    private val ATTR_ID_REGEX = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATTR_TITLE_REGEX = Regex("""\b(?:data-pane-title|data-tab-title|title)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val BLOCK_TOKEN_REGEX = Regex(
        """(<pre\b[^>]*>[\s\S]*?</pre>|<ac:structured-macro\b[^>]*ac:name\s*=\s*["']code["'][^>]*>[\s\S]*?</ac:structured-macro>|<div\b[^>]*class\s*=\s*["'][^"']*confluence-information-macro[^"']*["'][^>]*>[\s\S]*?</div>|<h([1-6])\b[^>]*>[\s\S]*?</h\2>|<ul\b[^>]*>[\s\S]*?</ul>|<ol\b[^>]*>[\s\S]*?</ol>|<img\b[^>]*>|<p\b[^>]*>[\s\S]*?</p>)""",
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

    /**
     * Extracts Confluence UI Tabs / Navitabs / Localtab panes when a single Confluence page
     * contains multiple sub-tabs (`tabs-pane` in rendered `body.view`, or `localtab`/`ui-tab` in `body.storage`).
     */
    fun parseInlineTabs(rawHtml: String, baseRoot: String): List<ConfluenceInlineTab> {
        if (rawHtml.isBlank()) return emptyList()
        val stripped = rawHtml.replace(SCRIPT_STYLE_REGEX, "")

        // 1. Rendered HTML AUI Tabs (<ul class="tabs-menu"> + <div class="tabs-pane" id="...">)
        val menuTitlesById = LinkedHashMap<String, String>()
        for (m in TAB_MENU_LINK_REGEX.findAll(stripped)) {
            val id = m.groupValues[1].trim()
            val title = cleanText(m.groupValues[2])
            if (id.isNotEmpty() && title.isNotEmpty()) {
                menuTitlesById[id] = title
            }
        }

        val auiTabs = mutableListOf<ConfluenceInlineTab>()
        for (m in TAB_PANE_OPEN_REGEX.findAll(stripped)) {
            val attrs = m.groupValues[1]
            val paneId = ATTR_ID_REGEX.find(attrs)?.groupValues?.getOrNull(1).orEmpty()
            val attrTitle = ATTR_TITLE_REGEX.find(attrs)?.groupValues?.getOrNull(1)?.let { cleanText(it) }.orEmpty()
            val resolvedTitle = menuTitlesById[paneId]
                ?: attrTitle.takeIf { it.isNotBlank() }
                ?: "Вкладка ${auiTabs.size + 1}"
            val innerHtml = extractBalancedDivInner(stripped, m.range.first, m.range.last + 1) ?: continue
            val paneBlocks = parseHtmlToBlocks(innerHtml, baseRoot, hasNativeChildPages = false)
            auiTabs.add(
                ConfluenceInlineTab(
                    id = paneId.ifBlank { "tab_${auiTabs.size}" },
                    title = resolvedTitle,
                    blocks = paneBlocks
                )
            )
        }
        if (auiTabs.isNotEmpty()) return auiTabs

        // 2. Storage XML Tabs (<ac:structured-macro ac:name="localtab" / "ui-tab">)
        val storageTabs = mutableListOf<ConfluenceInlineTab>()
        for (m in STORAGE_LOCALTAB_REGEX.findAll(stripped)) {
            val macroInner = m.groupValues[1]
            val title = STORAGE_TITLE_PARAM_REGEX.find(macroInner)?.groupValues?.getOrNull(1)
                ?.let { cleanText(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: "Вкладка ${storageTabs.size + 1}"
            val body = STORAGE_RICH_BODY_REGEX.find(macroInner)?.groupValues?.getOrNull(1) ?: macroInner
            storageTabs.add(
                ConfluenceInlineTab(
                    id = "st_${storageTabs.size}",
                    title = title,
                    blocks = parseHtmlToBlocks(body, baseRoot, hasNativeChildPages = false)
                )
            )
        }
        return storageTabs
    }

    private fun extractBalancedDivInner(html: String, divStart: Int, contentStart: Int): String? {
        val tagRegex = Regex("</?div\\b[^>]*>", RegexOption.IGNORE_CASE)
        var depth = 1
        var pos = contentStart
        while (pos < html.length) {
            val next = tagRegex.find(html, pos) ?: return html.substring(contentStart)
            val token = next.value.lowercase(Locale.ROOT)
            if (token.startsWith("</div")) {
                depth--
                if (depth == 0) {
                    return html.substring(contentStart, next.range.first)
                }
            } else if (!token.endsWith("/>")) {
                depth++
            }
            pos = next.range.last + 1
        }
        return html.substring(contentStart)
    }

    private fun splitTopLevelTables(html: String): List<Pair<Boolean, String>> {
        val tagRegex = Regex("</?table\\b[^>]*>", RegexOption.IGNORE_CASE)
        val segments = mutableListOf<Pair<Boolean, String>>()
        var cursor = 0
        var tableStart = -1
        var depth = 0
        for (m in tagRegex.findAll(html)) {
            val isClose = m.value.startsWith("</", ignoreCase = true)
            if (!isClose) {
                if (depth == 0) {
                    if (m.range.first > cursor) {
                        segments.add(false to html.substring(cursor, m.range.first))
                    }
                    tableStart = m.range.first
                }
                depth++
            } else if (depth > 0) {
                depth--
                if (depth == 0 && tableStart >= 0) {
                    segments.add(true to html.substring(tableStart, m.range.last + 1))
                    cursor = m.range.last + 1
                    tableStart = -1
                }
            }
        }
        if (depth > 0 && tableStart >= 0) {
            segments.add(true to html.substring(tableStart))
        } else if (cursor < html.length) {
            segments.add(false to html.substring(cursor))
        }
        return segments
    }

    fun parseHtmlToBlocks(
        rawHtml: String,
        baseRoot: String,
        hasNativeChildPages: Boolean = false
    ): List<ConfluenceBlock> {
        if (rawHtml.isBlank()) {
            return listOf(ConfluenceBlock.Paragraph(EMPTY_PAGE_PLACEHOLDER))
        }
        var stripped = rawHtml.replace(SCRIPT_STYLE_REGEX, "")
        if (hasNativeChildPages) {
            stripped = stripped.replace(CHILDREN_MACRO_REGEX, "")
        }

        val blocks = mutableListOf<ConfluenceBlock>()

        fun appendResidualPlainText(fragment: String) {
            val plain = cleanTextPreservingNewlines(fragment)
            if (plain.isNotEmpty()) {
                plain.split(Regex("\\n{2,}"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { blocks.add(ConfluenceBlock.Paragraph(it)) }
            }
        }

        for ((isTable, segment) in splitTopLevelTables(stripped)) {
            if (isTable) {
                parseTable(segment)?.let { blocks.add(it) }
                continue
            }

            var lastEnd = 0
            for (m in BLOCK_TOKEN_REGEX.findAll(segment)) {
                if (m.range.first > lastEnd) {
                    appendResidualPlainText(segment.substring(lastEnd, m.range.first))
                }
                lastEnd = m.range.last + 1

                val chunk = m.value
                val low = chunk.take(40).lowercase(Locale.ROOT)
                when {
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
            if (lastEnd < segment.length) {
                appendResidualPlainText(segment.substring(lastEnd))
            }
        }

        return blocks.ifEmpty {
            listOf(ConfluenceBlock.Paragraph(NO_TEXT_PLACEHOLDER))
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
