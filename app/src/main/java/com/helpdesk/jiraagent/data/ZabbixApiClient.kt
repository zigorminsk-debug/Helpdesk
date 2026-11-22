package com.helpdesk.jiraagent.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ZabbixDashboardSnapshot
import com.helpdesk.jiraagent.model.ZabbixProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

class ZabbixApiException(
    override val message: String,
    val code: Int = 0,
    val isAuthError: Boolean = false
) : Exception(message)

class ZabbixApiClient {

    private val gson = Gson()
    private val requestIdSeq = AtomicInteger(1)

    private data class EndpointInfo(
        val rpcUrl: String,
        val version: String,
        val majorMinor: Double,
        val trustAllUsed: Boolean
    )

    private data class CachedSession(
        val cacheKey: String,
        val endpoint: EndpointInfo,
        val token: String,
        val useBearerHeader: Boolean,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val endpointCache = ConcurrentHashMap<String, EndpointInfo>()

    @Volatile
    private var cachedSession: CachedSession? = null

    fun invalidateSession() {
        cachedSession = null
    }

    suspend fun testConnection(settings: AppSettings): Result<ZabbixDashboardSnapshot> {
        invalidateSession()
        endpointCache.clear()
        return fetchSnapshot(settings)
    }

    suspend fun fetchSnapshot(settings: AppSettings): Result<ZabbixDashboardSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val login = settings.zabbixLogin.trim()
                val password = settings.zabbixPassword
                val rawUrl = settings.zabbixUrl.trim().ifBlank { AppSettings.DEFAULT_ZABBIX_URL }

                if (login.isEmpty() || password.isEmpty()) {
                    throw ZabbixApiException(
                        "Укажите логин и пароль Zabbix для подключения к $rawUrl",
                        isAuthError = true
                    )
                }

                val session = getOrCreateSession(rawUrl, login, password, settings.trustSelfSignedSsl)
                try {
                    loadDashboardWithSession(session)
                } catch (e: ZabbixApiException) {
                    if (e.isAuthError) {
                        // Session token expired on Zabbix server -> re-login once transparently
                        invalidateSession()
                        val freshSession = getOrCreateSession(rawUrl, login, password, settings.trustSelfSignedSsl)
                        loadDashboardWithSession(freshSession)
                    } else {
                        throw e
                    }
                }
            }
        }

    suspend fun acknowledgeProblem(
        settings: AppSettings,
        eventId: String,
        message: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanEventId = eventId.trim()
            if (cleanEventId.isEmpty() || cleanEventId == "0") {
                throw ZabbixApiException("У данного триггера Zabbix отсутствует идентификатор события (eventid)")
            }
            val login = settings.zabbixLogin.trim()
            val password = settings.zabbixPassword
            val rawUrl = settings.zabbixUrl.trim().ifBlank { AppSettings.DEFAULT_ZABBIX_URL }
            if (login.isEmpty() || password.isEmpty()) {
                throw ZabbixApiException("Укажите логин и пароль Zabbix", isAuthError = true)
            }

            val session = getOrCreateSession(rawUrl, login, password, settings.trustSelfSignedSsl)
            val cleanMsg = message.trim()

            // Zabbix 4.0–7.x: action bitmask 2 = acknowledge, 4 = add message (6 = both)
            val paramsModern = JsonObject().apply {
                add("eventids", JsonArray().apply { add(cleanEventId) })
                if (cleanMsg.isNotEmpty()) {
                    addProperty("action", 6)
                    addProperty("message", cleanMsg)
                } else {
                    addProperty("action", 2)
                }
            }

            val firstAttempt = runCatching {
                callAuthenticatedRpc(session, "event.acknowledge", paramsModern)
            }
            if (firstAttempt.isFailure) {
                // Fallback if user role only permits adding message (action=4) or Zabbix 3.x without action bitmask
                val fallbackAction4 = runCatching {
                    val p4 = JsonObject().apply {
                        add("eventids", JsonArray().apply { add(cleanEventId) })
                        addProperty("action", 4)
                        addProperty("message", cleanMsg.ifBlank { "Принято в работу (Help-CSL)" })
                    }
                    callAuthenticatedRpc(session, "event.acknowledge", p4)
                }
                if (fallbackAction4.isFailure) {
                    val pLegacy = JsonObject().apply {
                        add("eventids", JsonArray().apply { add(cleanEventId) })
                        addProperty("message", cleanMsg.ifBlank { "Принято в работу (Help-CSL)" })
                    }
                    callAuthenticatedRpc(session, "event.acknowledge", pLegacy)
                }
            }
        }
    }

    private fun loadDashboardWithSession(session: CachedSession): ZabbixDashboardSnapshot {
        // 1. Query active firing triggers (value=1, monitored=true, skipDependent=true) with hosts & lastEvent
        val triggerParams = JsonObject().apply {
            add("output", JsonArray().apply {
                add("triggerid")
                add("description")
                add("priority")
                add("value")
                add("lastchange")
                add("comments")
                add("url")
            })
            add("filter", JsonObject().apply {
                addProperty("value", 1)
            })
            addProperty("monitored", true)
            addProperty("skipDependent", true)
            addProperty("expandDescription", true)
            add("selectHosts", JsonArray().apply {
                add("hostid")
                add("host")
                add("name")
            })
            addProperty("selectLastEvent", "extend")
            addProperty("sortfield", "lastchange")
            addProperty("sortorder", "DESC")
            addProperty("limit", 200)
        }

        val triggersResult = callAuthenticatedRpc(session, "trigger.get", triggerParams)
        val triggersArray = triggersResult.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

        // 2. Optional enrichment from problem.get (gives live opdata + acknowledgement messages + suppressed check)
        data class ProblemExtra(
            val eventId: String,
            val opData: String,
            val acknowledged: Boolean,
            val ackMessage: String,
            val suppressed: Boolean,
            val clockSec: Long,
            val name: String,
            val severity: Int
        )

        val problemByTriggerId = HashMap<String, ProblemExtra>()
        runCatching {
            val probParams = JsonObject().apply {
                addProperty("output", "extend")
                addProperty("selectAcknowledges", "extend")
                addProperty("recent", false)
                add("sortfield", JsonArray().apply { add("eventid") })
                addProperty("sortorder", "DESC")
                addProperty("limit", 250)
            }
            val probRes = callAuthenticatedRpc(session, "problem.get", probParams)
            if (probRes.isJsonArray) {
                for (el in probRes.asJsonArray) {
                    val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val objectId = obj.str("objectid")
                    if (objectId.isBlank() || problemByTriggerId.containsKey(objectId)) continue
                    val rEventId = obj.str("r_eventid")
                    if (rEventId.isNotBlank() && rEventId != "0") continue // already resolved

                    val suppressed = obj.str("suppressed") == "1"
                    val acked = obj.str("acknowledged") == "1"
                    var latestAckMsg = ""
                    val acks = obj.get("acknowledges")
                    if (acks != null && acks.isJsonArray && acks.asJsonArray.size() > 0) {
                        val firstAck = acks.asJsonArray[0].takeIf { it.isJsonObject }?.asJsonObject
                        latestAckMsg = firstAck?.str("message").orEmpty()
                    }
                    problemByTriggerId[objectId] = ProblemExtra(
                        eventId = obj.str("eventid"),
                        opData = obj.str("opdata"),
                        acknowledged = acked,
                        ackMessage = latestAckMsg,
                        suppressed = suppressed,
                        clockSec = obj.str("clock").toLongOrNull() ?: 0L,
                        name = obj.str("name"),
                        severity = obj.str("severity").toIntOrNull() ?: -1
                    )
                }
            }
        }

        // Collect distinct hostIds to resolve IP / DNS addresses in a single lightweight batch call
        val hostIds = LinkedHashSet<String>()
        for (el in triggersArray) {
            val tObj = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val hostsArr = tObj.get("hosts")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (h in hostsArr) {
                val hid = h.takeIf { it.isJsonObject }?.asJsonObject?.str("hostid").orEmpty()
                if (hid.isNotBlank()) hostIds.add(hid)
            }
        }

        val hostIpByHostId = HashMap<String, String>()
        if (hostIds.isNotEmpty()) {
            runCatching {
                val ifaceParams = JsonObject().apply {
                    add("hostids", JsonArray().apply { hostIds.forEach { add(it) } })
                    add("output", JsonArray().apply {
                        add("hostid")
                        add("ip")
                        add("dns")
                        add("useip")
                        add("main")
                    })
                }
                val ifaceRes = callAuthenticatedRpc(session, "hostinterface.get", ifaceParams)
                if (ifaceRes.isJsonArray) {
                    for (el in ifaceRes.asJsonArray) {
                        val io = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                        val hid = io.str("hostid")
                        val ip = io.str("ip").takeIf { it.isNotBlank() && it != "127.0.0.1" && it != "0.0.0.0" }
                        val dns = io.str("dns").takeIf { it.isNotBlank() }
                        val addr = ip ?: dns.orEmpty()
                        val isMain = io.str("main") == "1"
                        if (hid.isNotBlank() && addr.isNotBlank()) {
                            if (isMain || !hostIpByHostId.containsKey(hid)) {
                                hostIpByHostId[hid] = addr
                            }
                        }
                    }
                }
            }
        }

        val parsedProblems = ArrayList<ZabbixProblem>(triggersArray.size())
        for (el in triggersArray) {
            val tObj = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val triggerId = tObj.str("triggerid")
            val probExtra = problemByTriggerId[triggerId]
            if (probExtra?.suppressed == true) continue

            val hostsArr = tObj.get("hosts")?.takeIf { it.isJsonArray }?.asJsonArray
            val firstHost = if (hostsArr != null && hostsArr.size() > 0 && hostsArr[0].isJsonObject) {
                hostsArr[0].asJsonObject
            } else {
                null
            }
            val hostId = firstHost?.str("hostid").orEmpty()
            val hostTech = firstHost?.str("host").orEmpty()
            val hostName = firstHost?.str("name").orEmpty().ifBlank { hostTech }

            val lastEvObj = tObj.get("lastEvent")?.takeIf { it.isJsonObject }?.asJsonObject
            val eventId = probExtra?.eventId?.takeIf { it.isNotBlank() }
                ?: lastEvObj?.str("eventid").orEmpty()

            val triggerSeverity = tObj.str("priority").toIntOrNull() ?: 0
            val effectiveSeverity = when {
                probExtra != null && probExtra.severity >= 0 -> probExtra.severity
                lastEvObj != null && lastEvObj.str("severity").toIntOrNull() != null ->
                    lastEvObj.str("severity").toInt()
                else -> triggerSeverity
            }.coerceIn(0, 5)

            val clockSec = listOfNotNull(
                probExtra?.clockSec?.takeIf { it > 0L },
                lastEvObj?.str("clock")?.toLongOrNull()?.takeIf { it > 0L },
                tObj.str("lastchange").toLongOrNull()?.takeIf { it > 0L }
            ).firstOrNull() ?: 0L

            val acked = probExtra?.acknowledged
                ?: (lastEvObj?.str("acknowledged") == "1")

            val problemTitle = probExtra?.name?.takeIf { it.isNotBlank() }
                ?: lastEvObj?.str("name")?.takeIf { it.isNotBlank() }
                ?: tObj.str("description").ifBlank { "Триггер #$triggerId" }

            parsedProblems.add(
                ZabbixProblem(
                    eventId = eventId,
                    triggerId = triggerId,
                    name = problemTitle,
                    opData = probExtra?.opData.orEmpty(),
                    severityLevel = effectiveSeverity,
                    hostId = hostId,
                    hostTechnicalName = hostTech,
                    hostDisplayName = hostName,
                    hostIp = hostIpByHostId[hostId].orEmpty(),
                    clockEpochSeconds = clockSec,
                    acknowledged = acked,
                    ackMessage = probExtra?.ackMessage.orEmpty(),
                    comments = tObj.str("comments"),
                    triggerUrl = tObj.str("url")
                )
            )
        }

        parsedProblems.sortWith(
            compareByDescending<ZabbixProblem> { !it.acknowledged && it.severityLevel >= 3 }
                .thenByDescending { it.severityLevel }
                .thenByDescending { it.clockEpochSeconds }
        )

        // 3. Monitored hosts count (lightweight countOutput=true)
        val totalHostsCount = runCatching {
            val hostCountParams = JsonObject().apply {
                addProperty("countOutput", true)
                add("filter", JsonObject().apply {
                    addProperty("status", 0)
                })
            }
            val countEl = callAuthenticatedRpc(session, "host.get", hostCountParams)
            if (countEl.isJsonPrimitive) countEl.asString.toIntOrNull() ?: 0 else 0
        }.getOrDefault(0)

        return ZabbixDashboardSnapshot(
            serverVersion = session.endpoint.version,
            resolvedEndpointUrl = session.endpoint.rpcUrl,
            monitoredHostsCount = totalHostsCount,
            problems = parsedProblems,
            fetchedAtMillis = System.currentTimeMillis()
        )
    }

    private fun getOrCreateSession(
        rawUrl: String,
        login: String,
        password: String,
        trustSelfSignedSsl: Boolean
    ): CachedSession {
        val key = "${rawUrl.trim().lowercase()}|$login|${password.hashCode()}|$trustSelfSignedSsl"
        val existing = cachedSession
        if (existing != null && existing.cacheKey == key &&
            (System.currentTimeMillis() - existing.createdAtMs) < 6L * 3_600_000L
        ) {
            return existing
        }

        val endpoint = discoverEndpoint(rawUrl, trustSelfSignedSsl)
        val preferUsernameKey = endpoint.majorMinor == 0.0 || endpoint.majorMinor >= 5.4

        val primaryParamKey = if (preferUsernameKey) "username" else "user"
        val fallbackParamKey = if (preferUsernameKey) "user" else "username"

        val token = try {
            performLoginCall(endpoint, primaryParamKey, login, password)
        } catch (e: ZabbixApiException) {
            // If parameter name mismatch across Zabbix versions (-32602 Invalid params), try alternate key
            if (!e.isAuthError || e.code == -32602 || e.message.contains("parameter", ignoreCase = true)) {
                performLoginCall(endpoint, fallbackParamKey, login, password)
            } else {
                throw e
            }
        }

        val preferBearer = endpoint.majorMinor >= 6.4
        val newSession = CachedSession(
            cacheKey = key,
            endpoint = endpoint,
            token = token,
            useBearerHeader = preferBearer
        )
        cachedSession = newSession
        return newSession
    }

    private fun performLoginCall(
        endpoint: EndpointInfo,
        userFieldKey: String,
        login: String,
        password: String
    ): String {
        val params = JsonObject().apply {
            addProperty(userFieldKey, login)
            addProperty("password", password)
        }
        val res = executeRpcRaw(
            rpcUrl = endpoint.rpcUrl,
            method = "user.login",
            params = params,
            authTokenInBody = null,
            bearerTokenHeader = null,
            trustAllSsl = endpoint.trustAllUsed
        ).first
        val token = if (res.isJsonPrimitive) res.asString.trim() else ""
        if (token.isEmpty()) {
            throw ZabbixApiException("Сервер Zabbix вернул пустой токен авторизации", isAuthError = true)
        }
        return token
    }

    private fun callAuthenticatedRpc(
        session: CachedSession,
        method: String,
        params: JsonElement
    ): JsonElement {
        return try {
            executeRpcRaw(
                rpcUrl = session.endpoint.rpcUrl,
                method = method,
                params = params,
                authTokenInBody = if (session.useBearerHeader) null else session.token,
                bearerTokenHeader = if (session.useBearerHeader) session.token else null,
                trustAllSsl = session.endpoint.trustAllUsed
            ).first
        } catch (e: ZabbixApiException) {
            // Automatic cross-version auth header/body fallback if Zabbix version probe was disabled
            val msgLower = e.message.lowercase()
            if (msgLower.contains("unexpected parameter \"auth\"") ||
                (session.useBearerHeader && (e.isAuthError || msgLower.contains("not authorized")))
            ) {
                val flippedBearer = !session.useBearerHeader
                val res = executeRpcRaw(
                    rpcUrl = session.endpoint.rpcUrl,
                    method = method,
                    params = params,
                    authTokenInBody = if (flippedBearer) null else session.token,
                    bearerTokenHeader = if (flippedBearer) session.token else null,
                    trustAllSsl = session.endpoint.trustAllUsed
                ).first
                cachedSession = session.copy(useBearerHeader = flippedBearer)
                res
            } else {
                throw e
            }
        }
    }

    private fun discoverEndpoint(rawUrl: String, trustSelfSignedSsl: Boolean): EndpointInfo {
        val cacheKey = "${rawUrl.trim().lowercase()}|$trustSelfSignedSsl"
        endpointCache[cacheKey]?.let { return it }

        val candidates = buildCandidateRpcUrls(rawUrl)
        val allowAutoCorporateSslFallback = trustSelfSignedSsl || isInternalCorporateHost(rawUrl)

        var lastError: Throwable? = null
        for (candidate in candidates) {
            for (trustAll in if (trustSelfSignedSsl) listOf(true) else if (allowAutoCorporateSslFallback) listOf(false, true) else listOf(false)) {
                try {
                    val (verElement, finalUrl) = executeRpcRaw(
                        rpcUrl = candidate,
                        method = "apiinfo.version",
                        params = JsonArray(),
                        authTokenInBody = null,
                        bearerTokenHeader = null,
                        trustAllSsl = trustAll
                    )
                    val verStr = if (verElement.isJsonPrimitive) verElement.asString.trim() else ""
                    val majorMinor = parseMajorMinor(verStr)
                    val info = EndpointInfo(
                        rpcUrl = finalUrl,
                        version = verStr,
                        majorMinor = majorMinor,
                        trustAllUsed = trustAll
                    )
                    endpointCache[cacheKey] = info
                    return info
                } catch (sslErr: SSLException) {
                    lastError = sslErr
                    // Continue to trustAll=true if allowed
                } catch (apiErr: ZabbixApiException) {
                    // If apiinfo.version is restricted by firewall/hardening, endpoint still speaks Zabbix JSON-RPC!
                    if (apiErr.code != 0) {
                        val info = EndpointInfo(
                            rpcUrl = candidate,
                            version = "",
                            majorMinor = 0.0,
                            trustAllUsed = trustAll
                        )
                        endpointCache[cacheKey] = info
                        return info
                    }
                    lastError = apiErr
                    break
                } catch (t: Throwable) {
                    lastError = t
                    break
                }
            }
        }

        val errDetail = lastError?.message ?: "не удалось подключиться к API Zabbix"
        throw ZabbixApiException("Не удалось связаться с сервером Zabbix ($rawUrl): $errDetail")
    }

    private fun executeRpcRaw(
        rpcUrl: String,
        method: String,
        params: JsonElement,
        authTokenInBody: String?,
        bearerTokenHeader: String?,
        trustAllSsl: Boolean
    ): Pair<JsonElement, String> {
        val payloadObj = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
            addProperty("id", requestIdSeq.getAndIncrement())
            if (!authTokenInBody.isNullOrBlank()) {
                addProperty("auth", authTokenInBody)
            }
        }
        val jsonPayload = gson.toJson(payloadObj)
        val client = noRedirectClient(trustAllSsl)

        var currentUrl = rpcUrl
        var contentTypeHeader = "application/json-rpc"

        for (hop in 0..4) {
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .post(jsonPayload.toRequestBody(JSON_RPC_MEDIA_TYPE))
                .header("Content-Type", contentTypeHeader)
                .header("Accept", "application/json")
                .header("User-Agent", "Help-CSL-Zabbix-Agent/1.0")

            if (!bearerTokenHeader.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $bearerTokenHeader")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")?.trim().orEmpty()
                    if (location.isEmpty()) {
                        throw ZabbixApiException("Сервер Zabbix вернул перенаправление HTTP $code без заголовка Location")
                    }
                    val resolved = response.request.url.resolve(location)?.toString() ?: location
                    currentUrl = resolved
                    continue
                }

                if ((code == 412 || code == 415) && contentTypeHeader == "application/json-rpc") {
                    contentTypeHeader = "application/json"
                    continue
                }

                val bodyText = response.body?.string().orEmpty().trim()
                if (!response.isSuccessful) {
                    throw ZabbixApiException(
                        message = "HTTP $code от $currentUrl" +
                            (if (bodyText.isNotBlank()) ": ${bodyText.take(140)}" else ""),
                        isAuthError = (code == 401 || code == 403)
                    )
                }
                if (bodyText.isEmpty() || (!bodyText.startsWith("{") && !bodyText.startsWith("["))) {
                    throw ZabbixApiException("Ответ $currentUrl не является JSON-RPC Zabbix")
                }

                val root = JsonParser.parseString(bodyText).asJsonObject
                val errorObj = root.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
                if (errorObj != null) {
                    val errCode = errorObj.str("code").toIntOrNull() ?: -1
                    val errMsg = errorObj.str("message")
                    val errData = errorObj.str("data")
                    val combined = listOf(errMsg, errData)
                        .filter { it.isNotBlank() }
                        .joinToString(" — ")
                        .ifBlank { "Ошибка Zabbix JSON-RPC ($errCode)" }
                    val lower = combined.lowercase()
                    val isAuth = lower.contains("login name or password is incorrect") ||
                        lower.contains("incorrect user name or password") ||
                        lower.contains("not authorized") ||
                        lower.contains("session terminated") ||
                        lower.contains("re-login") ||
                        lower.contains("authoriz") ||
                        lower.contains("blocked")
                    throw ZabbixApiException(combined, code = errCode, isAuthError = isAuth)
                }

                val resultEl = root.get("result")
                    ?: throw ZabbixApiException("В ответе Zabbix отсутствует поле result")
                return resultEl to currentUrl
            }
        }

        throw IOException("Слишком много перенаправлений при подключении к Zabbix ($rpcUrl)")
    }

    companion object {
        private val JSON_RPC_MEDIA_TYPE = "application/json".toMediaType()

        @Volatile
        private var cachedNoRedirectStandard: OkHttpClient? = null

        @Volatile
        private var cachedNoRedirectTrustAll: OkHttpClient? = null

        private fun noRedirectClient(trustAllSsl: Boolean): OkHttpClient {
            return if (trustAllSsl) {
                cachedNoRedirectTrustAll ?: synchronized(this) {
                    cachedNoRedirectTrustAll ?: JiraApiClient.sharedClient(trustAllSsl = true)
                        .newBuilder()
                        .connectTimeout(12, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build()
                        .also { cachedNoRedirectTrustAll = it }
                }
            } else {
                cachedNoRedirectStandard ?: synchronized(this) {
                    cachedNoRedirectStandard ?: JiraApiClient.sharedClient(trustAllSsl = false)
                        .newBuilder()
                        .connectTimeout(12, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build()
                        .also { cachedNoRedirectStandard = it }
                }
            }
        }

        fun normalizeWebBaseUrl(rawUrl: String): String {
            var cleaned = rawUrl.trim()
            if (cleaned.isEmpty()) cleaned = AppSettings.DEFAULT_ZABBIX_URL
            if (!cleaned.startsWith("http://", ignoreCase = true) &&
                !cleaned.startsWith("https://", ignoreCase = true)
            ) {
                cleaned = "http://$cleaned"
            }
            cleaned = cleaned.substringBefore('?').substringBefore('#').trimEnd('/')
            if (cleaned.endsWith("/api_jsonrpc.php", ignoreCase = true)) {
                cleaned = cleaned.removeSuffix("/api_jsonrpc.php")
            } else if (cleaned.endsWith(".php", ignoreCase = true)) {
                cleaned = cleaned.substringBeforeLast('/')
            }
            return cleaned.trimEnd('/')
        }

        fun buildCandidateRpcUrls(rawUrl: String): List<String> {
            val base = normalizeWebBaseUrl(rawUrl)
            val parsed = base.toHttpUrlOrNull()
            val list = LinkedHashSet<String>()

            if (rawUrl.trim().endsWith("/api_jsonrpc.php", ignoreCase = true)) {
                val direct = if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl.trim() else "http://${rawUrl.trim()}"
                list.add(direct)
            }

            list.add("$base/api_jsonrpc.php")
            if (parsed != null) {
                val altScheme = if (parsed.isHttps) "http" else "https"
                val altBase = parsed.newBuilder().scheme(altScheme).build().toString().trimEnd('/')
                list.add("$altBase/api_jsonrpc.php")
                if (!base.lowercase().endsWith("/zabbix")) {
                    list.add("$base/zabbix/api_jsonrpc.php")
                    list.add("$altBase/zabbix/api_jsonrpc.php")
                }
            } else if (!base.lowercase().endsWith("/zabbix")) {
                list.add("$base/zabbix/api_jsonrpc.php")
            }
            return list.toList()
        }

        private fun isInternalCorporateHost(rawUrl: String): Boolean {
            val lower = rawUrl.lowercase()
            return lower.contains(".csl.by") ||
                lower.contains(".local") ||
                lower.contains("192.168.") ||
                lower.contains("10.") ||
                lower.contains("172.")
        }

        private fun parseMajorMinor(version: String): Double {
            val parts = version.trim().split('.')
            if (parts.size < 2) return 0.0
            val major = parts[0].filter(Char::isDigit).toIntOrNull() ?: return 0.0
            val minor = parts[1].filter(Char::isDigit).toIntOrNull() ?: 0
            return "$major.$minor".toDoubleOrNull() ?: 0.0
        }

        private fun JsonObject.str(key: String): String {
            val el = this.get(key) ?: return ""
            return if (el.isJsonNull) "" else if (el.isJsonPrimitive) el.asString else ""
        }
    }
}
