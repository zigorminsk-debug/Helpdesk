package com.helpdesk.jiraagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.helpdesk.jiraagent.domain.EmployeeActivityStatus
import com.helpdesk.jiraagent.domain.EmployeeKpiItem
import com.helpdesk.jiraagent.domain.PeriodAnalyticsSummary
import com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator
import com.helpdesk.jiraagent.domain.ShiftReportPeriod
import com.helpdesk.jiraagent.domain.TicketStopwatchEntry
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.model.TicketTimingFormatter
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen

/**
 * Employee KPI & Shift Activity Monitor (`📈 KPI по сотрудникам и мониторинг активности`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShiftAnalyticsDialog(
    tickets: List<HelpdeskTicket>,
    kpiSampleTickets: List<HelpdeskTicket> = emptyList(),
    isLoadingKpi: Boolean = false,
    isJiraConfigured: Boolean = false,
    stopwatches: Map<String, TicketStopwatchEntry> = emptyMap(),
    connectedUserDisplayName: String?,
    usernameOrEmail: String,
    onRefreshKpi: () -> Unit = {},
    onOpenTicket: (String) -> Unit = {},
    onSelectClientFilter: (String) -> Unit,
    onExportPdf: (PeriodAnalyticsSummary) -> Unit = {},
    onExportCsv: (PeriodAnalyticsSummary) -> Unit = {},
    onDismiss: () -> Unit
) {
    var period by rememberSaveable { mutableStateOf(ShiftReportPeriod.TODAY) }
    var employeeSearch by rememberSaveable { mutableStateOf("") }
    var expandedEmployee by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (isJiraConfigured && kpiSampleTickets.isEmpty()) {
            onRefreshKpi()
        }
    }

    val combinedTickets = remember(tickets, kpiSampleTickets) {
        val byKey = LinkedHashMap<String, HelpdeskTicket>()
        tickets.forEach { byKey[it.key.uppercase()] = it }
        kpiSampleTickets.forEach { sample ->
            val k = sample.key.uppercase()
            val existing = byKey[k]
            if (existing == null) {
                byKey[k] = sample
            } else if (existing.resolvedEpochMillis <= 0L && sample.resolvedEpochMillis > 0L) {
                byKey[k] = existing.copy(
                    resolvedIso = sample.resolvedIso,
                    resolvedEpochMillis = sample.resolvedEpochMillis
                )
            }
        }
        byKey.values.toList()
    }

    val summary = remember(combinedTickets, connectedUserDisplayName, usernameOrEmail, stopwatches, period) {
        ShiftAnalyticsCalculator.computeForPeriod(
            tickets = combinedTickets,
            period = period,
            connectedUserDisplayName = connectedUserDisplayName,
            usernameOrEmail = usernameOrEmail,
            stopwatches = stopwatches
        )
    }

    val visibleEmployees = remember(summary.employeeKpis, employeeSearch) {
        val q = employeeSearch.trim().lowercase()
        if (q.isEmpty()) summary.employeeKpis
        else summary.employeeKpis.filter { it.engineerName.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .heightIn(max = 680.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.BarChart, contentDescription = null, tint = CslOrange)
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            "KPI сотрудников",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "Мониторинг активности (${summary.periodRangeDisplay})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isJiraConfigured) {
                    IconButton(
                        onClick = onRefreshKpi,
                        enabled = !isLoadingKpi
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Обновить KPI из Jira",
                            tint = CslOrange
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isLoadingKpi) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = CslOrange
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ShiftReportPeriod.entries.forEach { p ->
                        val selected = period == p
                        FilterChip(
                            selected = selected,
                            onClick = { period = p },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CslOrange,
                                selectedLabelColor = Color.White
                            ),
                            label = {
                                Text(
                                    p.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiMiniBox("Поступило", "+${summary.createdInPeriod}", CslOrange, Modifier.weight(1f))
                    KpiMiniBox("Закрыто", "${summary.closedInPeriod}", CslSuccessGreen, Modifier.weight(1f))
                    KpiMiniBox("Мной", "${summary.closedByMeInPeriod}", CslSuccessGreen, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiMiniBox("Нов / Раб", "${summary.activeNewCount}/${summary.activeInProgressCount}", CslOrange, Modifier.weight(1f))
                    KpiMiniBox(
                        "Ср. решен.",
                        TicketTimingFormatter.formatDurationMinutes(summary.avgTeamResolutionMinutes),
                        MaterialTheme.colorScheme.onSurface,
                        Modifier.weight(1f)
                    )
                    KpiMiniBox("SLA <15м", "${summary.slaGreenPercent}%", CslSuccessGreen, Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onExportPdf(summary) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CslOrange,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📄 PDF KPI", style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = { onExportCsv(summary) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📊 Excel (.csv)", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                }

                HorizontalDivider()

                Text(
                    text = "👨‍💻 KPI и мониторинг активности сотрудников (${visibleEmployees.size}):",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = CslOrange
                )

                OutlinedTextField(
                    value = employeeSearch,
                    onValueChange = { employeeSearch = it },
                    placeholder = { Text("Поиск по ФИО сотрудника…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (visibleEmployees.isEmpty()) {
                    Text(
                        text = "За выбранный период (${period.title}) назначенных или закрытых заявок по сотрудникам не найдено.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    visibleEmployees.forEachIndexed { idx, emp ->
                        val isExpanded = expandedEmployee == emp.engineerName
                        AndroidEmployeeKpiCard(
                            rank = idx + 1,
                            item = emp,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                expandedEmployee = if (isExpanded) null else emp.engineerName
                            },
                            onOpenTicket = onOpenTicket
                        )
                    }
                }

                if (summary.topClients.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "🏢 Топ клиентов по обращениям (нажмите для фильтра):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CslOrange
                    )
                    summary.topClients.take(8).forEach { cl ->
                        Surface(
                            onClick = {
                                onDismiss()
                                onSelectClientFilter(cl.clientName)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cl.clientName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Всего: ${cl.totalCount} (откр.: ${cl.openCount})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CslOrange,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidEmployeeKpiCard(
    rank: Int,
    item: EmployeeKpiItem,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenTicket: (String) -> Unit
) {
    val rankLabel = when (rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> "#$rank"
    }
    val statusColor = when (item.activityStatus) {
        EmployeeActivityStatus.ACTIVE_NOW -> CslSuccessGreen
        EmployeeActivityStatus.ACTIVE_RECENT -> CslOrange
        EmployeeActivityStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (item.isCurrentUser) CslOrange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            width = if (item.isCurrentUser) 1.5.dp else 1.dp,
            color = if (item.isCurrentUser) CslOrange else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$rankLabel ${item.engineerName}" + if (item.isCurrentUser) " (Вы)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = item.activityStatus.badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "✅ Закрыто: ${item.closedCount} (${item.shareOfTeamClosedPercent}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = CslSuccessGreen,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "· 🔧 В работе: ${item.inProgressCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "· ⏱ Ср.: ${TicketTimingFormatter.formatDurationMinutes(item.avgResolutionMinutes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.closedCount > 0) {
                    Text(
                        text = "· ⚡ <1ч: ${item.fastCloseUnder1hPercent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = CslOrange
                    )
                }
            }

            if (item.lastActivityEpochMs > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Посл. активность: ${TicketTimingFormatter.formatCompactMinsk(item.lastActivityEpochMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (isExpanded) "▲ Скрыть" else "▼ Заявки (${item.tickets.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CslOrange
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (item.shareOfTeamClosedPercent / 100f).coerceIn(0.03f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(99.dp)),
                color = CslSuccessGreen,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            if (isExpanded && item.tickets.isNotEmpty()) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    item.tickets.take(25).forEach { t ->
                        val closedAt = t.effectiveClosedEpochMillis
                        val timingLine = if (t.stage == TicketStage.CLOSED && closedAt > 0L) {
                            val dur = t.resolutionDurationMinutes?.let { " · ⏱ ${TicketTimingFormatter.formatDurationMinutes(it)}" }.orEmpty()
                            "📥 ${TicketTimingFormatter.formatFullMinsk(t.createdEpochMillis)} → ✅ ${TicketTimingFormatter.formatFullMinsk(closedAt)}$dur"
                        } else {
                            "📥 ${TicketTimingFormatter.formatFullMinsk(t.createdEpochMillis)} · [${t.status}]"
                        }
                        Surface(
                            onClick = { onOpenTicket(t.key) },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "${t.key} · ${t.reporterName} — ${t.summary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = timingLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (t.stage == TicketStage.CLOSED) CslSuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KpiMiniBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accent
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private data class GuideSection(
    val title: String,
    val bullets: List<String>
)

private val HELP_CSL_GUIDE_SECTIONS = listOf(
    GuideSection(
        title = "1. Быстрый старт и перенос настроек за 3 секунды",
        bullets = listOf(
            "Во вкладке «Настройки» укажите URL вашего сервера Jira (поддерживаются Jira Server / Data Center и Jira Cloud), способ авторизации (Логин+Пароль, Email+API Token или PAT) и нажмите «Проверить подключение».",
            "Если у коллеги приложение уже настроено — нажмите «Перенос настроек по QR-коду» во вкладке «Настройки», отсканируйте QR с его экрана, и все адреса, фильтры, РБ-график и шаблоны подтянутся автоматически."
        )
    ),
    GuideSection(
        title = "2. Вкладки заявок, свайпы, фильтры и массовые действия",
        bullets = listOf(
            "4 вкладки очереди: «Новые» (включая «Ожидание поддержки»), «В работе», «Закрытые» и «Сервисные» (куда без звука уходят zabbix, авто-боты и мониторинг).",
            "Под вкладками расположен быстрый фильтр «Все / Мои / Свободные» со счётчиками.",
            "Потяните список вниз (Pull-to-Refresh) для мгновенной синхронизации.",
            "Свайп карточки ВПРАВО берёт заявку «В работу». Свайп ВЛЕВО сразу звонит клиенту (или открывает обсуждение тикета в чате админов).",
            "Кнопка «☑» в строке поиска (или долгое нажатие) включает режим массового выбора — можно взять в работу, прокомментировать или закрыть сразу несколько заявок.",
            "Кнопка «+» в правом нижнем углу позволяет создать новую заявку прямо с телефона с голосовым вводом 🎤."
        )
    ),
    GuideSection(
        title = "3. Уведомления, график РБ, наушники MIUI/HyperOS и анти-шторм",
        bullets = listOf(
            "Баннеры с кнопками «В работу» и «Позвонить» работают прямо из шторки уведомлений без открытия программы.",
            "По умолчанию оповещения звучат строго по производственному календарю Республики Беларусь (Пн–Пт 09:00–17:30, учёт праздников РБ и рабочих суббот). Заявки вне графика сохраняются и оповестят вас в 09:00 следующего рабочего дня.",
            "Плитка «Help-CSL Дежурство» в верхней шторке Android в 1 касание переключает режим «График РБ» ↔ «Дежурство 24/7».",
            "Двойной вывод звука (динамик + Bluetooth-гарнитура одновременно, включая Xiaomi MIUI / HyperOS) + голосовое чтение новых заявок в наушник (режим «За рулём»).",
            "Защита от «Шторма заявок»: если одновременно пришло 3+ обращений (авария у провайдера или 1С), приложение сыграет один сигнал и покажет сгруппированную сводку."
        )
    ),
    GuideSection(
        title = "4. Карточка заявки: Workflow, отсрочка, внутренние заметки и коллеги",
        bullets = listOf(
            "Кнопки движения заявки: «В работу» (автоматически назначает на вас), «Отложить на» (закрывает сейчас и автоматически пересоздаёт новую заявку в выбранное время с уведомлением), «Отклонить», «Завершить» (с возвратом на главный экран).",
            "Кнопка «👤 Коллеге» позволяет передать тикет конкретному инженеру из Jira.",
            "Над полем комментария есть переключатель «🌍 Ответ клиенту» ↔ «🔒 Внутренняя заметка (JSM)», быстрые 1-тап шаблоны и голосовой ввод 🎤.",
            "Блок «Досье клиента» сохраняет локальную тех-шпаргалку по организации (провайдер, где серверная, чей контакт), а блок «Прошлые обращения клиента» показывает историю тикетов."
        )
    ),
    GuideSection(
        title = "5. Инструменты выездного инженера: Сканер S/N, PDF-акт с подписью и Навигатор",
        bullets = listOf(
            "Кнопка сканера штрихкода/QR в поиске (и внутри заявки) считывает серийный или инвентарный номер (S/N) железа и находит всю историю его прошлых ремонтов в Jira.",
            "Кнопка «✍️ PDF-акт» открывает чек-лист работ и поле подписи клиента пальцем на экране, формирует фирменный PDF-акт в стиле csl.by и автоматически прикрепляет его в Jira.",
            "Если в тексте заявки указан адрес выезда (ул., пр-т, пер., БЦ), появляется зелёная кнопка «Маршрут в Навигатор» (Яндекс.Навигатор / Карты / Google Maps).",
            "Камера 📷 и вложения 📎 позволяют фотографировать оборудование с автосжатием и просматривать скриншоты из Jira с Pinch-to-Zoom."
        )
    ),
    GuideSection(
        title = "6. Вкладка «В ремонт»: журнал оборудования в сервисе",
        bullets = listOf(
            "Вкладка «В ремонт» в нижней панели (со счётчиком активного железа) предназначена для приёмки и учёта всей техники, поступившей на диагностику и ремонт (ноутбуки, ПК, принтеры/МФУ, серверы, сетевое оборудование, ИБП).",
            "Добавить устройство можно по кнопке «+ Принять», сканированием штрихкода S/N в строке поиска или прямо из карточки заявки Jira по кнопке «🔧 Занести технику во вкладку В ремонт» (поля клиента, телефона, номера тикета и неисправности заполнятся автоматически).",
            "Фильтры по этапам ремонта: «В работе» (Принято / Диагностика / В ремонте), «Ждёт ЗЧ», «Готово», архив «Выдано» и раздел аудита «Удалённые». Перевод на следующий этап доступен в 1 нажатие прямо с карточки.",
            "Аудит удалений: при удалении позиция НЕ исчезает бесследно — она попадает в раздел «Удалённые» с красной отметкой КТО удалил (ФИО / логин инженера), датой/временем, причиной и возможностью восстановления (↩ Восстановить).",
            "В поле «Клиент / Организация» автоматически подгружается справочник организаций и клиентов из Jira Helpdesk (JSM Organizations + авторы клиентских заявок с их телефонами) — можно выбрать клиента из выпадающего списка ▼ с автоподстановкой телефона, а любой введённый вручную новый клиент автоматически дополняет справочник.",
            "Быстрые чипы комплектности (Блок питания, Кабель 220В, Сумка, Картридж и др.), указание полки хранения, звонок и мессенджеры (Telegram / Viber / WhatsApp) для уведомления клиента о готовности.",
            "Кнопка «PDF» формирует брендированную A4 «Квитанцию о приёме в ремонт» или «Акт выдачи из ремонта» с подписью клиента пальцем на экране — документ можно отправить на печать/в мессенджер или прикрепить к заявке Jira.",
            "Журнал работает мгновенно офлайн и автоматически синхронизируется между всеми инженерами смены через служебный реестр Jira."
        )
    ),
    GuideSection(
        title = "7. Сетевой комбайн, Caller ID и учёт времени Worklog",
        bullets = listOf(
            "«🛠 Инструменты» → «⌨ Команды»: фильтры по платформе и темам, включая серверы и домен AD. Раскройте карточку, изучите инструкции и предупреждения и скопируйте команду или инструкцию для коллеги. «🔌 Порты» — офлайн-справочник портов, не сканер. На низком экране фильтры и результаты можно прокручивать.",
            "Автораспознавание 9–10-значных ID AnyDesk / RustDesk (копирование в 1 тап), IPv4, доменов и MAC-адресов с офлайн-определением вендора (MikroTik, Cisco, Hikvision, HP и др.).",
            "Встроенная диагностика узла проверяет ICMP Ping, TCP-порты (80, 443, 3389 RDP, 22 SSH, 8291 WinBox, 445 SMB), HTTP-ответ, остаток дней SSL-сертификата и адреса через системный резолвер устройства. MX/TXT/NS не запрашиваются; настройки системы могут использовать внешний DNS.",
            "Определитель номера (Caller ID): при входящем звонке с номера из базы тикетов показывает имя клиента и открытую заявку (включается в «Настройках» без доступа к журналу вызовов).",
            "После завершения звонка клиенту приложение предлагает в 1 тап списать 5/10/15/30 мин в Worklog Jira и продиктовать итог разговора."
        )
    ),
    GuideSection(
        title = "8. Внутренний чат админов, Офлайн-очередь, Виджет и Автообновление",
        bullets = listOf(
            "Вкладка «Чат» — закрытый внутренний чат смены поверх сервисной комнаты Jira (клиенты его не видят): дежурная заметка 📌 вверху, быстрые статусы (👀 Смотрю, 🚗 На выезде, ✅ Сделал), привязка тикетов через #, ответы и голос 🎤.",
            "Если пропала мобильная сеть, все действия, комментарии, Worklog и сообщения сохраняются в офлайн-очередь и автоматически уходят в Jira при появлении связи.",
            "На рабочий стол телефона доступен виджет 4×2 «Help-CSL Сводка заявок», а обновления APK скачиваются с GitHub Releases и ставятся поверх без потери настроек."
        )
    ),
    GuideSection(
        title = "9. Вкладка «В ремонт», справочник клиентов Helpdesk и аудит «Удалённые»",
        bullets = listOf(
            "Вкладка «В ремонт» ведёт единый журнал приёма и ремонта оборудования (REM-1001+) с этапами Принято → Диагностика → Ждёт ЗЧ → В ремонте → Готово → Выдано, комплектацией, полкой хранения и печатью PDF-квитанций А4.",
            "В поле «Клиент / Организация» автоматически подгружается список организаций и клиентов из Jira Helpdesk с живым поиском и автоподстановкой телефона; новые введённые клиенты автоматически пополняют справочник.",
            "При удалении карточки она не исчезает бесследно, а переходит в раздел «🗑 Удалённые» с записью ФИО/логина инженера, даты/времени, причины и кнопкой «↩ Восстановить»."
        )
    ),
    GuideSection(
        title = "10. Вкладки «Zabbix» (z.csl.by), «Wiki» (w.csl.by) и свежие/прошлые заявки",
        bullets = listOf(
            "При старте приложения и при нажатии кнопки «Назад» (системный жест Android или стрелка «←») основным экраном всегда открывается «Заявки → Новые → ● Свежие» (обращения за сегодня по Минску). Зависшие тикеты с прошлых дней вынесены в переключатель «◷ Прошлые».",
            "Вкладка «Zabbix» подключается к мониторингу z.csl.by (JSON-RPC): позволяет скрывать старые/нерешаемые проблемы, навсегда глушить шумные триггеры, подтверждать (Ack) события с комментарием и получать звуковой баннер строго при авариях уровня Disaster (5).",
            "Вкладка «Wiki» открывает базу знаний Confluence (w.csl.by) с единым логином Jira, алфавитным каталогом клиентов, копированием паролей/IP из таблиц в 1 тап и полной офлайн-базой с ежедневной синхронизацией."
        )
    ),
    GuideSection(
        title = "11. Настольная версия Help-CSL для Windows 10/11 (setup.exe)",
        bullets = listOf(
            "В каждом релизе GitHub доступен самодостаточный установщик setup.exe для Windows 10/11 (x64): не требует прав администратора и не требует установленной Java.",
            "При запуске и по кнопке «← Назад» (а также по боковой кнопке мыши Назад, Alt+← или Esc) открывается основной экран «Заявки → Новые → ● Свежие».",
            "Windows-суперспособности: 1-клик запуск AnyDesk.exe / rustdesk.exe / RDP (mstsc) / SSH, прямой запуск десктопных Telegram / Viber / WhatsApp без браузера, вызов SIP-софтфона (tel:), вставка скриншотов из буфера обмена (Win+Shift+S / Ctrl+V), тёмная и светлая темы, печать актов А4 PDF и 3-секундный перенос настроек по QR-коду HELPCSL1."
        )
    )
)

/**
 * Full searchable in-app User & Engineer Guide (`📖 Полный гайд по Help-CSL`).
 */
@Composable
fun AppFullGuideDialog(
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filteredSections = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) HELP_CSL_GUIDE_SECTIONS
        else HELP_CSL_GUIDE_SECTIONS.mapNotNull { sec ->
            if (sec.title.contains(q, ignoreCase = true)) {
                sec
            } else {
                val matchingBullets = sec.bullets.filter { it.contains(q, ignoreCase = true) }
                if (matchingBullets.isNotEmpty()) sec.copy(bullets = matchingBullets) else null
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = CslMidnight, contentColor = Color.White) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, tint = CslOrange)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Полный гайд по Help-CSL",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Справочник всех функций дежурного и выездного инженера",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text("Поиск по гайду (например: свайп, SSL, акт, QR, MIUI)…") }
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        filteredSections.forEach { sec ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = sec.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CslOrange
                                    )
                                    sec.bullets.forEach { item ->
                                        Text(
                                            text = "• $item",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}
