package com.helpdesk.jiraagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.helpdesk.jiraagent.domain.ShiftAnalyticsCalculator
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.ui.theme.CslMidnight
import com.helpdesk.jiraagent.ui.theme.CslOrange
import com.helpdesk.jiraagent.ui.theme.CslSuccessGreen

/**
 * Shift Statistics Dashboard (`📊 Статистика смены`).
 */
@Composable
fun ShiftAnalyticsDialog(
    tickets: List<HelpdeskTicket>,
    connectedUserDisplayName: String?,
    usernameOrEmail: String,
    onSelectClientFilter: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val report = remember(tickets, connectedUserDisplayName, usernameOrEmail) {
        ShiftAnalyticsCalculator.compute(tickets, connectedUserDisplayName, usernameOrEmail)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BarChart, contentDescription = null, tint = CslOrange)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Статистика смены Help-CSL", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiMiniBox("За сегодня", "+${report.createdToday}", CslOrange, Modifier.weight(1f))
                    KpiMiniBox("Закрыто", "${report.closedToday}", CslSuccessGreen, Modifier.weight(1f))
                    KpiMiniBox("Мной", "${report.closedByMeToday}", CslSuccessGreen, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiMiniBox("В очереди", "${report.activeNewCount}", CslOrange, Modifier.weight(1f))
                    KpiMiniBox("SLA <15м", "${report.slaGreenPercent}%", CslSuccessGreen, Modifier.weight(1f))
                    KpiMiniBox("Ср. ожид.", "${report.avgQueueWaitMinutes} м", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                }

                Text(
                    text = "За 7 дней: поступило ${report.createdLast7Days} · решено ${report.closedLast7Days}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (report.engineerStats.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "Нагрузка по инженерам:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CslOrange
                    )
                    report.engineerStats.forEach { eng ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(eng.engineerName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                text = "В работе: ${eng.inProgressCount} · Закр.: ${eng.closedCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (report.topClients.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "Топ клиентов по обращениям (нажмите для поиска):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = CslOrange
                    )
                    report.topClients.forEach { cl ->
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
            "Быстрые чипы комплектности (Блок питания, Кабель 220В, Сумка, Картридж и др.), указание полки хранения, звонок и мессенджеры (Telegram / Viber / WhatsApp) для уведомления клиента о готовности.",
            "Кнопка «PDF» формирует брендированную A4 «Квитанцию о приёме в ремонт» или «Акт выдачи из ремонта» с подписью клиента пальцем на экране — документ можно отправить на печать/в мессенджер или прикрепить к заявке Jira.",
            "Журнал работает мгновенно офлайн и автоматически синхронизируется между всеми инженерами смены через служебный реестр Jira."
        )
    ),
    GuideSection(
        title = "7. Сетевой комбайн, Caller ID и учёт времени Worklog",
        bullets = listOf(
            "Автораспознавание 9–10-значных ID AnyDesk / RustDesk (копирование в 1 тап), IPv4, доменов и MAC-адресов с офлайн-определением вендора (MikroTik, Cisco, Hikvision, HP и др.).",
            "Встроенная диагностика узла проверяет ICMP Ping, TCP-порты (80, 443, 3389 RDP, 22 SSH, 8291 WinBox, 445 SMB), HTTP-ответ, остаток дней SSL-сертификата и DNS-записи (A, MX, SPF/TXT, NS).",
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
