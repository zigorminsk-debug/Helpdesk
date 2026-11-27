package com.helpdesk.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.EmployeeActivityStatus
import com.helpdesk.jiraagent.domain.EmployeeKpiItem
import com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator
import com.helpdesk.jiraagent.domain.ShiftReportPeriod
import com.helpdesk.jiraagent.domain.TicketFilterEngine
import com.helpdesk.jiraagent.model.AlertSoundPreset
import com.helpdesk.jiraagent.model.AppSettings
import com.helpdesk.jiraagent.model.AuthType
import com.helpdesk.jiraagent.model.DarkThemePreset
import com.helpdesk.jiraagent.model.JiraServerType
import com.helpdesk.jiraagent.model.PhoneRegion
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.model.TicketTimingFormatter
import com.helpdesk.jiraagent.model.TtsReadoutMode
import java.io.File
import javax.swing.JFileChooser

// =============================================================================
//  1. FILTER SETTINGS WORKSPACE
// =============================================================================

@Composable
fun DesktopFilterWorkspace(
    state: DesktopUiState,
    controller: DesktopController
) {
    var draft by remember(state.filterSettings) { mutableStateOf(state.filterSettings) }
    val activeJqlPreview = remember(draft) { TicketFilterEngine.buildActiveJql(draft) }
    val filterScroll = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(filterScroll)
            .padding(start = 20.dp, end = 26.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "🎯 Фильтрация клиентских заявок JSM и отсев автоматики (zabbix)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Клиентские обращения портала Jira Service Management (Service Request, Проблемы с ПК/Ноутбуком, Сейлзмодерн, ЖКХ и др.) попадают в рабочие очереди с уведомлениями, а алерты мониторинга (zabbix, prometheus, боты) направляются во вкладку «Сервисные» без звуковых баннеров.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = draft.projectKeys,
                    onValueChange = { draft = draft.copy(projectKeys = it) },
                    label = { Text("Ключи проектов через запятую (пусто = все доступные, напр. CSL, HD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.excludedReporters,
                    onValueChange = { draft = draft.copy(excludedReporters = it) },
                    label = { Text("Сервисные отправители (уходят в «Сервисные» без звука)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.excludedLabelsAndKeywords,
                    onValueChange = { draft = draft.copy(excludedLabelsAndKeywords = it) },
                    label = { Text("Сервисные метки и ключевые слова") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.excludedIssueTypes,
                    onValueChange = { draft = draft.copy(excludedIssueTypes = it) },
                    label = { Text("Исключённые системные типы задач (по умолчанию только Sub-task / Подзадача)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.customJqlCondition,
                    onValueChange = { draft = draft.copy(customJqlCondition = it) },
                    label = { Text("Дополнительное условие JQL (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.clientOnlyFilterEnabled,
                            onCheckedChange = { draft = draft.copy(clientOnlyFilterEnabled = it) }
                        )
                        Text("Отделять сервисные/бот-заявки от клиентских", fontSize = 12.5.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.excludeSubtasks,
                            onCheckedChange = { draft = draft.copy(excludeSubtasks = it) }
                        )
                        Text("Скрывать технические подзадачи", fontSize = 12.5.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.ignoreOwnTickets,
                            onCheckedChange = { draft = draft.copy(ignoreOwnTickets = it) }
                        )
                        Text("Не считать мои собственные заявки клиентскими", fontSize = 12.5.sp)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Итоговый JQL активных очередей:", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        Text(activeJqlPreview, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { controller.saveFilterSettings(draft) },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("💾 Сохранить правила фильтра", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { draft = com.helpdesk.jiraagent.model.FilterSettings() }) {
                        Text("Сбросить по умолчанию")
                    }
                }
            }
        }
    }
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(filterScroll),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp, top = 4.dp, bottom = 4.dp)
    )
    }
}

// =============================================================================
//  2. SETTINGS WORKSPACE (JIRA, RB CALENDAR, SOUND, TRAY, AUTO-UPDATE)
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopSettingsWorkspace(
    state: DesktopUiState,
    controller: DesktopController,
    onOpenQrDialog: () -> Unit
) {
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var showSecret by remember { mutableStateOf(false) }
    val scheduleStatus = remember(draft) { BelarusWorkCalendar.evaluate(draft) }
    val settingsScroll = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(settingsScroll)
            .padding(start = 20.dp, end = 26.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card 1: Jira Connection
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🌐 Подключение к Jira Server / Data Center и Jira Cloud", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    OutlinedButton(onClick = onOpenQrDialog) {
                        Text("📲 Перенос настроек по QR-коду (Android ↔ Windows)", fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = draft.serverUrl,
                    onValueChange = { draft = draft.copy(serverUrl = it) },
                    label = { Text("Адрес сервера Jira (напр. https://jira.company.by или https://company.atlassian.net)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.confluenceUrl,
                    onValueChange = { draft = draft.copy(confluenceUrl = it) },
                    label = { Text("Адрес Confluence Wiki (по умолчанию https://w.csl.by — вход с тем же логином и паролем Jira)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = draft.confluenceToken,
                    onValueChange = { draft = draft.copy(confluenceToken = it) },
                    label = { Text("Персональный токен Confluence (PAT — необязательно, если на w.csl.by включена 2FA)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                Text(
                    text = "Мониторинг Zabbix (z.csl.by) — звук и баннер только при попадании в Disaster",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    color = DesktopColors.CslOrange
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = draft.zabbixUrl,
                        onValueChange = { draft = draft.copy(zabbixUrl = it) },
                        label = { Text("URL сервера Zabbix") },
                        placeholder = { Text("http://z.csl.by") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f)
                    )
                    OutlinedTextField(
                        value = draft.zabbixLogin,
                        onValueChange = { draft = draft.copy(zabbixLogin = it) },
                        label = { Text("Логин Zabbix") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.zabbixPassword,
                        onValueChange = { draft = draft.copy(zabbixPassword = it) },
                        label = { Text("Пароль Zabbix") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JiraServerType.entries.forEach { st ->
                        FilterChip(
                            selected = draft.serverType == st,
                            onClick = { draft = draft.copy(serverType = st) },
                            label = { Text(st.title, fontSize = 12.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthType.entries.forEach { at ->
                        FilterChip(
                            selected = draft.authType == at,
                            onClick = { draft = draft.copy(authType = at) },
                            label = { Text(at.title, fontSize = 12.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.usernameOrEmail,
                        onValueChange = { draft = draft.copy(usernameOrEmail = it) },
                        label = { Text(draft.authType.loginLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.passwordOrToken,
                        onValueChange = { draft = draft.copy(passwordOrToken = it) },
                        label = { Text(draft.authType.secretLabel) },
                        singleLine = true,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showSecret = !showSecret }) {
                                Text(if (showSecret) "Скрыть" else "Показать", fontSize = 11.sp)
                            }
                        },
                        modifier = Modifier.weight(1.2f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = draft.trustSelfSignedSsl,
                        onCheckedChange = { draft = draft.copy(trustSelfSignedSsl = it) }
                    )
                    Text("Доверять корпоративному / самоподписанному SSL-сертификату", fontSize = 12.5.sp)
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { controller.saveSettings(draft) },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("💾 Сохранить и синхронизировать", fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { controller.testConnection(draft) },
                        enabled = !state.isPerformingAction
                    ) {
                        Text("🔌 Проверить подключение")
                    }
                    OutlinedButton(
                        onClick = { controller.cleanupSystemRoomsInJira(silentIfZero = false) },
                        enabled = draft.isConfigured && !state.isCleaningSystemRooms
                    ) {
                        Text(
                            if (state.isCleaningSystemRooms) "⏳ Очистка [HELPCSL_*_ROOM]…"
                            else "🧹 Убрать дубли [HELPCSL_*_ROOM] из Jira"
                        )
                    }
                }
            }
        }

        // Card 2: RB Working Calendar, Sound & Windows System Tray
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🔔 Расписание РБ (09:00–17:30), темы оформления и системный трей Windows", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.darkTheme,
                            onCheckedChange = {
                                controller.setDarkTheme(it)
                                draft = draft.copy(
                                    themeMode = if (it) com.helpdesk.jiraagent.model.AppThemeMode.DARK else com.helpdesk.jiraagent.model.AppThemeMode.LIGHT
                                )
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("🌙 Тёмная тема оформления интерфейса", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }

                // 6 Dark Theme Visual Presets Selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "🎨 Стиль тёмной темы (выберите один из 6 вариантов палитры):",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DarkThemePreset.entries.forEach { preset ->
                            val isSelected = state.settings.darkThemePreset == preset
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(preset.previewCardHex),
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(preset.previewAccentHex) else Color(0xFF475569)
                                ),
                                modifier = Modifier
                                    .widthIn(min = 195.dp)
                                    .clickable {
                                        controller.setDarkThemePreset(preset)
                                        draft = draft.copy(
                                            themeMode = com.helpdesk.jiraagent.model.AppThemeMode.DARK,
                                            darkThemePreset = preset
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(Color(preset.previewBgHex)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(preset.previewAccentHex))
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(
                                            text = if (isSelected) "✓ ${preset.title}" else preset.title,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            color = Color(0xFFF8FAFC),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = preset.subtitle,
                                            fontSize = 10.5.sp,
                                            color = Color(0xFFCBD5E1),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = draft.pollingEnabled,
                            onCheckedChange = { draft = draft.copy(pollingEnabled = it) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Фоновый мониторинг заявок", fontSize = 13.sp)
                    }
                    OutlinedTextField(
                        value = draft.pollIntervalSeconds.toString(),
                        onValueChange = { v ->
                            v.filter(Char::isDigit).toIntOrNull()?.let { sec ->
                                draft = draft.copy(pollIntervalSeconds = sec)
                            }
                        },
                        label = { Text("Интервал днём (сек)") },
                        singleLine = true,
                        modifier = Modifier.width(180.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.minimizeToTray,
                            onCheckedChange = { controller.setMinimizeToTray(it) }
                        )
                        Text("Сворачивать в системный трей Windows при закрытии окна (✕)", fontSize = 12.5.sp)
                    }
                }

                val currentOffMin = draft.normalizedOffHoursPollMinutes
                var customOffMinText by remember(currentOffMin) {
                    mutableStateOf(if (currentOffMin > 0) currentOffMin.toString() else "")
                }
                val desktopOffOptions = remember {
                    listOf(
                        15 to "15 мин",
                        30 to "30 мин",
                        60 to "1 час",
                        120 to "2 часа",
                        180 to "3 часа",
                        240 to "4 часа",
                        360 to "6 часов",
                        AppSettings.OFF_HOURS_POLL_DISABLED to "Пауза до смены",
                        AppSettings.OFF_HOURS_POLL_SAME_AS_WORK to "Как днём"
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "🌙 Синхронизация в нерабочее время (вне ${draft.workStartTime}–${draft.workEndTime} и в выходные РБ): ${AppSettings.formatOffHoursIntervalLabel(currentOffMin).lowercase()}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = DesktopColors.CslOrange
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        desktopOffOptions.forEach { (mins, label) ->
                            FilterChip(
                                selected = currentOffMin == mins,
                                onClick = {
                                    val updated = draft.copy(offHoursPollIntervalMinutes = mins)
                                    draft = updated
                                    controller.saveSettings(updated)
                                },
                                label = { Text(label, fontSize = 11.5.sp, maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customOffMinText,
                            onValueChange = { raw ->
                                customOffMinText = raw.filter(Char::isDigit).take(4)
                            },
                            label = { Text("Свой интервал в нерабочее время (мин, 5–720)") },
                            singleLine = true,
                            modifier = Modifier.width(320.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                customOffMinText.toIntOrNull()?.coerceIn(5, 720)?.let { parsed ->
                                    val updated = draft.copy(offHoursPollIntervalMinutes = parsed)
                                    draft = updated
                                    controller.saveSettings(updated)
                                }
                            }
                        ) {
                            Text("Применить")
                        }
                    }
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(
                        checked = state.respectWorkScheduleOnDesktop,
                        onCheckedChange = { enabled ->
                            controller.setRespectWorkScheduleOnDesktop(enabled)
                            draft = draft.copy(workScheduleEnabled = enabled)
                        }
                    )
                    Column {
                        Text(
                            "Ограничивать уведомления на этом ПК рабочими днями и часами РБ",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        )
                        Text(
                            if (!state.respectWorkScheduleOnDesktop) {
                                "Сейчас: 🔔 Круглосуточно 24/7 (рекомендуется для ПК) — всплывающие баннеры и звук работают в любое время и в выходные, пока программа свёрнута в системный трей."
                            } else {
                                "${scheduleStatus.statusTitle} — ${scheduleStatus.detailMessage}"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.workStartTime,
                        onValueChange = { draft = draft.copy(workStartTime = it) },
                        label = { Text("Начало смены (Минск)") },
                        singleLine = true,
                        modifier = Modifier.width(180.dp)
                    )
                    OutlinedTextField(
                        value = draft.workEndTime,
                        onValueChange = { draft = draft.copy(workEndTime = it) },
                        label = { Text("Окончание смены (Минск)") },
                        singleLine = true,
                        modifier = Modifier.width(180.dp)
                    )
                    OutlinedTextField(
                        value = draft.extraWorkingDates,
                        onValueChange = { draft = draft.copy(extraWorkingDates = it) },
                        label = { Text("Доп. рабочие даты (ДД.ММ.ГГГГ)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.extraHolidayDates,
                        onValueChange = { draft = draft.copy(extraHolidayDates = it) },
                        label = { Text("Доп. выходные (ДД.ММ.ГГГГ)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = draft.staleTicketReminderEnabled,
                        onCheckedChange = { draft = draft.copy(staleTicketReminderEnabled = it) }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "⏰ Повторное SLA-напоминание о непринятых заявках в «Новые»",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            "Если клиентская заявка остаётся без исполнителя дольше заданного времени, Help-CSL покажет напоминание со звуком.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = draft.staleTicketReminderMinutes.toString(),
                        onValueChange = { v ->
                            v.filter(Char::isDigit).toIntOrNull()?.let { mins ->
                                draft = draft.copy(staleTicketReminderMinutes = mins.coerceIn(5, 120))
                            }
                        },
                        enabled = draft.staleTicketReminderEnabled,
                        label = { Text("Порог SLA (мин)") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp)
                    )
                }

                HorizontalDivider()

                Text("Мелодия звукового оповещения Windows:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        AlertSoundPreset.CSL_CHIME,
                        AlertSoundPreset.DISPATCH_PULSE,
                        AlertSoundPreset.HARMONIC_CHORD,
                        AlertSoundPreset.URGENT_ALARM,
                        AlertSoundPreset.CUSTOM_URI
                    ).forEach { preset ->
                        FilterChip(
                            selected = draft.alertSoundPreset == preset,
                            onClick = {
                                draft = draft.copy(alertSoundPreset = preset)
                                DesktopSoundPlayer.playAlert(preset, draft.customAlertSoundUri)
                            },
                            label = { Text(preset.title.take(24), fontSize = 11.5.sp) }
                        )
                    }
                    OutlinedButton(onClick = { DesktopSoundPlayer.playAlert(draft.alertSoundPreset, draft.customAlertSoundUri) }) {
                        Text("🔊 Тест звука", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { controller.triggerTestNotification() },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("🔔 Тест баннера + звука", fontSize = 12.sp)
                    }
                }

                if (draft.alertSoundPreset == AlertSoundPreset.CUSTOM_URI) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft.customAlertSoundUri,
                            onValueChange = { draft = draft.copy(customAlertSoundUri = it) },
                            label = { Text("Путь к собственному WAV-файлу") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            val chooser = JFileChooser()
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                draft = draft.copy(
                                    customAlertSoundUri = chooser.selectedFile.absolutePath,
                                    customAlertSoundTitle = chooser.selectedFile.name
                                )
                            }
                        }) { Text("Выбрать WAV...") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.stormProtectionEnabled,
                            onCheckedChange = { draft = draft.copy(stormProtectionEnabled = it) }
                        )
                        Text("Анти-шторм (группировать пачку ≥4 заявок в одно сводное окно)", fontSize = 12.5.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.ttsReadoutMode == TtsReadoutMode.ALWAYS,
                            onCheckedChange = {
                                draft = draft.copy(ttsReadoutMode = if (it) TtsReadoutMode.ALWAYS else TtsReadoutMode.OFF)
                            }
                        )
                        Text("Озвучивать новые заявки голосом Windows TTS", fontSize = 12.5.sp)
                    }
                }

                Text("Регион нормализации телефонов клиентов:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneRegion.entries.forEach { reg ->
                        FilterChip(
                            selected = draft.phoneRegion == reg,
                            onClick = { draft = draft.copy(phoneRegion = reg) },
                            label = { Text(reg.title, fontSize = 11.5.sp) }
                        )
                    }
                }

                Button(
                    onClick = { controller.saveSettings(draft) },
                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                ) {
                    Text("💾 Сохранить все настройки", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Card 3: GitHub Self-Update (`setup.exe`)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "🚀 Обновление Help-CSL для Windows (текущая версия v${controller.currentVersion})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = state.updateInfo?.let { "Доступен новый установщик setup.exe: версия v${it.versionName}" }
                            ?: "Проверка обновлений выполняется напрямую через GitHub Releases без лимитов API 403.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.checkUpdates(manual = true) },
                        enabled = !state.isCheckingUpdate
                    ) {
                        Text(if (state.isCheckingUpdate) "Проверка..." else "🔄 Проверить обновления")
                    }
                    state.updateInfo?.let { upd ->
                        Button(
                            onClick = { controller.downloadAndRunWindowsSetup(upd) },
                            enabled = state.updateDownloadProgress == null,
                            colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                        ) {
                            val label = state.updateDownloadProgress?.let { "Скачивание $it%..." }
                                ?: "⬇ Скачать и запустить setup.exe v${upd.versionName}"
                            Text(label, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(settingsScroll),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp, top = 4.dp, bottom = 4.dp)
    )
    }
}

// =============================================================================
//  3. SHIFT ANALYTICS, QR ONBOARDING & FULL PROGRAM GUIDE DIALOGS
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopShiftAnalyticsDialog(
    state: DesktopUiState,
    controller: DesktopController,
    onDismiss: () -> Unit
) {
    var period by remember { mutableStateOf(ShiftReportPeriod.TODAY) }
    var employeeSearch by remember { mutableStateOf("") }
    var expandedEmployee by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (state.settings.isConfigured && state.kpiSampleTickets.isEmpty()) {
            controller.refreshEmployeeKpi(daysBack = 30)
        }
    }

    val combinedTickets = remember(state.tickets, state.kpiSampleTickets) {
        val byKey = LinkedHashMap<String, com.helpdesk.jiraagent.model.HelpdeskTicket>()
        state.tickets.forEach { byKey[it.key.uppercase()] = it }
        state.kpiSampleTickets.forEach { sample ->
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

    val summary = remember(
        combinedTickets,
        state.connectedUserName,
        state.settings.usernameOrEmail,
        state.ticketStopwatches,
        period
    ) {
        ShiftAnalyticsCalculator.computeForPeriod(
            tickets = combinedTickets,
            period = period,
            connectedUserDisplayName = state.connectedUserName,
            usernameOrEmail = state.settings.usernameOrEmail,
            stopwatches = state.ticketStopwatches
        )
    }

    val visibleEmployees = remember(summary.employeeKpis, employeeSearch) {
        val q = employeeSearch.trim().lowercase()
        if (q.isEmpty()) summary.employeeKpis
        else summary.employeeKpis.filter { it.engineerName.lowercase().contains(q) }
    }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(min = 860.dp, max = 1080.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "📈 KPI по сотрудникам и мониторинг активности смены",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp
                    )
                    Text(
                        "Период: ${summary.periodRangeDisplay} · Выборка Jira: ${combinedTickets.count { it.isClientTicket }} заявок",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { controller.refreshEmployeeKpi(daysBack = 30) },
                    enabled = state.settings.isConfigured && !state.isLoadingKpi
                ) {
                    Text(
                        if (state.isLoadingKpi) "⏳ Загрузка из Jira…" else "🔄 Обновить KPI из Jira",
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 10.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.isLoadingKpi) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = DesktopColors.CslOrange
                        )
                    }

                    // Period selector + Employee search + Export actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ShiftReportPeriod.entries.forEach { p ->
                                val selected = period == p
                                FilterChip(
                                    selected = selected,
                                    onClick = { period = p },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DesktopColors.CslOrange,
                                        selectedLabelColor = Color.White
                                    ),
                                    label = {
                                        Text(
                                            p.title,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { controller.exportShiftAnalyticsPdf(summary) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DesktopColors.CslOrange,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("📄 Отчёт PDF", fontSize = 12.sp, color = Color.White, maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = {
                                    val chooser = JFileChooser()
                                    chooser.selectedFile = File("HelpCSL_EmployeeKPI_${period.name}.csv")
                                    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                        controller.exportShiftAnalyticsCsv(summary, chooser.selectedFile)
                                    }
                                }
                            ) {
                                Text("📊 Экспорт KPI в Excel (.csv)", fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    // 6 Summary KPI Cards
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KpiBox("Поступило (${period.title})", "+${summary.createdInPeriod}", Modifier.weight(1f))
                        KpiBox("Закрыто всего", summary.closedInPeriod.toString(), Modifier.weight(1f))
                        KpiBox("Закрыто мной", summary.closedByMeInPeriod.toString(), Modifier.weight(1f))
                        KpiBox("Новые / В работе", "${summary.activeNewCount} / ${summary.activeInProgressCount}", Modifier.weight(1f))
                        KpiBox(
                            "Ср. время решения",
                            TicketTimingFormatter.formatDurationMinutes(summary.avgTeamResolutionMinutes),
                            Modifier.weight(1.1f)
                        )
                        KpiBox("SLA < 15 мин", "${summary.slaGreenPercent}%", Modifier.weight(0.9f))
                    }

                    HorizontalDivider()

                    // Employee Activity & KPI Section Header + Filter
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "👨‍💻 Мониторинг активности и KPI по сотрудникам (${visibleEmployees.size}):",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.5.sp,
                            color = DesktopColors.TextPrimary
                        )
                        OutlinedTextField(
                            value = employeeSearch,
                            onValueChange = { employeeSearch = it },
                            placeholder = { Text("Поиск по сотруднику…", fontSize = 12.sp, maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier.width(260.dp)
                        )
                    }

                    if (visibleEmployees.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DesktopColors.SubtleBg,
                            border = BorderStroke(1.dp, DesktopColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "За выбранный период (${period.title}) назначенных или закрытых сотрудниками заявок не найдено.",
                                fontSize = 13.sp,
                                color = DesktopColors.TextSecondary,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        visibleEmployees.forEachIndexed { idx, emp ->
                            val isExpanded = expandedEmployee == emp.engineerName
                            DesktopEmployeeKpiCard(
                                rank = idx + 1,
                                item = emp,
                                isExpanded = isExpanded,
                                onToggleExpand = {
                                    expandedEmployee = if (isExpanded) null else emp.engineerName
                                },
                                onOpenTicket = { ticketKey ->
                                    onDismiss()
                                    controller.selectTab(DesktopMainTab.TICKETS)
                                    controller.selectTicket(ticketKey)
                                }
                            )
                        }
                    }

                    if (summary.topClients.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            "🏢 Топ клиентов по числу обращений (${period.title}):",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            summary.topClients.take(8).forEach { cl ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = DesktopColors.SubtleBg,
                                    border = BorderStroke(1.dp, DesktopColors.BorderSubtle)
                                ) {
                                    Text(
                                        text = "${cl.clientName}: ${cl.totalCount} (откр. ${cl.openCount})",
                                        fontSize = 11.5.sp,
                                        color = DesktopColors.TextPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesktopColors.CslOrange,
                    contentColor = Color.White
                )
            ) { Text("Закрыть", color = Color.White) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopEmployeeKpiCard(
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
        EmployeeActivityStatus.ACTIVE_NOW -> DesktopColors.StatusGreen
        EmployeeActivityStatus.ACTIVE_RECENT -> DesktopColors.CslOrange
        EmployeeActivityStatus.IDLE -> DesktopColors.TextMuted
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (item.isCurrentUser) DesktopColors.CslOrangeContainer.copy(alpha = 0.25f) else DesktopColors.CardBg,
        border = BorderStroke(
            width = if (item.isCurrentUser) 1.5.dp else 1.dp,
            color = if (item.isCurrentUser) DesktopColors.CslOrange else DesktopColors.BorderSubtle
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(rankLabel, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = item.engineerName,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = DesktopColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.isCurrentUser) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DesktopColors.CslOrange
                        ) {
                            Text(
                                "Вы",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = item.activityStatus.badgeLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (item.lastActivityEpochMs > 0L) {
                        Text(
                            text = "Посл. активность: ${TicketTimingFormatter.formatCompactMinsk(item.lastActivityEpochMs)}",
                            fontSize = 11.sp,
                            color = DesktopColors.TextMuted,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Text(
                        text = if (isExpanded) "▲ Скрыть заявки" else "▼ Заявки (${item.tickets.size})",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = DesktopColors.CslOrange,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // KPI Metric Pills Row
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EmployeeMetricPill(
                    label = "✅ Закрыто: ${item.closedCount} (${item.shareOfTeamClosedPercent}%)",
                    accent = DesktopColors.StatusGreen
                )
                EmployeeMetricPill(
                    label = "🔧 В работе: ${item.inProgressCount}",
                    accent = DesktopColors.StatusBlue
                )
                if (item.assignedNewCount > 0) {
                    EmployeeMetricPill(
                        label = "📥 В очереди: ${item.assignedNewCount}",
                        accent = DesktopColors.CslOrange
                    )
                }
                EmployeeMetricPill(
                    label = "⏱ Ср. решение: ${TicketTimingFormatter.formatDurationMinutes(item.avgResolutionMinutes)}",
                    accent = DesktopColors.TextSecondary
                )
                if (item.medianResolutionMinutes != null) {
                    EmployeeMetricPill(
                        label = "Медиана: ${TicketTimingFormatter.formatDurationMinutes(item.medianResolutionMinutes)}",
                        accent = DesktopColors.TextSecondary
                    )
                }
                if (item.closedCount > 0) {
                    EmployeeMetricPill(
                        label = "⚡ Быстрее 1 ч: ${item.fastCloseUnder1hPercent}%",
                        accent = if (item.fastCloseUnder1hPercent >= 50) DesktopColors.StatusGreen else DesktopColors.StatusAmber
                    )
                }
                if (item.stopwatchTrackedMinutes > 0) {
                    EmployeeMetricPill(
                        label = "⏱ Секундомер: ${TicketTimingFormatter.formatDurationMinutes(item.stopwatchTrackedMinutes.toLong())}",
                        accent = DesktopColors.CslOrange
                    )
                }
            }

            // Visual contribution bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(DesktopColors.BorderSubtle)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((item.shareOfTeamClosedPercent / 100f).coerceIn(0.03f, 1f))
                        .fillMaxHeight()
                        .background(DesktopColors.StatusGreen)
                )
            }

            // Expandable per-employee ticket list with exact Arrival & Closure times
            if (isExpanded && item.tickets.isNotEmpty()) {
                HorizontalDivider(color = DesktopColors.BorderSubtle)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    item.tickets.take(30).forEach { t ->
                        val closedAt = t.effectiveClosedEpochMillis
                        val timingStr = if (t.stage == TicketStage.CLOSED && closedAt > 0L) {
                            val dur = t.resolutionDurationMinutes?.let { " · ⏱ ${TicketTimingFormatter.formatDurationMinutes(it)}" }.orEmpty()
                            "📥 ${TicketTimingFormatter.formatFullMinsk(t.createdEpochMillis)}  →  ✅ ${TicketTimingFormatter.formatFullMinsk(closedAt)}$dur"
                        } else {
                            "📥 Поступила: ${TicketTimingFormatter.formatFullMinsk(t.createdEpochMillis)}  ·  [${t.status}]"
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DesktopColors.SubtleBg,
                            border = BorderStroke(1.dp, DesktopColors.BorderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenTicket(t.key) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = t.key,
                                            fontSize = 11.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = DesktopColors.CslOrange,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = t.reporterName,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DesktopColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "— ${t.summary}",
                                            fontSize = 12.sp,
                                            color = DesktopColors.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = timingStr,
                                        fontSize = 11.sp,
                                        color = if (t.stage == TicketStage.CLOSED) DesktopColors.StatusGreen else DesktopColors.TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "Открыть →",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DesktopColors.CslOrange,
                                    maxLines = 1,
                                    softWrap = false
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
private fun EmployeeMetricPill(label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = accent.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = DesktopColors.TextPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun KpiBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = DesktopColors.CslOrange)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DesktopQrTransferDialog(
    state: DesktopUiState,
    controller: DesktopController,
    onDismiss: () -> Unit
) {
    var includeCredentials by remember { mutableStateOf(true) }
    var pinCode by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }

    val encodedPayload = remember(state.settings, state.filterSettings, includeCredentials, pinCode) {
        DesktopQrTransferCodec.encode(
            appSettings = state.settings,
            filterSettings = state.filterSettings,
            includeCredentials = includeCredentials,
            pinCode = pinCode
        )
    }
    val qrBitmap = remember(encodedPayload) {
        DesktopQrTransferCodec.renderQrImageBitmap(encodedPayload, sizePx = 280)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(min = 640.dp, max = 780.dp),
        title = { Text("📲 QR-перенос настроек Help-CSL (Windows ↔ Android)", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Наведите камеру телефона в приложении Help-CSL (Настройки → Сканировать QR) на экран монитора, чтобы за 3 секунды перенести адрес сервера, фильтр и токен.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                qrBitmap?.let { bmp ->
                    Image(bitmap = bmp, contentDescription = "QR Help-CSL", modifier = Modifier.size(240.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeCredentials, onCheckedChange = { includeCredentials = it })
                        Text("Включить логин и пароль/токен", fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = pinCode,
                        onValueChange = { pinCode = it },
                        label = { Text("PIN-код шифрования (необяз.)") },
                        singleLine = true,
                        modifier = Modifier.width(210.dp)
                    )
                    OutlinedButton(onClick = { WindowsExternalLaunchers.copyToClipboard(encodedPayload) }) {
                        Text("📋 Скопировать код")
                    }
                }

                HorizontalDivider()

                Text("Или вставьте текстовый код HELPCSL1:... со смартфона или от коллеги:", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text("HELPCSL1:...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            controller.importQrPayload(importText, pinCode)
                            onDismiss()
                        },
                        enabled = importText.startsWith("HELPCSL1:"),
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) { Text("Импортировать") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
fun DesktopFullGuideDialog(
    version: String,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val sections = remember(version) {
        listOf(
            "1. Назначение и архитектура (Windows setup.exe + Android APK)" to
                "Help-CSL v$version — единое корпоративное рабочее место системного администратора и сервисного инженера в стиле csl.by. Версия для Windows устанавливается самодостаточным инсталлятором setup.exe (не требует прав администратора и не требует установки Java), работает в системном трее Windows и на 100% синхронизируется с мобильной Android-версией через ваш сервер Jira Server / Data Center или Jira Cloud.",
            "2. Основной экран «Свежие», очереди и сплит-панель инспектора" to
                "При запуске программы и при нажатии кнопки «← Назад» (в верхней панели, в карточке тикета, боковой кнопкой мыши Назад, Alt+← или Esc) всегда открывается основной экран: «Заявки → Новые → ● Свежие» (заявки за сегодня). Тикеты с прошлых дней доступны в 1 клик в фильтре «◷ Прошлые». Слева расположены 4 вкладки единой высоты: «Новые», «В работе», «Закрытые» и «Сервисные». Справа открывается инспектор заявки: «▶ В работу», «✅ Завершить», «⏰ Отложить на...», «✖ Отклонить», «👤 Коллеге», «🏢 Техпаспорт» и секундомер работ.",
            "3. Суперспособность Windows: быстрые подключения, мессенджеры и Win+Shift+S" to
                "Инспектор автоматически распознаёт в тексте и комментариях заявки:\n• ID AnyDesk / RustDesk — кнопка 1-клик запускает установленный AnyDesk.exe / rustdesk.exe;\n• IP-адреса и домены — кнопки запуска RDP (mstsc.exe /v:host), SSH-терминала и диагностики портов/SSL и системного разрешения DNS (MX/TXT/NS не запрашиваются);\n• Телефоны +375 и +7 — вызов SIP-софтфона (tel:) и прямой запуск настольных клиентов Telegram, Viber и WhatsApp в обход браузера;\n• Кнопка «📸 Вставить скриншот (Win+Shift+S)» или Ctrl+V в комментарии отправляет снимок экрана напрямую из буфера обмена в Jira.",
            "4. Вкладки «Zabbix» (z.csl.by) и «Wiki» (w.csl.by)" to
                "• Zabbix: подключение к серверу мониторинга z.csl.by (JSON-RPC), подтверждение событий (Ack), скрытие текущих и вечное глушение нерешаемых триггеров; звуковой сигнал и срочный баннер срабатывают строго на аварии уровня Disaster (5).\n• Wiki: встроенный клиент Confluence (w.csl.by) с единым логином Jira, алфавитным справочником клиентов, быстрым копированием реквизитов из таблиц и полной офлайн-базой знаний с ежедневной синхронизацией.",
            "5. Вкладка «В ремонт», справочник клиентов Helpdesk и аудит «Удалённые»" to
                "Предназначена для учёта техники в мастерской (REM-1001+). Поле «Клиент / Организация» автоматически подгружает реальных клиентов и организации из Helpdesk и само пополняется при вводе новых заказчиков. Поддерживается ввод серийных номеров ручным USB-сканером штрихкодов, печать QR-этикеток 58×40 мм и актов/квитанций A4 PDF с кириллицей. При удалении позиция переходит во вкладку «🗑 Удалённые» с записью ФИО инженера, времени и кнопкой «↩ Восстановить».",
            "6. Внутренний «Чат» инженеров, «Инструменты», пустая очередь и Habr.com" to
                "Вкладка «Чат» поддерживает закреп дежурной смены, скриншоты Ctrl+V, @упоминания и ссылки CSL-XXXXX. В «🛠 Инструменты» доступны Wake-on-LAN, калькулятор подсетей, генератор паролей, мониторинг узлов и вкладка «⌨ Команды»: Windows CMD, Linux / Bash и PowerShell, фильтры по платформе и теме, включая серверы и домен AD, описания и предупреждения. Раскройте карточку и скопируйте команду либо инструкцию для коллеги; приложение ничего не запускает. «🔌 Порты» — офлайн-справочник, а не сканер. «🏢 Домен AD» — проверки по роли члена домена, контроллера и админ-ПК с RSAT; восстановление доверия члена домена отдельно, с условиями и подтверждением копирования, без запуска в приложении. «📋 Сценарии» — сеть, домен, принтер, диск и службы с отметками и копируемым итогом для Jira; команды не запускаются и отчёт не отправляется автоматически. Черновик временный, не вводите секреты. «🌐 DNS» — ручные незашифрованные запросы к одному или двум указанным IPv4 DNS-серверам после подтверждения; не отправляйте внутренние имена публичным серверам без разрешения. Когда свежих заявок нет, отображается лента Habr.com.",
            "7. График РБ, сворачивание в панель задач / трей и автообновление setup.exe" to
                "Кнопка свернуть (_) сворачивает окно в панель задач Windows, а закрытие (✕) прячет программу в системный трей со счётчиком новых заявок. Встроенный календарь Республики Беларусь учитывает праздники РБ (включая Радуницу) и переносы Совмина РБ, а также настраиваемый интервал опроса вне смены. Проверка обновлений скачивает и запускает свежий setup.exe напрямую с GitHub Releases."
        )
    }

    val filtered = remember(sections, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) sections
        else sections.filter { (t, b) -> t.lowercase().contains(q) || b.lowercase().contains(q) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(min = 720.dp, max = 920.dp),
        title = { Text("📖 Полное руководство по программе Help-CSL v$version", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Поиск по руководству (AnyDesk, В ремонт, Удалённые, Win+Shift+S, РБ)...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SelectionContainer(
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        filtered.forEach { (title, body) ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = DesktopColors.CslOrange)
                                    Text(body, fontSize = 12.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) { Text("Понятно") }
        }
    )
}
