package com.helpdesk.jiraagent.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.FilterSettings
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.JiraServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class JiraApiException(message: String, val httpCode: Int? = null) : Exception(message)

class JiraApiClient {

    private val standardClient: OkHttpClient = buildClient(trustAllSsl = false)
    private val selfSignedClient: OkHttpClient = buildClient(trustAllSsl = true)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun clientFor(settings: AppSettings): OkHttpClient {
        return if (settings.trustSelfSignedSsl) selfSignedClient else standardClient
    }

    private fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw JiraApiException("Укажите адрес сервера Jira в настройках")
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        if (withScheme.toHttpUrlOrNull() == null) {
            throw JiraApiException("Некорректный адрес сервера: $rawUrl")
        }
        return withScheme
    }

    private fun isCloudTarget(settings: AppSettings, baseUrl: String): Boolean {
        return when (settings.serverType) {
            JiraServerType.CLOUD -> true
            JiraServerType.SERVER_DC -> false
            JiraServerType.AUTO -> baseUrl.contains(".atlassian.net", ignoreCase = true) ||
                settings.authType == AuthType.CLOUD_EMAIL_TOKEN
        }
    }

    private fun Request.Builder.applyAuth(settings: AppSettings): Request.Builder {
        header("Accept", "application/json")
        header("X-Atlassian-Token", "no-check")
        when (settings.authType) {
            AuthType.BASIC_LOGIN_PASSWORD, AuthType.CLOUD_EMAIL_TOKEN -> {
                val credential = Credentials.basic(
                    settings.usernameOrEmail.trim(),
                    settings.passwordOrToken,
                    Charsets.UTF_8
                )
                header("Authorization", credential)
            }
            AuthType.BEARER_PAT -> {
                header("Authorization", "Bearer ${settings.passwordOrToken.trim()}")
            }
        }
        return this
    }

    /**
     * Tests connectivity and authentication against `/rest/api/2/myself` or `/rest/api/3/myself`.
     * Returns the authenticated user's display name.
     */
    suspend fun testConnection(settings: AppSettings): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferV3 = isCloudTarget(settings, baseUrl)
            val endpoints = if (preferV3) {
                listOf("$baseUrl/rest/api/3/myself", "$baseUrl/rest/api/2/myself")
            } else {
                listOf("$baseUrl/rest/api/2/myself", "$baseUrl/rest/api/3/myself")
            }

            var lastErr: Exception? = null
            for (url in endpoints) {
                try {
                    val req = Request.Builder().url(url).get().applyAuth(settings).build()
                    clientFor(settings).newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val json = JsonParser.parseString(bodyStr).asJsonObject
                            val displayName = json.get("displayName")?.asString
                                ?: json.get("name")?.asString
                                ?: json.get("emailAddress")?.asString
                                ?: "Успешно"
                            return@withContext Result.success(displayName)
                        } else if (resp.code == 401 || resp.code == 403) {
                            throw mapHttpError(resp.code, bodyStr)
                        } else {
                            lastErr = mapHttpError(resp.code, bodyStr)
                        }
                    }
                } catch (e: JiraApiException) {
                    if (e.httpCode == 401 || e.httpCode == 403) throw e
                    lastErr = e
                } catch (e: Exception) {
                    lastErr = wrapNetworkException(e, settings.trustSelfSignedSsl)
                }
            }
            throw (lastErr ?: JiraApiException("Не удалось подключиться к серверу Jira"))
        }
    }

    /**
     * Fetches recent tickets from Jira (compatible with Jira Cloud v3 `/search/jql` and Server/DC v2 `/search`).
     */
    suspend fun fetchRecentTickets(
        settings: AppSettings,
        filterSettings: FilterSettings,
        maxResults: Int = 40
    ): Result<List<HelpdeskTicket>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val jql = TicketFilterEngine.buildJql(filterSettings)
            val preferCloud = isCloudTarget(settings, baseUrl)

            val responseJson = if (preferCloud) {
                try {
                    executeCloudJqlSearch(baseUrl, jql, maxResults, settings)
                } catch (e: JiraApiException) {
                    if (e.httpCode == 404 || e.httpCode == 405 || e.httpCode == 410) {
                        executeV2GetSearch(baseUrl, jql, maxResults, settings)
                    } else {
                        throw e
                    }
                }
            } else {
                try {
                    executeV2GetSearch(baseUrl, jql, maxResults, settings)
                } catch (e: JiraApiException) {
                    if (e.httpCode == 404 || e.httpCode == 410) {
                        executeCloudJqlSearch(baseUrl, jql, maxResults, settings)
                    } else {
                        throw e
                    }
                }
            }

            JiraJsonParser.parseSearchResponse(responseJson, settings, filterSettings)
        }
    }

    /**
     * Fetches full details & comments for a single Jira issue key (e.g. "HD-1042").
     */
    suspend fun fetchTicketByKey(
        issueKey: String,
        settings: AppSettings,
        filterSettings: FilterSettings
    ): Result<HelpdeskTicket> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.serverUrl)
            val preferCloud = isCloudTarget(settings, baseUrl)
            val apiVersion = if (preferCloud) "3" else "2"
            val url = "$baseUrl/rest/api/$apiVersion/issue/${URLEncoder.encode(issueKey, "UTF-8")}?expand=names"

            val req = Request.Builder().url(url).get().applyAuth(settings).build()
            val json = try {
                clientFor(settings).newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw mapHttpError(resp.code, bodyStr)
                    bodyStr
                }
            } catch (e: Exception) {
                throw wrapNetworkException(e, settings.trustSelfSignedSsl)
            }

            JiraJsonParser.parseSingleIssue(json, settings, filterSettings)
                ?: throw JiraApiException("Не удалось разобрать данные заявки $issueKey")
        }
    }

    private fun executeCloudJqlSearch(
        baseUrl: String,
        jql: String,
        maxResults: Int,
        settings: AppSettings
    ): String {
        val payload = JsonObject().apply {
            addProperty("jql", jql)
            addProperty("maxResults", maxResults)
            add("fields", JsonArray().apply { add("*all") })
            addProperty("expand", "names")
        }
        val bodyBytes = payload.toString().toRequestBody(jsonMediaType)

        // Try modern Jira Cloud /rest/api/3/search/jql first, then fallback to /rest/api/3/search
        val urls = listOf(
            "$baseUrl/rest/api/3/search/jql",
            "$baseUrl/rest/api/3/search"
        )

        var lastError: Exception? = null
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).post(bodyBytes).applyAuth(settings).build()
                clientFor(settings).newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) return bodyStr
                    if (resp.code == 401 || resp.code == 403 || resp.code == 400) {
                        throw mapHttpError(resp.code, bodyStr)
                    }
                    lastError = mapHttpError(resp.code, bodyStr)
                }
            } catch (e: JiraApiException) {
                if (e.httpCode == 401 || e.httpCode == 403 || e.httpCode == 400) throw e
                lastError = e
            } catch (e: Exception) {
                throw wrapNetworkException(e, settings.trustSelfSignedSsl)
            }
        }
        throw (lastError ?: JiraApiException("Ошибка поиска заявок в Jira Cloud"))
    }

    private fun executeV2GetSearch(
        baseUrl: String,
        jql: String,
        maxResults: Int,
        settings: AppSettings
    ): String {
        val encodedJql = URLEncoder.encode(jql, "UTF-8")
        val url = "$baseUrl/rest/api/2/search?jql=$encodedJql&maxResults=$maxResults&fields=*all&expand=names"
        val req = Request.Builder().url(url).get().applyAuth(settings).build()
        try {
            clientFor(settings).newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw mapHttpError(resp.code, bodyStr)
                return bodyStr
            }
        } catch (e: Exception) {
            throw wrapNetworkException(e, settings.trustSelfSignedSsl)
        }
    }

    private fun mapHttpError(code: Int, body: String): JiraApiException {
        val serverMsg = runCatching {
            val obj = JsonParser.parseString(body).asJsonObject
            val arr = obj.getAsJsonArray("errorMessages")
            if (arr != null && arr.size() > 0) arr[0].asString else null
        }.getOrNull()

        val message = when (code) {
            400 -> "Ошибка запроса JQL (400): ${serverMsg ?: "проверьте фильтр проектов или JQL"}"
            401 -> "Ошибка авторизации (401): проверьте логин/email и пароль или токен"
            403 -> "Доступ запрещён (403): недостаточно прав или сработала защита CAPTCHA в Jira"
            404 -> "Ресурс не найден (404): проверьте адрес сервера Jira (например, наличие /jira)"
            else -> "Ошибка сервера Jira ($code)${if (serverMsg != null) ": $serverMsg" else ""}"
        }
        return JiraApiException(message, code)
    }

    private fun wrapNetworkException(e: Exception, trustSelfSigned: Boolean): Exception {
        if (e is JiraApiException) return e
        val msg = e.message.orEmpty()
        if (e is javax.net.ssl.SSLException && !trustSelfSigned) {
            return JiraApiException(
                "Ошибка SSL-сертификата. Если используется внутренний корпоративный сервер, " +
                    "включите «Доверять самоподписанным SSL» в настройках."
            )
        }
        return JiraApiException("Ошибка сети: ${msg.ifBlank { e.javaClass.simpleName }}")
    }

    companion object {
        private fun buildClient(trustAllSsl: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)

            if (trustAllSsl) {
                val trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                builder.hostnameVerifier { _, _ -> true }
            }

            return builder.build()
        }
    }
}
