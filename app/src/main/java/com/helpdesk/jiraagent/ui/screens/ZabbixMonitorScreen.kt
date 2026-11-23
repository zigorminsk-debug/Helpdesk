package com.helpdesk.jiraagent.ui.screens

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.helpdesk.jiraagent.data.ZabbixApiClient
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.ZabbixDashboardSnapshot
import com.helpdesk.jiraagent.model.ZabbixMutedRule
import com.helpdesk.jiraagent.model.ZabbixProblem
import com.helpdesk.jiraagent.model.ZabbixSeverity
import com.helpdesk.jiraagent.ui.HostDiagnosticUiState
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class ZabbixViewMode {
    PROBLEMS_API,
    WEB_PORTAL
}

private val ZABBIX_ALERT_THRESHOLD_OPTIONS = listOf(
    5 to "Только Disaster (5)",
    4 to "High и Disaster (4–5)",
    3 to "Average и выше (3–5)",
    2 to "Warning и выше (2–5)"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZabbixMonitorScreen(
    settings: AppSettings,
    snapshot: ZabbixDashboardSnapshot?,
    dismissedKeys: Set<String>,
    mutedRules: List<ZabbixMutedRule>,
    lastError: String?,
    statusFeedback: String?,
    isLoading: Boolean,
    hostDiagnosticState: HostDiagnosticUiState,
    onSaveAndConnect: (url: String, login: String, password: String, enabled: Boolean, minSeverity: Int) -> Unit,
    onRefresh: () -> Unit,
    onAcknowledge: (ZabbixProblem, String?) -> Unit,
    onDismissProblem: (ZabbixProblem) -> Unit,
    onDismissAllCurrent: (List<ZabbixProblem>) -> Unit,
    onRestoreProblem: (ZabbixProblem) -> Unit,
    onRestoreAllDismissed: () -> Unit,
    onMuteForever: (ZabbixProblem) -> Unit,
    onUnmuteRule: (String) -> Unit,
    onClearAllMuted: () -> Unit,
    onTestZabbixBanner: () -> Unit,
    onRunHostDiagnostics: (String) -> Unit,
    onDismissHostDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var viewMode by rememberSaveable { mutableStateOf(ZabbixViewMode.PROBLEMS_API) }
    var showAuthCard by rememberSaveable { mutableStateOf(!settings.isZabbixConfigured) }
    var urlDraft by rememberSaveable(settings.zabbixUrl) {
        mutableStateOf(settings.zabbixUrl.ifBlank { AppSettings.DEFAULT_ZABBIX_URL })
    }
    var loginDraft by rememberSaveable(settings.zabbixLogin) { mutableStateOf(settings.zabbixLogin) }
    var passwordDraft by rememberSaveable(settings.zabbixPassword) { mutableStateOf(settings.zabbixPassword) }
    var enabledDraft by rememberSaveable(settings.zabbixEnabled) { mutableStateOf(settings.zabbixEnabled) }
    var minSeverityDraft by rememberSaveable(settings.zabbixMinAlertSeverity) {
        mutableIntStateOf(settings.normalizedZabbixMinSeverity)
    }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 0 = Новые / активные, 1 = Ошибки и критические (3..5), 2 = Неподтверждённые, 3 = Скрытые и нерешаемые
    var listFilterMode by rememberSaveable { mutableIntStateOf(0) }

    var ackDialogTarget by remember { mutableStateOf<ZabbixProblem?>(null) }
    var ackCommentDraft by remember { mutableStateOf("") }

    LaunchedEffect(settings.isZabbixConfigured) {
        if (!settings.isZabbixConfigured) {
            showAuthCard = true
        }
    }

    ackDialogTarget?.let { prob ->
        AlertDialog(
            onDismissRequest = { ackDialogTarget = null },
            title = {
                Text(
                    text = "Подтвердить событие Zabbix",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${prob.hostWithIpLabel}\n${prob.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = ackCommentDraft,
                        onValueChange = { ackCommentDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Комментарий в Zabbix (необязательно)") },
                        placeholder = { Text("Взято в работу, проверяем узел…") },
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAcknowledge(prob, ackCommentDraft)
                        ackDialogTarget = null
                        ackCommentDraft = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CslSuccessGreen)
                ) {
                    Text("Подтвердить (Ack)")
                }
            },
            dismissButton = {
                TextButton(onClick = { ackDialogTarget = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    if (hostDiagnosticState.targetHost.isNotBlank()) {
        AlertDialog(
            onDismissRequest = onDismissHostDiagnostics,
            title = {
                Text(
                    text = "Диагностика узла ${hostDiagnosticState.targetHost}",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (hostDiagnosticState.isRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CslOrange)
                        Text("Проверка ICMP / TCP-портов…")
                    }
                } else {
                    val rep = hostDiagnosticState.report
                    if (rep == null) {
                        Text("Нет данных диагностики для указанного узла")
                    } else {
                        val openPorts = rep.portStatuses.filter { it.isOpen }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("IP: ${rep.resolvedIp ?: "не определён"}", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (rep.icmpReachable) {
                                    "🟢 Пинг (ICMP): доступен (${rep.icmpLatencyMs ?: 0} мс)"
                                } else {
                                    "🔴 Пинг (ICMP): нет ответа"
                                }
                            )
                            Text(
                                text = if (openPorts.isEmpty()) {
                                    "Открытые TCP-порты не обнаружены"
                                } else {
                                    "Открытые порты: " + openPorts.joinToString(", ") { "${it.port}/${it.serviceName} (${it.latencyMs ?: 0}мс)" }
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissHostDiagnostics) { Text("Закрыть") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Top Mode & Quick Controls Bar
        Surface(
            color = CslMidnight,
            contentColor = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = viewMode == ZabbixViewMode.PROBLEMS_API,
                    onClick = { viewMode = ZabbixViewMode.PROBLEMS_API },
                    label = { Text("📊 Проблемы", maxLines = 1, softWrap = false) }
                )
                FilterChip(
                    selected = viewMode == ZabbixViewMode.WEB_PORTAL,
                    onClick = { viewMode = ZabbixViewMode.WEB_PORTAL },
                    label = { Text("🌐 Веб z.csl.by", maxLines = 1, softWrap = false) }
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { showAuthCard = !showAuthCard },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (showAuthCard) Icons.Default.ExpandLess else Icons.Default.Settings,
                        contentDescription = "Настройки Zabbix",
                        tint = if (settings.isZabbixConfigured) Color.White else CslOrange
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить Zabbix",
                        tint = CslOrange
                    )
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = CslOrange
            )
        }

        if (viewMode == ZabbixViewMode.WEB_PORTAL) {
            ZabbixEmbeddedWebPortal(
                settings = settings.copy(
                    zabbixUrl = urlDraft.ifBlank { AppSettings.DEFAULT_ZABBIX_URL },
                    zabbixLogin = loginDraft,
                    zabbixPassword = passwordDraft
                ),
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            return@Column
        }

        val mutedKeySet = remember(mutedRules) {
            mutedRules.flatMap { it.allMatchKeys() }.toSet()
        }
        val allServerProblems = snapshot?.problems.orEmpty()
        val activeProblems = remember(allServerProblems, dismissedKeys, mutedKeySet) {
            allServerProblems.filterNot { it.isHiddenFromActiveFeed(dismissedKeys, mutedKeySet) }
        }
        val dismissedActiveProblems = remember(allServerProblems, dismissedKeys, mutedKeySet) {
            allServerProblems.filter { !it.isMutedBy(mutedKeySet) && it.isDismissedBy(dismissedKeys) }
        }
        val hiddenTotalCount = dismissedActiveProblems.size + mutedRules.size

        val filteredActiveProblems = remember(activeProblems, searchQuery, listFilterMode) {
            val q = searchQuery.trim().lowercase()
            activeProblems.filter { p ->
                val matchesFilter = when (listFilterMode) {
                    1 -> p.severityLevel >= 3
                    2 -> !p.acknowledged
                    else -> true
                }
                val matchesQuery = q.isEmpty() ||
                    p.name.lowercase().contains(q) ||
                    p.effectiveHostLabel.lowercase().contains(q) ||
                    p.hostTechnicalName.lowercase().contains(q) ||
                    p.hostIp.lowercase().contains(q) ||
                    p.opData.lowercase().contains(q)
                matchesFilter && matchesQuery
            }
        }

        val activeDisasterCount = remember(activeProblems) { activeProblems.count { it.severityLevel >= 5 } }
        val activeHighCount = remember(activeProblems) { activeProblems.count { it.severityLevel == 4 } }
        val activeAverageCount = remember(activeProblems) { activeProblems.count { it.severityLevel == 3 } }
        val activeWarningCount = remember(activeProblems) { activeProblems.count { it.severityLevel == 2 } }
        val activeCritAndErrorCount = remember(activeProblems) { activeProblems.count { it.severityLevel >= 3 } }
        val activeUnackedCount = remember(activeProblems) { activeProblems.count { !it.acknowledged } }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Authorization & Alert Threshold Card
            item(key = "zabbix_auth_card") {
                AnimatedVisibility(visible = showAuthCard || !settings.isZabbixConfigured) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.55f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = CslOrange
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Авторизация Zabbix (z.csl.by)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Вход по логину и паролю · срочный баннер при ошибках и критических предупреждениях",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (settings.isZabbixConfigured) {
                                    IconButton(onClick = { showAuthCard = false }) {
                                        Icon(Icons.Default.Close, contentDescription = "Свернуть")
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = urlDraft,
                                onValueChange = { urlDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Сервер Zabbix") },
                                placeholder = { Text("http://z.csl.by") },
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = loginDraft,
                                onValueChange = { loginDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Логин Zabbix") },
                                placeholder = { Text("Ваш логин на z.csl.by") },
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = passwordDraft,
                                onValueChange = { passwordDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Пароль Zabbix") },
                                singleLine = true,
                                visualTransformation = if (showPassword) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Показать пароль"
                                        )
                                    }
                                }
                            )

                            if (settings.usernameOrEmail.isNotBlank() && settings.passwordOrToken.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        loginDraft = settings.usernameOrEmail
                                        passwordDraft = settings.passwordOrToken
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text("Подставить логин и пароль из учётной записи Jira")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Срочный баннер Zabbix (как заявка от клиента)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Звук и баннер только при попадании проблемы в Disaster",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = enabledDraft,
                                    onCheckedChange = { enabledDraft = it }
                                )
                            }

                            Text(
                                text = "Порог вывода срочного баннера:",
                                style = MaterialTheme.typography.labelLarge
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ZABBIX_ALERT_THRESHOLD_OPTIONS.forEach { (lvl, title) ->
                                    FilterChip(
                                        selected = minSeverityDraft == lvl,
                                        onClick = { minSeverityDraft = lvl },
                                        label = { Text(title, maxLines = 1) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onSaveAndConnect(
                                            urlDraft,
                                            loginDraft,
                                            passwordDraft,
                                            enabledDraft,
                                            minSeverityDraft
                                        )
                                        if (loginDraft.isNotBlank() && passwordDraft.isNotBlank()) {
                                            showAuthCard = false
                                        }
                                    },
                                    enabled = !isLoading,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CslOrange)
                                ) {
                                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Войти и сохранить", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                OutlinedButton(
                                    onClick = onTestZabbixBanner,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NotificationsActive,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = CslOrange
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Тест", maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            // Status / Error Banner
            if (!lastError.isNullOrBlank()) {
                item(key = "zabbix_error_banner") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ошибка связи с Zabbix",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = lastError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            TextButton(onClick = { showAuthCard = true }) {
                                Text("Настроить")
                            }
                        }
                    }
                }
            } else if (!statusFeedback.isNullOrBlank()) {
                item(key = "zabbix_ok_banner") {
                    Surface(
                        color = CslSuccessGreen.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusFeedback,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = CslSuccessGreen,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 2. KPI Summary & Search Bar
            if (snapshot != null) {
                item(key = "zabbix_kpi_summary") {
                    val syncTimeStr = remember(snapshot.fetchedAtMillis) {
                        DateTimeFormatter.ofPattern("HH:mm:ss")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(snapshot.fetchedAtMillis))
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(CslSuccessGreen)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = buildString {
                                        append(settings.zabbixUrl.ifBlank { AppSettings.DEFAULT_ZABBIX_URL })
                                        if (snapshot.serverVersion.isNotBlank()) {
                                            append(" · v").append(snapshot.serverVersion)
                                        }
                                        if (snapshot.monitoredHostsCount > 0) {
                                            append(" · узлов: ").append(snapshot.monitoredHostsCount)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = syncTimeStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ZabbixCountPill("Аварии (5)", activeDisasterCount, Color(0xFFD93025))
                                ZabbixCountPill("Критич. (4)", activeHighCount, Color(0xFFE65100))
                                ZabbixCountPill("Ошибки (3)", activeAverageCount, CslOrange)
                                ZabbixCountPill("Предупр. (2)", activeWarningCount, Color(0xFFD97706))
                            }

                            if (allServerProblems.size > activeProblems.size) {
                                Text(
                                    text = "Скрыто старых / нерешаемых: ${allServerProblems.size - activeProblems.size} (показываются только новые)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item(key = "zabbix_search_and_filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                                    }
                                }
                            },
                            placeholder = { Text("Поиск по узлу, IP-адресу или проблеме…") },
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = listFilterMode == 0,
                                onClick = { listFilterMode = 0 },
                                label = { Text("Новые / активные (${activeProblems.size})", maxLines = 1, softWrap = false) }
                            )
                            FilterChip(
                                selected = listFilterMode == 1,
                                onClick = { listFilterMode = 1 },
                                label = { Text("Ошибки и критич. ($activeCritAndErrorCount)", maxLines = 1, softWrap = false) }
                            )
                            FilterChip(
                                selected = listFilterMode == 2,
                                onClick = { listFilterMode = 2 },
                                label = { Text("Неподтв. ($activeUnackedCount)", maxLines = 1, softWrap = false) }
                            )
                            FilterChip(
                                selected = listFilterMode == 3,
                                onClick = { listFilterMode = 3 },
                                label = { Text("🔕 Скрытые ($hiddenTotalCount)", maxLines = 1, softWrap = false) }
                            )
                        }

                        if (listFilterMode != 3 && filteredActiveProblems.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { onDismissAllCurrent(filteredActiveProblems) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, CslOrange.copy(alpha = 0.65f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = CslOrange
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Закрыть все текущие (${filteredActiveProblems.size}) — только новые",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (listFilterMode == 3) {
                    // Hidden & Permanently Muted Management Tab
                    item(key = "zabbix_muted_rules_header") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NotificationsOff,
                                        contentDescription = null,
                                        tint = CslOrange,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Нерешаемые задачи (${mutedRules.size})",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "По этим триггерам уведомления и баннеры отключены навсегда",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (mutedRules.isNotEmpty()) {
                                        TextButton(onClick = onClearAllMuted) {
                                            Text("Очистить")
                                        }
                                    }
                                }

                                if (mutedRules.isEmpty()) {
                                    Text(
                                        text = "Список пуст. Нажмите «Не решаема» на любой карточке, чтобы больше не получать по ней уведомления.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    val q = searchQuery.trim().lowercase()
                                    mutedRules.filter { r ->
                                        q.isEmpty() ||
                                            r.hostLabel.lowercase().contains(q) ||
                                            r.problemName.lowercase().contains(q)
                                    }.forEach { rule ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Surface(
                                                    color = zabbixSeverityColor(rule.severityLevel),
                                                    shape = RoundedCornerShape(5.dp)
                                                ) {
                                                    Text(
                                                        text = ZabbixSeverity.fromLevel(rule.severityLevel).shortBadge,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = rule.hostLabel.ifBlank { "Триггер Zabbix" },
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = rule.problemName,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                OutlinedButton(
                                                    onClick = { onUnmuteRule(rule.ruleKey) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Вернуть", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "zabbix_dismissed_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Закрытые текущие карточки (${dismissedActiveProblems.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (dismissedActiveProblems.isNotEmpty()) {
                                TextButton(onClick = onRestoreAllDismissed) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Вернуть все")
                                }
                            }
                        }
                    }

                    if (dismissedActiveProblems.isEmpty()) {
                        item(key = "zabbix_no_dismissed") {
                            Text(
                                text = "Нет закрытых текущих карточек.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    } else {
                        val q = searchQuery.trim().lowercase()
                        val shownDismissed = dismissedActiveProblems.filter { p ->
                            q.isEmpty() ||
                                p.name.lowercase().contains(q) ||
                                p.effectiveHostLabel.lowercase().contains(q) ||
                                p.hostIp.lowercase().contains(q)
                        }
                        items(
                            items = shownDismissed,
                            key = { "dismissed_" + it.alertKey }
                        ) { problem ->
                            ZabbixDismissedCard(
                                problem = problem,
                                onRestore = { onRestoreProblem(problem) },
                                onMuteForever = { onMuteForever(problem) }
                            )
                        }
                    }
                } else if (filteredActiveProblems.isEmpty()) {
                    item(key = "zabbix_all_ok") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = CslSuccessGreen.copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, CslSuccessGreen.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = CslSuccessGreen,
                                        modifier = Modifier.size(30.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when {
                                                allServerProblems.isEmpty() -> "Все контролируемые узлы в норме"
                                                activeProblems.isEmpty() -> "Новых проблем нет (старые скрыты)"
                                                else -> "По выбранному фильтру проблем нет"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "При появлении новой ошибки или аварии она появится здесь и вызовет срочный баннер со звуком.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (hiddenTotalCount > 0) {
                                    TextButton(
                                        onClick = { listFilterMode = 3 },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) {
                                        Text("Посмотреть скрытые и нерешаемые ($hiddenTotalCount)")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(
                        items = filteredActiveProblems,
                        key = { it.alertKey }
                    ) { problem ->
                        ZabbixProblemCard(
                            problem = problem,
                            onCopyHost = { hostStr ->
                                clipboard.setText(AnnotatedString(hostStr))
                                Toast.makeText(context, "Скопировано: $hostStr", Toast.LENGTH_SHORT).show()
                            },
                            onAcknowledgeClick = {
                                ackCommentDraft = ""
                                ackDialogTarget = problem
                            },
                            onDiagnoseHost = {
                                val target = problem.hostIp.ifBlank { problem.hostTechnicalName }.ifBlank { problem.effectiveHostLabel }
                                onRunHostDiagnostics(target)
                            },
                            onCloseCard = { onDismissProblem(problem) },
                            onMuteForever = { onMuteForever(problem) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZabbixDismissedCard(
    problem: ZabbixProblem,
    onRestore: () -> Unit,
    onMuteForever: () -> Unit
) {
    val sevColor = zabbixSeverityColor(problem.severityLevel)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(color = sevColor, shape = RoundedCornerShape(5.dp)) {
                    Text(
                        text = problem.severity.shortBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = problem.hostWithIpLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = problem.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Вернуть", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onMuteForever,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.NotificationsOff, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Не решаема", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ZabbixCountPill(label: String, count: Int, accent: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (count > 0) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (count > 0) accent else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = if (count > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZabbixProblemCard(
    problem: ZabbixProblem,
    onCopyHost: (String) -> Unit,
    onAcknowledgeClick: () -> Unit,
    onDiagnoseHost: () -> Unit,
    onCloseCard: () -> Unit,
    onMuteForever: () -> Unit
) {
    val sevColor = zabbixSeverityColor(problem.severityLevel)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (problem.severityLevel >= 4 && !problem.acknowledged) 1.8.dp else 1.dp,
            color = sevColor.copy(alpha = if (problem.acknowledged) 0.35f else 0.85f)
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = sevColor,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = problem.severity.shortBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                Text(
                    text = problem.hostWithIpLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onCopyHost(problem.hostIp.ifBlank { problem.hostTechnicalName.ifBlank { problem.effectiveHostLabel } })
                        }
                )

                IconButton(
                    onClick = {
                        onCopyHost(problem.hostIp.ifBlank { problem.hostTechnicalName.ifBlank { problem.effectiveHostLabel } })
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Скопировать адрес узла",
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onCloseCard,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть карточку",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = problem.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (problem.opData.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Текущее значение: ${problem.opData}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Начало: ${problem.formattedStartTime()} · Длится: ${problem.formattedDuration()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (problem.acknowledged) {
                    Surface(
                        color = CslSuccessGreen.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = "✓ Подтверждено",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CslSuccessGreen,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (problem.ackMessage.isNotBlank()) {
                Text(
                    text = "Комментарий: ${problem.ackMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 2.dp))

            // Row 1: Acknowledge & Host Diagnostics (compact padding prevents clipping on large fonts)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (problem.eventId.isNotBlank() && problem.eventId != "0") {
                    OutlinedButton(
                        onClick = onAcknowledgeClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TaskAlt,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = CslSuccessGreen
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (problem.acknowledged) "Комментарий" else "Подтвердить",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDiagnoseHost,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = CslOrange
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Пинг",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Row 2: Close current card (only new ones appear) & Mark unsolvable (mute forever)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCloseCard,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Скрыть",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onMuteForever,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsOff,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = Color(0xFFD93025)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Не решаема",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

fun zabbixSeverityColor(level: Int): Color {
    return when {
        level >= 5 -> Color(0xFFD93025) // Disaster - bright red
        level == 4 -> Color(0xFFE65100) // High - deep orange-red
        level == 3 -> Color(0xFFF7631B) // Average - CSL orange
        level == 2 -> Color(0xFFD97706) // Warning - amber
        level == 1 -> Color(0xFF0284C7) // Info - sky blue
        else -> Color(0xFF64748B)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ZabbixEmbeddedWebPortal(
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val baseUrl = remember(settings.zabbixUrl) {
        ZabbixApiClient.normalizeWebBaseUrl(settings.zabbixUrl)
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var currentLoadedBase by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner, webViewRef) {
        val wv = webViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { wv?.onResume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { wv?.onPause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { webViewRef?.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = CslOrange
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = baseUrl,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Перезагрузить")
                }
            }
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    val cookieMgr = CookieManager.getInstance()
                    cookieMgr.setAcceptCookie(true)
                    cookieMgr.setAcceptThirdPartyCookies(this, true)

                    this.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }

                    var autoSubmittedZabbixLoginOnce = false

                    webViewClient = object : WebViewClient() {
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?
                        ) {
                            if (settings.zabbixLogin.isNotBlank() && settings.zabbixPassword.isNotBlank()) {
                                handler?.proceed(settings.zabbixLogin.trim(), settings.zabbixPassword)
                            } else {
                                super.onReceivedHttpAuthRequest(view, handler, host, realm)
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            val urlStr = error?.url.orEmpty().lowercase()
                            if (settings.trustSelfSignedSsl || urlStr.contains(".csl.by")) {
                                handler?.proceed()
                            } else {
                                super.onReceivedSslError(view, handler, error)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            canGoBack = view?.canGoBack() == true
                            runCatching { CookieManager.getInstance().flush() }

                            // Auto-fill Zabbix web login form (#name + #password + #enter) once per WebView session
                            if (!autoSubmittedZabbixLoginOnce &&
                                settings.zabbixLogin.isNotBlank() &&
                                settings.zabbixPassword.isNotBlank()
                            ) {
                                val uJson = JSONObject.quote(settings.zabbixLogin.trim())
                                val pJson = JSONObject.quote(settings.zabbixPassword)
                                val jsAutofill = """
                                    (function() {
                                        var u = document.querySelector('input[name="name"], input#name');
                                        var p = document.querySelector('input[type="password"], input[name="password"], input#password');
                                        if (u && p && !u.value) {
                                            u.value = $uJson;
                                            p.value = $pJson;
                                            u.dispatchEvent(new Event('input', { bubbles: true }));
                                            p.dispatchEvent(new Event('input', { bubbles: true }));
                                            var btn = document.querySelector('button#enter, button[name="enter"], input[name="enter"], button[type="submit"]');
                                            if (btn) { btn.click(); return 'submitted'; }
                                        }
                                        return 'skip';
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(jsAutofill) { res ->
                                    if (res?.contains("submitted") == true) {
                                        autoSubmittedZabbixLoginOnce = true
                                    }
                                }
                            }
                        }
                    }

                    webViewRef = this
                    currentLoadedBase = baseUrl
                    loadUrl(baseUrl)
                }
            },
            update = { view ->
                if (baseUrl.isNotBlank() && baseUrl != currentLoadedBase) {
                    currentLoadedBase = baseUrl
                    view.loadUrl(baseUrl)
                }
                canGoBack = view.canGoBack()
            },
            onRelease = { view ->
                runCatching { view.stopLoading() }
                runCatching { view.onPause() }
                runCatching { view.destroy() }
            }
        )
    }
}
