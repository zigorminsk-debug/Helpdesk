package com.helpdesk.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.helpdesk.jiraagent.data.HabrArticleBlock
import com.helpdesk.jiraagent.data.HabrArticleDetail
import com.helpdesk.jiraagent.data.HabrFeedCategory
import com.helpdesk.jiraagent.data.HabrNewsItem
import com.helpdesk.jiraagent.data.HabrNewsRepository
import com.helpdesk.jiraagent.data.JiraAssignableUser
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.domain.EngineerNameResolver
import com.helpdesk.jiraagent.domain.PostponeDateTimeParser
import com.helpdesk.jiraagent.domain.QuickReplyTemplates
import com.helpdesk.jiraagent.domain.SlaCalculator
import com.helpdesk.jiraagent.domain.SlaUrgency
import com.helpdesk.jiraagent.domain.SysadminEntityExtractor
import com.helpdesk.jiraagent.domain.TicketStopwatchTracker
import com.helpdesk.jiraagent.model.ClientTechPassportCodec
import com.helpdesk.jiraagent.model.HelpdeskTicket
import com.helpdesk.jiraagent.model.TicketStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.swing.JFileChooser

private enum class TicketQueueTab(val title: String) {
    NEW("Новые"),
    IN_PROGRESS("В работе"),
    CLOSED("Закрытые"),
    SERVICE("Сервисные")
}

private enum class AssigneeScope(val title: String) {
    ALL("Все"),
    MINE("Мои")
}

private data class DesktopGifSpec(
    val resourcePath: String,
    val quote: String,
    val aspectRatio: Float
)

private val DESKTOP_ADMIN_GIFS = listOf(
    DesktopGifSpec(
        resourcePath = "/admin_gifs/admin_gif_01.gif",
        quote = "«Вы пробовали выключить и снова включить?» — доброе утро, очередь чиста!",
        aspectRatio = 439f / 256f
    ),
    DesktopGifSpec(
        resourcePath = "/admin_gifs/admin_gif_07.gif",
        quote = "Доброе утро! В очереди чисто. Главное правило админа — не трогать то, что работает!",
        aspectRatio = 440f / 248f
    )
)

@Composable
fun DesktopTicketsWorkspace(
    state: DesktopUiState,
    controller: DesktopController
) {
    var queueTab by remember { mutableStateOf(TicketQueueTab.NEW) }
    var inWorkScope by remember { mutableStateOf(AssigneeScope.ALL) }
    var closedScope by remember { mutableStateOf(AssigneeScope.ALL) }
    var filterOnlyStaleNew by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSerialArchiveDialog by remember { mutableStateOf(false) }
    var showKnowledgeArchiveDialog by remember { mutableStateOf(false) }
    var showPostponedDialog by remember { mutableStateOf(false) }
    var showHabrPanel by remember { mutableStateOf(false) }

    val nowTick by produceState(initialValue = Instant.now()) {
        while (true) {
            delay(20_000L)
            value = Instant.now()
        }
    }

    val isMorningGifTime = remember(state.settings, state.dismissedGifDateIso, nowTick) {
        BelarusWorkCalendar.shouldShowMorningGif(
            settings = state.settings,
            dismissedDateIso = state.dismissedGifDateIso.takeIf { it.isNotBlank() },
            sessionStartedEpochMs = controller.sessionStartedEpochMs,
            maxSessionDisplayMinutes = 10L,
            nowInstant = nowTick
        )
    }

    val clientTickets = remember(state.tickets) { state.tickets.filter { it.isClientTicket } }
    val serviceTickets = remember(state.tickets) { state.tickets.filter { !it.isClientTicket } }

    val newCount = remember(clientTickets) { clientTickets.count { it.stage == TicketStage.NEW } }
    val staleThresholdMin = state.settings.staleTicketReminderMinutes.coerceIn(5, 120)
    val staleNewCount = remember(clientTickets, staleThresholdMin, nowTick) {
        val nowMs = nowTick.toEpochMilli()
        clientTickets.count { t ->
            t.stage == TicketStage.NEW &&
                t.createdEpochMillis > 0L &&
                (nowMs - t.createdEpochMillis) >= staleThresholdMin * 60_000L
        }
    }
    val inWorkCount = remember(clientTickets) { clientTickets.count { it.stage == TicketStage.IN_PROGRESS } }
    val closedCount = remember(clientTickets) { clientTickets.count { it.stage == TicketStage.CLOSED } }
    val serviceCount = serviceTickets.size

    val filteredList = remember(
        state.tickets,
        queueTab,
        inWorkScope,
        closedScope,
        filterOnlyStaleNew,
        staleThresholdMin,
        nowTick,
        searchQuery,
        state.connectedUserName,
        state.settings.usernameOrEmail
    ) {
        val base = when (queueTab) {
            TicketQueueTab.NEW -> {
                val list = clientTickets.filter { it.stage == TicketStage.NEW }
                if (filterOnlyStaleNew) {
                    val nowMs = nowTick.toEpochMilli()
                    list.filter { t ->
                        t.createdEpochMillis > 0L &&
                            (nowMs - t.createdEpochMillis) >= staleThresholdMin * 60_000L
                    }
                } else list
            }
            TicketQueueTab.IN_PROGRESS -> {
                val list = clientTickets.filter { it.stage == TicketStage.IN_PROGRESS }
                if (inWorkScope == AssigneeScope.MINE) {
                    list.filter { it.isAssignedToAgent(state.connectedUserName, state.settings.usernameOrEmail) }
                } else list
            }
            TicketQueueTab.CLOSED -> {
                val list = clientTickets.filter { it.stage == TicketStage.CLOSED }
                if (closedScope == AssigneeScope.MINE) {
                    list.filter { it.isAssignedToAgent(state.connectedUserName, state.settings.usernameOrEmail) }
                } else list
            }
            TicketQueueTab.SERVICE -> serviceTickets
        }

        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) base
        else base.filter { t ->
            t.key.lowercase().contains(q) ||
                t.reporterName.lowercase().contains(q) ||
                t.summary.lowercase().contains(q) ||
                t.description.lowercase().contains(q) ||
                (t.assigneeName?.lowercase()?.contains(q) == true) ||
                t.phoneNumbers.any { it.e164.contains(q) || it.formattedDisplay.contains(q) }
        }
    }

    val selectedTicket = remember(state.tickets, state.selectedTicketKey) {
        state.tickets.firstOrNull { it.key == state.selectedTicketKey }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // ================= LEFT PANE: QUEUE & EMPTY GIF + HABR =================
        Column(
            modifier = Modifier
                .width(455.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp)
        ) {
            // Search & Quick Action Toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Поиск: клиент, CSL-номер, телефон...", fontSize = 12.sp) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            TextButton(onClick = { searchQuery = "" }) { Text("✕") }
                        }
                    }
                )
                FilledTonalButton(
                    onClick = { showCreateDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text("➕ Заявка", fontSize = 12.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { showSerialArchiveDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text("🔎 S/N", fontSize = 11.5.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { showKnowledgeArchiveDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text("📚 Решения", fontSize = 11.5.sp, maxLines = 1)
                }
                if (showHabrPanel) {
                    Button(
                        onClick = { showHabrPanel = false },
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)
                    ) {
                        Text("📰 Habr", fontSize = 12.sp, maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            showHabrPanel = true
                            if (state.habrItems.isEmpty() && !state.isLoadingHabr) {
                                controller.loadHabrFeed(state.habrCategory, force = false)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 8.dp)
                    ) {
                        Text("📰 Habr", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }

            if (state.postponedTickets.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = DesktopColors.StatusAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, DesktopColors.StatusAmber.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPostponedDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "⏰ Отложенные заявки к автовозврату: ${state.postponedTickets.size}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("Открыть →", fontSize = 11.sp, color = DesktopColors.CslOrange)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 4 Primary Queue Tabs (Zero Clipping)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    TicketQueueTab.NEW to newCount,
                    TicketQueueTab.IN_PROGRESS to inWorkCount,
                    TicketQueueTab.CLOSED to closedCount,
                    TicketQueueTab.SERVICE to serviceCount
                ).forEach { (tab, count) ->
                    val selected = !showHabrPanel && queueTab == tab
                    Surface(
                        color = if (selected) DesktopColors.CslOrange else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                showHabrPanel = false
                                queueTab = tab
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = count.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Sub-tabs («Все» / «Мои») for В работе & Закрытые, or SLA filter for Новые
            if (!showHabrPanel && queueTab == TicketQueueTab.NEW && (staleNewCount > 0 || filterOnlyStaleNew)) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !filterOnlyStaleNew,
                        onClick = { filterOnlyStaleNew = false },
                        label = { Text("Все новые ($newCount)", fontSize = 11.sp, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = filterOnlyStaleNew,
                        onClick = { filterOnlyStaleNew = !filterOnlyStaleNew },
                        label = { Text("🔥 Ждут >${staleThresholdMin}м ($staleNewCount)", fontSize = 11.sp, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else if (queueTab == TicketQueueTab.IN_PROGRESS || queueTab == TicketQueueTab.CLOSED) {
                val activeScope = if (queueTab == TicketQueueTab.IN_PROGRESS) inWorkScope else closedScope
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssigneeScope.entries.forEach { scopeOpt ->
                        FilterChip(
                            selected = activeScope == scopeOpt,
                            onClick = {
                                if (queueTab == TicketQueueTab.IN_PROGRESS) inWorkScope = scopeOpt
                                else closedScope = scopeOpt
                            },
                            label = { Text("${scopeOpt.title} заявки", fontSize = 11.sp, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (showHabrPanel || filteredList.isEmpty()) {
                DesktopEmptyQueueWithHabr(
                    showEmptyQueueHeader = !showHabrPanel && filteredList.isEmpty(),
                    showMorningGif = !showHabrPanel && filteredList.isEmpty() && isMorningGifTime,
                    gifIndex = state.emptyGifIndex,
                    onCycleGif = { controller.cycleEmptyGif() },
                    onDismissGifToday = { controller.dismissMorningGifToday() },
                    onRefreshTickets = { controller.refreshTickets(silent = false) },
                    habrCategory = state.habrCategory,
                    habrItems = state.habrItems,
                    selectedHabrUrl = state.selectedHabrItem?.url,
                    isLoadingHabr = state.isLoadingHabr,
                    onSelectHabrCategory = { controller.loadHabrFeed(it, force = false) },
                    onRefreshHabr = { controller.loadHabrFeed(state.habrCategory, force = true) },
                    onSelectHabrNews = { controller.selectHabrNews(it) }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredList, key = { it.key }) { ticket ->
                        CompactDesktopTicketCard(
                            ticket = ticket,
                            isSelected = ticket.key == state.selectedTicketKey,
                            settings = state.settings,
                            onClick = { controller.selectTicket(ticket.key) }
                        )
                    }
                }
            }
        }

        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

        // ================= RIGHT PANE: TICKET INSPECTOR OR IN-APP HABR READER =================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val activeHabr = state.selectedHabrItem
            when {
                activeHabr != null -> {
                    DesktopHabrArticleReader(
                        item = activeHabr,
                        detail = state.habrArticleDetail,
                        isLoading = state.isLoadingHabrArticle,
                        errorMessage = state.habrArticleError,
                        allItems = state.habrItems,
                        onSelectAdjacent = { controller.selectHabrNews(it) },
                        onRetry = { controller.selectHabrNews(activeHabr, forceRefresh = true) },
                        onCopyLink = { WindowsExternalLaunchers.copyToClipboard(activeHabr.url) },
                        onClose = { controller.closeHabrArticle() }
                    )
                }
                selectedTicket != null -> {
                    DesktopTicketInspector(
                        ticket = selectedTicket,
                        state = state,
                        controller = controller
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Выберите заявку или новость Habr слева",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "В правом окне открываются карточки заявок (SIP-звонок, 1-клик AnyDesk/RustDesk/RDP/SSH, вставка скриншотов Win+Shift+S, PDF-акты) и полный текст новостей с Habr.com без запуска стороннего браузера.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 580.dp)
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateTicketDialog(
            defaultProject = state.filterSettings.projectKeys.split(',').firstOrNull()?.trim().orEmpty().ifBlank { "CSL" },
            onDismiss = { showCreateDialog = false },
            onCreate = { proj, type, sum, desc, self ->
                showCreateDialog = false
                controller.createManualTicket(proj, type, sum, desc, self)
            }
        )
    }

    if (showSerialArchiveDialog) {
        SerialArchiveSearchDialog(
            results = state.serialSearchResults,
            isSearching = state.isSearchingSerial,
            onSearch = { controller.searchBySerialOrText(it) },
            onOpenTicket = { key ->
                showSerialArchiveDialog = false
                controller.selectTicket(key)
            },
            onDismiss = { showSerialArchiveDialog = false }
        )
    }

    if (showPostponedDialog) {
        PostponedTicketsDialog(
            items = state.postponedTickets,
            onCancelItem = { controller.cancelPostponedTicket(it) },
            onDismiss = { showPostponedDialog = false }
        )
    }

    if (showKnowledgeArchiveDialog) {
        DesktopKnowledgeArchiveDialog(
            initialQuery = searchQuery,
            excludeTicketKey = state.selectedTicketKey,
            state = state,
            controller = controller,
            onOpenTicket = { key -> controller.selectTicket(key) },
            onDismiss = { showKnowledgeArchiveDialog = false }
        )
    }
}

/**
 * Compact Ticket Card following user rules:
 * - Client Name (`reporterName`) prominent at top;
 * - Ticket Number (`key`) moved down in a smaller font;
 * - ZERO empty blank space inside the card below ticket info.
 */
@Composable
private fun CompactDesktopTicketCard(
    ticket: HelpdeskTicket,
    isSelected: Boolean,
    settings: com.helpdesk.jiraagent.model.AppSettings,
    onClick: () -> Unit
) {
    val slaBadge = remember(ticket.key, ticket.stage, ticket.createdEpochMillis) {
        if (ticket.isClientTicket && ticket.stage == TicketStage.NEW) {
            SlaCalculator.computeWaitBadge(
                createdEpochMillis = ticket.createdEpochMillis
            )
        } else null
    }

    val borderColor = when {
        isSelected -> DesktopColors.CslOrange
        slaBadge?.urgency == SlaUrgency.OVERDUE -> DesktopColors.StatusRed.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) DesktopColors.CslOrange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(if (isSelected) 1.6.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 1. Prominent Client Name at Top + SLA / Stage Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = ticket.reporterName.ifBlank { ticket.reporterLoginOrEmail.ifBlank { "Клиент" } },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (slaBadge != null) {
                    val slaColor = when (slaBadge.urgency) {
                        SlaUrgency.FRESH -> DesktopColors.StatusGreen
                        SlaUrgency.WARNING -> DesktopColors.StatusAmber
                        SlaUrgency.OVERDUE -> DesktopColors.StatusRed
                    }
                    Surface(
                        color = slaColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = slaBadge.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = slaColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // 2. Ticket Summary
            Text(
                text = ticket.summary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // 3. Smaller Ticket Key moved down + Status + Assignee (compact footer, zero wasted space)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ticket.key,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = DesktopColors.CslOrange
                    )
                    Text(
                        text = "· ${ticket.status}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ticket.assigneeName?.takeIf { it.isNotBlank() }?.let { eng ->
                    Text(
                        text = "👤 $eng",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Empty Queue Screen strictly following user constraints:
 * - ONLY GIF #1 (Roy) and GIF #7 (Moss), smooth native Skia multi-frame animation;
 * - ONLY the quote (no extra title above it);
 * - Live `habr.com/ru` news feed directly below the GIF!
 */
@Composable
private fun DesktopEmptyQueueWithHabr(
    showEmptyQueueHeader: Boolean,
    showMorningGif: Boolean,
    gifIndex: Int,
    onCycleGif: () -> Unit,
    onDismissGifToday: () -> Unit,
    onRefreshTickets: () -> Unit,
    habrCategory: HabrFeedCategory,
    habrItems: List<HabrNewsItem>,
    selectedHabrUrl: String?,
    isLoadingHabr: Boolean,
    onSelectHabrCategory: (HabrFeedCategory) -> Unit,
    onRefreshHabr: () -> Unit,
    onSelectHabrNews: (HabrNewsItem) -> Unit
) {
    val spec = DESKTOP_ADMIN_GIFS[gifIndex.mod(DESKTOP_ADMIN_GIFS.size)]

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showMorningGif) {
            item(key = "morning_gif_banner") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCycleGif)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "☀️ Начало рабочего дня",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = DesktopColors.CslOrange
                            )
                            TextButton(
                                onClick = onDismissGifToday,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(22.dp)
                            ) {
                                Text("✕ Скрыть", fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        AnimatedResourceGifImage(
                            resourcePath = spec.resourcePath,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(165.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = spec.quote,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else if (showEmptyQueueHeader) {
            item(key = "compact_clean_queue_strip") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "✓ В выбранной очереди заявок нет",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(
                            onClick = onRefreshTickets,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("🔄 Обновить (F5)", fontSize = 11.sp, color = DesktopColors.CslOrange)
                        }
                    }
                }
            }
        }

        // Live Habr.com RSS Header + Category Chips directly below GIF
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📰 Свежее на Habr.com",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = DesktopColors.CslOrange
                    )
                    TextButton(
                        onClick = onRefreshHabr,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(if (isLoadingHabr) "Загрузка..." else "🔄 Обновить", fontSize = 11.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HabrFeedCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = habrCategory == cat,
                            onClick = { onSelectHabrCategory(cat) },
                            label = { Text(cat.title, fontSize = 10.5.sp, maxLines = 1) }
                        )
                    }
                }
            }
        }

        items(habrItems, key = { it.url }) { news ->
            val isSelected = news.url == selectedHabrUrl
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) {
                    DesktopColors.CslOrange.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) DesktopColors.CslOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectHabrNews(news) }
            ) {
                Column(modifier = Modifier.padding(9.dp)) {
                    Text(
                        text = news.title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (news.summary.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = news.summary,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (isSelected) "${news.publishedLabel} · Открыто справа →" else "${news.publishedLabel} · Читать справа →",
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = DesktopColors.CslOrange
                    )
                }
            }
        }
    }
}

/**
 * Built-in Habr.com Article Reader displayed directly in the Windows right pane
 * without launching any external browser.
 */
@Composable
private fun DesktopHabrArticleReader(
    item: HabrNewsItem,
    detail: HabrArticleDetail?,
    isLoading: Boolean,
    errorMessage: String?,
    allItems: List<HabrNewsItem>,
    onSelectAdjacent: (HabrNewsItem) -> Unit,
    onRetry: () -> Unit,
    onCopyLink: () -> Unit,
    onClose: () -> Unit
) {
    val scrollState = rememberScrollState()
    var linkCopied by remember(item.url) { mutableStateOf(false) }

    LaunchedEffect(item.url) {
        scrollState.scrollTo(0)
    }

    val currentIndex = remember(allItems, item.url) {
        allItems.indexOfFirst { it.url == item.url }
    }
    val prevItem = if (currentIndex > 0) allItems[currentIndex - 1] else null
    val nextItem = if (currentIndex >= 0 && currentIndex < allItems.lastIndex) allItems[currentIndex + 1] else null

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Header Bar inside Right Pane
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = DesktopColors.CslOrange.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "📰 Habr.com · Чтение в Help-CSL",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = DesktopColors.CslOrange,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = { prevItem?.let(onSelectAdjacent) },
                        enabled = prevItem != null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("← Предыдущая", fontSize = 11.5.sp)
                    }
                    OutlinedButton(
                        onClick = { nextItem?.let(onSelectAdjacent) },
                        enabled = nextItem != null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("Следующая →", fontSize = 11.5.sp)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onCopyLink()
                            linkCopied = true
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(if (linkCopied) "✓ Ссылка скопирована" else "📋 Копировать ссылку", fontSize = 11.5.sp)
                    }
                    OutlinedButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("🔄 Обновить", fontSize = 11.5.sp)
                    }
                    FilledTonalButton(
                        onClick = onClose,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text("✕ Закрыть", fontSize = 11.5.sp)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 28.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val displayTitle = detail?.title ?: item.title
                    val displayAuthor = detail?.author ?: item.author
                    val displayDate = detail?.publishedLabel?.ifBlank { item.publishedLabel } ?: item.publishedLabel
                    val displayHubs = (detail?.hubs?.takeIf { it.isNotEmpty() } ?: item.tags)

                    // Article Title
                    Text(
                        text = displayTitle,
                        fontSize = 21.sp,
                        lineHeight = 27.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Author / Date / Reading Time / Views Row
                    val metaParts = buildList {
                        if (!displayAuthor.isNullOrBlank()) add("👤 $displayAuthor")
                        if (displayDate.isNotBlank()) add("🕒 $displayDate")
                        detail?.readingTimeMinutes?.takeIf { it > 0 }?.let { add("⏱ ~$it мин чтения") }
                        detail?.viewsCount?.takeIf { it > 0 }?.let { add("👁 $it") }
                        detail?.score?.let { sc ->
                            val sign = if (sc > 0) "+" else ""
                            add("★ $sign$sc")
                        }
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString("   ·   "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Hubs / Tags
                    if (displayHubs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            displayHubs.take(5).forEach { hub ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = hub,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    if (isLoading && detail == null) {
                        if (item.summary.isNotBlank()) {
                            Text(
                                text = item.summary,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = DesktopColors.CslOrange
                                )
                                Text(
                                    text = "Загружаем полный текст статьи с Habr.com...",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (detail != null) {
                        detail.blocks.forEach { block ->
                            when (block) {
                                is HabrArticleBlock.Heading -> {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = block.text,
                                        fontSize = when (block.level) {
                                            1, 2 -> 18.sp
                                            3 -> 16.sp
                                            else -> 14.5.sp
                                        },
                                        lineHeight = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                is HabrArticleBlock.Paragraph -> {
                                    Text(
                                        text = block.text,
                                        fontSize = 14.sp,
                                        lineHeight = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                is HabrArticleBlock.Quote -> {
                                    Surface(
                                        color = DesktopColors.CslOrange.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                            Box(
                                                modifier = Modifier
                                                    .width(4.dp)
                                                    .fillMaxHeight()
                                                    .background(DesktopColors.CslOrange)
                                            )
                                            Text(
                                                text = block.text,
                                                fontSize = 13.5.sp,
                                                lineHeight = 20.5.sp,
                                                fontStyle = FontStyle.Italic,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                            )
                                        }
                                    }
                                }

                                is HabrArticleBlock.BulletList -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        block.items.forEachIndexed { idx, li ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = if (block.ordered) "${idx + 1}." else "•",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = DesktopColors.CslOrange
                                                )
                                                Text(
                                                    text = li,
                                                    fontSize = 14.sp,
                                                    lineHeight = 21.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }

                                is HabrArticleBlock.CodeBlock -> {
                                    var codeCopied by remember(block.code) { mutableStateOf(false) }
                                    Surface(
                                        color = DesktopColors.CslNavySurface,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.35f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = block.language?.uppercase() ?: "КОД",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = DesktopColors.CslOrange
                                                )
                                                TextButton(
                                                    onClick = {
                                                        WindowsExternalLaunchers.copyToClipboard(block.code)
                                                        codeCopied = true
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = if (codeCopied) "✓ Скопировано" else "📋 Копировать",
                                                        fontSize = 11.sp,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = block.code,
                                                fontSize = 12.5.sp,
                                                lineHeight = 18.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFFE8ECF4)
                                            )
                                        }
                                    }
                                }

                                is HabrArticleBlock.Image -> {
                                    HabrInlineImage(url = block.url, caption = block.caption)
                                }
                            }
                        }
                    } else {
                        if (item.summary.isNotBlank()) {
                            Text(
                                text = item.summary,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (!errorMessage.isNullOrBlank()) {
                            Surface(
                                color = DesktopColors.StatusRed.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, DesktopColors.StatusRed.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Не удалось загрузить полный текст: $errorMessage",
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = onRetry,
                                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                                    ) {
                                        Text("🔄 Повторить", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(end = 3.dp, top = 4.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun HabrInlineImage(
    url: String,
    caption: String?
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = HabrNewsRepository.loadImageBytes(url) ?: return@runCatching null
                ByteArrayInputStream(bytes).use { bais ->
                    ImageIO.read(bais)?.toComposeImageBitmap()
                }
            }.getOrNull()
        }
        bitmap = loaded
    }

    val bmp = bitmap
    if (bmp != null) {
        val aspect = remember(bmp) {
            val h = bmp.height.coerceAtLeast(1)
            (bmp.width.toFloat() / h.toFloat()).coerceIn(0.6f, 3.2f)
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
            ) {
                Image(
                    bitmap = bmp,
                    contentDescription = caption,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .heightIn(max = 420.dp)
                )
            }
            if (!caption.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = caption,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun AnimatedResourceGifImage(
    resourcePath: String,
    modifier: Modifier = Modifier
) {
    var frames by remember(resourcePath) { mutableStateOf<List<Pair<ImageBitmap, Long>>>(emptyList()) }
    var currentFrameIdx by remember(resourcePath) { mutableStateOf(0) }

    LaunchedEffect(resourcePath) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = DesktopController::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                    ?: return@runCatching emptyList()
                val readers = ImageIO.getImageReadersByFormatName("gif")
                if (!readers.hasNext()) return@runCatching emptyList()
                val reader = readers.next()
                ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { iis ->
                    reader.setInput(iis, false, false)
                    val count = reader.getNumImages(true).coerceAtLeast(1)
                    val firstFrame = reader.read(0)
                    val canvas = BufferedImage(firstFrame.width, firstFrame.height, BufferedImage.TYPE_INT_ARGB)
                    val list = ArrayList<Pair<ImageBitmap, Long>>(count)
                    for (i in 0 until count) {
                        val raw = if (i == 0) firstFrame else reader.read(i)
                        var offsetX = 0
                        var offsetY = 0
                        var delayMs = 90L
                        runCatching {
                            val meta = reader.getImageMetadata(i)
                            val root = meta.getAsTree(meta.nativeMetadataFormatName) as? IIOMetadataNode
                            val children = root?.childNodes
                            if (children != null) {
                                for (idx in 0 until children.length) {
                                    val node = children.item(idx) as? IIOMetadataNode ?: continue
                                    if (node.nodeName == "ImageDescriptor") {
                                        offsetX = node.getAttribute("imageLeftPosition").toIntOrNull() ?: 0
                                        offsetY = node.getAttribute("imageTopPosition").toIntOrNull() ?: 0
                                    } else if (node.nodeName == "GraphicControlExtension") {
                                        val cs = node.getAttribute("delayTime").toLongOrNull() ?: 9L
                                        delayMs = (cs * 10L).coerceAtLeast(30L)
                                    }
                                }
                            }
                        }
                        val g = canvas.createGraphics()
                        g.drawImage(raw, offsetX, offsetY, null)
                        g.dispose()
                        val snap = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
                        val sg = snap.createGraphics()
                        sg.drawImage(canvas, 0, 0, null)
                        sg.dispose()
                        list.add(snap.toComposeImageBitmap() to delayMs)
                    }
                    reader.dispose()
                    list
                }
            }.getOrDefault(emptyList())
        }
        frames = decoded
        currentFrameIdx = 0
        if (decoded.size > 1) {
            while (true) {
                val dur = decoded[currentFrameIdx].second
                delay(dur)
                currentFrameIdx = (currentFrameIdx + 1) % decoded.size
            }
        }
    }

    if (frames.isNotEmpty()) {
        Image(
            bitmap = frames[currentFrameIdx.coerceIn(frames.indices)].first,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun DesktopTicketInspector(
    ticket: HelpdeskTicket,
    state: DesktopUiState,
    controller: DesktopController
) {
    val scroll = rememberScrollState()
    val entities = remember(ticket.key, ticket.summary, ticket.description, ticket.comments.size) {
        SysadminEntityExtractor.extractFromTicket(ticket)
    }

    var commentDraft by remember(ticket.key) { mutableStateOf("") }
    var internalComment by remember(ticket.key) { mutableStateOf(false) }
    var showCompleteDialog by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var showPostponeModal by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var showWorklogDialog by remember { mutableStateOf(false) }
    var showActPdfDialog by remember { mutableStateOf(false) }
    var showPassportDialog by remember { mutableStateOf(false) }
    var showTicketKbDialog by remember { mutableStateOf(false) }

    val stopwatchEntry = state.ticketStopwatches[ticket.key]
    var stopwatchNowMs by remember(ticket.key) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticket.key, stopwatchEntry?.isRunning) {
        while (stopwatchEntry?.isRunning == true) {
            stopwatchNowMs = System.currentTimeMillis()
            delay(1_000L)
        }
        stopwatchNowMs = System.currentTimeMillis()
    }
    val stopwatchDisplay = remember(stopwatchEntry, stopwatchNowMs) {
        TicketStopwatchTracker.formatTimerDisplay(stopwatchEntry, stopwatchNowMs)
    }
    val stopwatchSuggestedMins = remember(stopwatchEntry, stopwatchNowMs) {
        TicketStopwatchTracker.roundedMinutesForWorklog(stopwatchEntry, stopwatchNowMs)
    }
    val clientPassport = remember(state.clientPassports, ticket.reporterName) {
        val k = ClientTechPassportCodec.normalizeKey(ticket.reporterName)
        state.clientPassports[k]
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header Card (Client Name prominent at top, Ticket Key smaller below)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ticket.reporterName.ifBlank { ticket.reporterLoginOrEmail.ifBlank { "Клиент" } },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { WindowsExternalLaunchers.copyToClipboard(ticket.key) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("📋 ${ticket.key}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        if (ticket.webUrl.isNotBlank()) {
                            OutlinedButton(
                                onClick = { WindowsExternalLaunchers.openBrowser(ticket.webUrl) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("🌐 Открыть в Jira ↗", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Text(
                    text = ticket.summary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Номер: ${ticket.key}", fontSize = 12.sp, color = DesktopColors.CslOrange, fontWeight = FontWeight.Bold)
                    Text("Статус: ${ticket.status}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Приоритет: ${ticket.priority}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Исполнитель: ${ticket.assigneeName ?: "Не назначен"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Primary Workflow Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { controller.performTicketAction(ticket.key, TicketWorkflowAction.TAKE_IN_WORK) },
                        enabled = !state.isPerformingAction,
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("▶ В работу", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { showCompleteDialog = true },
                        enabled = !state.isPerformingAction,
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.StatusGreen)
                    ) {
                        Text("✅ Завершить", fontWeight = FontWeight.Bold)
                    }
                    FilledTonalButton(
                        onClick = { showPostponeModal = true },
                        enabled = !state.isPerformingAction
                    ) {
                        Text("⏰ Отложить на...")
                    }
                    OutlinedButton(
                        onClick = { showRejectDialog = true },
                        enabled = !state.isPerformingAction
                    ) {
                        Text("✖ Отклонить")
                    }
                    OutlinedButton(
                        onClick = { showTransferDialog = true },
                        enabled = !state.isPerformingAction
                    ) {
                        Text("👤 Коллеге")
                    }
                }

                // Secondary Engineer Actions (В ремонт, В чат, PDF-акт, Worklog, Таймер, Wiki, Техпаспорт)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (stopwatchEntry?.isRunning == true) DesktopColors.CslOrangeContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (stopwatchEntry?.isRunning == true) DesktopColors.CslOrange else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "⏱ $stopwatchDisplay",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (stopwatchEntry?.isRunning == true) DesktopColors.CslOrange else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (stopwatchEntry?.isRunning == true) "⏸" else "▶",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { controller.toggleTicketStopwatch(ticket.key) }
                                    .padding(horizontal = 4.dp)
                            )
                            if (stopwatchEntry != null) {
                                Text(
                                    text = "✕",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { controller.resetTicketStopwatch(ticket.key) }
                                        .padding(horizontal = 3.dp)
                                )
                            }
                        }
                    }

                    OutlinedButton(onClick = { showPassportDialog = true }) {
                        Text(
                            if (clientPassport != null && !clientPassport.isEmpty) "🏢 Техпаспорт ✓" else "🏢 Техпаспорт",
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(onClick = { controller.openClientInWiki(ticket.reporterName) }) {
                        Text("📘 Wiki клиента", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { showTicketKbDialog = true }) {
                        Text("📚 База решений", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { controller.openRepairIntakeFromTicket(ticket) }) {
                        Text("🔧 В ремонт", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { controller.discussTicketInChat(ticket) }) {
                        Text("💬 В чат", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { showActPdfDialog = true }) {
                        Text("📄 Акт PDF", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { showWorklogDialog = true }) {
                        Text("⏱ Списать время", fontSize = 12.sp)
                    }
                }

                // Inline synced Client Tech Passport summary strip (if filled by team)
                if (clientPassport != null && !clientPassport.isEmpty) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DesktopColors.CslOrangeContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth().clickable { showPassportDialog = true }
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "🏢 Техпаспорт «${ticket.reporterName}» (нажмите для редактирования)",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = DesktopColors.CslOrange
                            )
                            val lines = buildList {
                                if (clientPassport.addressesAndBranches.isNotBlank()) add("📍 ${clientPassport.addressesAndBranches}")
                                if (clientPassport.gatewayAndProvider.isNotBlank()) add("🌐 ${clientPassport.gatewayAndProvider}")
                                if (clientPassport.vpnAndRemoteNotes.isNotBlank()) add("🔐 ${clientPassport.vpnAndRemoteNotes}")
                                if (clientPassport.serversAndServices.isNotBlank()) add("🖥 ${clientPassport.serversAndServices}")
                                if (clientPassport.contactsAndNotes.isNotBlank()) add("👤 ${clientPassport.contactsAndNotes}")
                            }
                            lines.forEach { ln ->
                                Text(ln, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // 2. Phones, SIP Softphone & Messengers (+375 / +7)
        if (ticket.phoneNumbers.isNotEmpty() || !ticket.telegramHandles.isNullOrEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📞 Связь с клиентом (SIP-телефония и мессенджеры)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    ticket.phoneNumbers.forEach { phone ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${phone.formattedDisplay} (${phone.e164})",
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = { WindowsExternalLaunchers.dialSipOrTel(phone.e164) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("📞 SIP звонок", fontSize = 11.5.sp) }
                                OutlinedButton(
                                    onClick = { WindowsExternalLaunchers.openTelegram(phone.e164) },
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
                                ) { Text("Telegram", fontSize = 11.5.sp) }
                                OutlinedButton(
                                    onClick = { WindowsExternalLaunchers.openViber(phone.e164) },
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
                                ) { Text("Viber", fontSize = 11.5.sp) }
                                OutlinedButton(
                                    onClick = { WindowsExternalLaunchers.openWhatsApp(phone.e164) },
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
                                ) { Text("WhatsApp", fontSize = 11.5.sp) }
                                OutlinedButton(
                                    onClick = { WindowsExternalLaunchers.copyToClipboard(phone.e164) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text("📋", fontSize = 11.5.sp) }
                            }
                        }
                    }
                    ticket.telegramHandles.orEmpty().forEach { handle ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("✈ Telegram ник: @$handle", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            OutlinedButton(
                                onClick = { WindowsExternalLaunchers.openTelegram("", handle) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("Открыть чат @$handle", fontSize = 11.5.sp) }
                        }
                    }
                }
            }
        }

        // 3. Windows Sysadmin Quick-Connect (AnyDesk, RustDesk, RDP, SSH, Network Diagnostics, MAC OUI)
        if (!entities.isEmpty) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "🛠 Инженерная панель быстрого подключения Windows",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = DesktopColors.CslOrange
                    )

                    entities.remoteIds.forEach { remote ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "🖥 ${remote.kind.label}: ${remote.formattedId}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { WindowsExternalLaunchers.launchAnyDesk(remote.normalizedId) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                                ) { Text("⚡ AnyDesk", fontSize = 11.5.sp) }
                                FilledTonalButton(
                                    onClick = { WindowsExternalLaunchers.launchRustDesk(remote.normalizedId) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("🦀 RustDesk", fontSize = 11.5.sp) }
                                OutlinedButton(
                                    onClick = { WindowsExternalLaunchers.copyToClipboard(remote.normalizedId) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text("📋 Копия", fontSize = 11.5.sp) }
                            }
                        }
                    }

                    entities.networkTargets.forEach { net ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("🌐 Хост / IP: ${net.host}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = { WindowsExternalLaunchers.launchRdp(net.host) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("🖥 RDP (mstsc)", fontSize = 11.5.sp) }
                                OutlinedButton(
                                    onClick = { WindowsExternalLaunchers.launchSshTerminal(net.host) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("⌨ SSH", fontSize = 11.5.sp) }
                                OutlinedButton(
                                    onClick = { controller.runHostDiagnostics(net.host) },
                                    enabled = !state.isRunningDiagnostics,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text("📡 Диагностика", fontSize = 11.5.sp) }
                            }
                        }
                    }

                    if (entities.macAddresses.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            entities.macAddresses.forEach { mac ->
                                AssistChip(
                                    onClick = { WindowsExternalLaunchers.copyToClipboard(mac.normalizedMac) },
                                    label = {
                                        Text(
                                            "MAC ${mac.normalizedMac} · ${mac.vendorName ?: "Неизвестный OUI"}",
                                            fontSize = 11.sp
                                        )
                                    }
                                )
                            }
                        }
                    }

                    entities.streetAddresses.forEach { addr ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("📍 ${addr.displayAddress}", fontSize = 12.sp)
                            TextButton(
                                onClick = {
                                    val encoded = java.net.URLEncoder.encode(addr.searchQuery, "UTF-8")
                                    WindowsExternalLaunchers.openBrowser("https://yandex.by/maps/?text=$encoded")
                                }
                            ) { Text("Открыть Яндекс.Карты ↗", fontSize = 11.5.sp) }
                        }
                    }

                    state.diagnosticReport?.let { rep ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "Отчёт диагностики: ${rep.targetHost} (${rep.resolvedIp ?: "DNS не разрешён"})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "ICMP/TCP доступность: ${if (rep.isHostAlive) "ДОСТУПЕН (${rep.icmpLatencyMs ?: "—"} мс)" else "НЕДОСТУПЕН"}",
                                    fontSize = 11.5.sp
                                )
                                val openPorts = rep.portStatuses.filter { it.isOpen }
                                    .joinToString(", ") { "${it.port}/${it.serviceName} (${it.latencyMs}мс)" }
                                Text("Открытые TCP-порты: ${openPorts.ifBlank { "нет открытых из стандартного списка" }}", fontSize = 11.5.sp)
                                rep.sslCertificate?.let { ssl ->
                                    Text("SSL сертификат: ${ssl.subjectCn}, истекает ${ssl.expiresFormatted} (${ssl.daysRemaining} дн.)", fontSize = 11.5.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = {
                                        val formatted = QuickReplyTemplates.formatDiagnosticReportForComment(rep)
                                        commentDraft = if (commentDraft.isBlank()) {
                                            formatted
                                        } else {
                                            "${commentDraft.trimEnd()}\n\n$formatted"
                                        }
                                        internalComment = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("📋 Вставить отчёт в комментарий (внутр. заметка)", fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Ticket Description & Attachments + Win+Shift+S Clipboard Upload
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📝 Описание обращения", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Text(
                    text = ticket.description.ifBlank { "Описание не указано." },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "📎 Вложения (${ticket.attachments?.size ?: 0})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { controller.uploadClipboardScreenshot(ticket.key) },
                            enabled = !state.isPerformingAction,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text("📸 Вставить скриншот (Win+Shift+S)", fontSize = 11.5.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val chooser = JFileChooser()
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    val f: File = chooser.selectedFile
                                    controller.uploadFileAttachment(ticket.key, f)
                                }
                            },
                            enabled = !state.isPerformingAction,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text("📁 Прикрепить файл...", fontSize = 11.5.sp)
                        }
                    }
                }

                Text(
                    "💡 Можно перетащить файлы прямо из Проводника Windows в это окно (Drag & Drop).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DesktopAttachmentThumbnailStrip(
                    attachments = ticket.attachments.orEmpty(),
                    controller = controller,
                    onOpenOriginal = { att -> controller.downloadAndOpenAttachment(att.contentUrl, att.filename) }
                )

                ticket.attachments.orEmpty().forEach { att ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .clickable { controller.downloadAndOpenAttachment(att.contentUrl, att.filename) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄 ${att.filename} (${att.sizeBytes / 1024} КБ)", fontSize = 12.sp)
                        Text("Скачать и открыть ↗", fontSize = 11.sp, color = DesktopColors.CslOrange)
                    }
                }
            }
        }

        // 5. Client's Complete Tickets History (with Scrollbar, Filter & Expand)
        if (state.clientHistoryForKey == ticket.key && (state.isLoadingClientHistory || state.clientHistoryTickets.isNotEmpty())) {
            var historyFilterQuery by remember(ticket.key) { mutableStateOf("") }
            var historyExpandedHeight by remember(ticket.key) { mutableStateOf(false) }

            val filteredHistory = remember(state.clientHistoryTickets, historyFilterQuery) {
                val q = historyFilterQuery.trim()
                if (q.isEmpty()) {
                    state.clientHistoryTickets
                } else {
                    state.clientHistoryTickets.filter { prev ->
                        prev.key.contains(q, ignoreCase = true) ||
                            prev.summary.contains(q, ignoreCase = true) ||
                            prev.status.contains(q, ignoreCase = true) ||
                            (prev.assigneeName?.contains(q, ignoreCase = true) == true)
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                            val countLabel = if (historyFilterQuery.isNotBlank()) {
                                "${filteredHistory.size} из ${state.clientHistoryTickets.size}"
                            } else {
                                "${state.clientHistoryTickets.size}"
                            }
                            Text(
                                "🗂 Прошлые заявки клиента «${ticket.reporterName}» ($countLabel)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.isLoadingClientHistory) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = DesktopColors.CslOrange
                                )
                            }
                        }

                        if (state.clientHistoryTickets.size > 5) {
                            TextButton(
                                onClick = { historyExpandedHeight = !historyExpandedHeight },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (historyExpandedHeight) "↕ Компактно" else "↕ Развернуть список",
                                    fontSize = 11.5.sp,
                                    color = DesktopColors.CslOrange
                                )
                            }
                        }
                    }

                    if (state.clientHistoryTickets.size > 5) {
                        OutlinedTextField(
                            value = historyFilterQuery,
                            onValueChange = { historyFilterQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    "🔍 Поиск по истории клиента (номер CSL, тема, статус, исполнитель)...",
                                    fontSize = 12.sp
                                )
                            },
                            trailingIcon = {
                                if (historyFilterQuery.isNotBlank()) {
                                    TextButton(
                                        onClick = { historyFilterQuery = "" },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                    ) {
                                        Text("✕", fontSize = 12.sp)
                                    }
                                }
                            }
                        )
                    }

                    if (filteredHistory.isEmpty()) {
                        Text(
                            text = if (state.isLoadingClientHistory) {
                                "Загрузка всей истории заявок клиента из Jira..."
                            } else {
                                "По запросу «$historyFilterQuery» прошлых заявок не найдено."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else if (filteredHistory.size <= 5) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            filteredHistory.forEach { prev ->
                                ClientHistoryTicketRow(
                                    prev = prev,
                                    onClick = { controller.selectTicket(prev.key) }
                                )
                            }
                        }
                    } else {
                        val historyListState = rememberLazyListState()
                        val viewportHeight = if (historyExpandedHeight) 460.dp else 235.dp
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(viewportHeight)
                        ) {
                            LazyColumn(
                                state = historyListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(filteredHistory, key = { it.key }) { prev ->
                                    ClientHistoryTicketRow(
                                        prev = prev,
                                        onClick = { controller.selectTicket(prev.key) }
                                    )
                                }
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(historyListState),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }

        // 6. Comments & Quick Reply Templates
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("💬 Комментарии (${ticket.comments.size})", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)

                ticket.comments.forEach { c ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(9.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(c.author, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = DesktopColors.CslOrange)
                                Text(c.createdIso.take(16).replace('T', ' '), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(c.body, fontSize = 12.5.sp)
                        }
                    }
                }

                // Quick Reply Templates (editable + {клиент}/{заявка}/{инженер} placeholders)
                var showTemplatesManager by remember { mutableStateOf(false) }
                val resolvedEngineerName = remember(state.connectedUserName, state.settings.usernameOrEmail, state.tickets) {
                    EngineerNameResolver.resolve(
                        rawEngineer = state.connectedUserName,
                        connectedUserDisplayName = state.connectedUserName,
                        currentUsernameOrEmail = state.settings.usernameOrEmail,
                        knownTickets = state.tickets
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "⚡ Быстрые шаблоны ответа:",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { showTemplatesManager = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("⚙ Настроить шаблоны", fontSize = 11.sp, color = DesktopColors.CslOrange)
                    }
                }

                val templatesList = state.settings.quickReplyTemplates
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    templatesList.chunked(3).forEach { rowTemplates ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowTemplates.forEach { tpl ->
                                val shortLabel = if (tpl.length > 34) tpl.take(33) + "…" else tpl
                                SuggestionChip(
                                    onClick = {
                                        val expanded = QuickReplyTemplates.expand(
                                            templateBody = tpl,
                                            clientName = ticket.reporterName,
                                            ticketKey = ticket.key,
                                            engineerName = resolvedEngineerName
                                        )
                                        commentDraft = if (commentDraft.isBlank()) {
                                            expanded
                                        } else {
                                            "${commentDraft.trimEnd()} $expanded"
                                        }
                                    },
                                    label = { Text(shortLabel, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    }
                }

                val sendCommentNow: () -> Unit = {
                    val sendText = commentDraft.trim()
                    if (sendText.isNotEmpty() && !state.isPerformingAction) {
                        commentDraft = ""
                        controller.postComment(ticket.key, sendText, internalComment)
                    }
                }

                OutlinedTextField(
                    value = commentDraft,
                    onValueChange = { commentDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown &&
                                ev.isCtrlPressed &&
                                (ev.key == Key.Enter || ev.key == Key.NumPadEnter)
                            ) {
                                sendCommentNow()
                                true
                            } else {
                                false
                            }
                        },
                    minLines = 2,
                    maxLines = 6,
                    placeholder = { Text("Введите комментарий или заметку (Ctrl+Enter — быстрая отправка)...") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = internalComment, onCheckedChange = { internalComment = it })
                        Text("🔒 Только для инженеров (внутренняя заметка JSM)", fontSize = 12.sp)
                    }
                    Button(
                        onClick = sendCommentNow,
                        enabled = commentDraft.isNotBlank() && !state.isPerformingAction,
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("Отправить (Ctrl+Enter)")
                    }
                }

                if (showTemplatesManager) {
                    DesktopQuickRepliesManagerDialog(
                        templates = templatesList,
                        onAdd = { controller.addQuickReplyTemplate(it) },
                        onRemove = { controller.removeQuickReplyTemplate(it) },
                        onResetDefaults = { controller.resetQuickReplyTemplates() },
                        onDismiss = { showTemplatesManager = false }
                    )
                }
            }
        }
    }

    // Dialogs for Workflow Actions
    if (showCompleteDialog) {
        WorkflowCommentDialog(
            title = "Завершить заявку ${ticket.key}",
            placeholder = "Комментарий о решении (необязательно)...",
            confirmText = "✅ Завершить и вернуться в очередь",
            suggestedWorklogMinutes = if (stopwatchEntry != null) stopwatchSuggestedMins else null,
            onDismiss = { showCompleteDialog = false },
            onConfirmWithWorklog = { text, logMins ->
                showCompleteDialog = false
                controller.completeTicketWithOptionalWorklog(ticket.key, text.ifBlank { null }, logMins)
            }
        )
    }

    if (showRejectDialog) {
        WorkflowCommentDialog(
            title = "Отклонить заявку ${ticket.key}",
            placeholder = "Укажите причину отклонения...",
            confirmText = "✖ Отклонить заявку",
            onDismiss = { showRejectDialog = false },
            onConfirm = { text ->
                showRejectDialog = false
                controller.performTicketAction(ticket.key, TicketWorkflowAction.REJECT, text.ifBlank { null })
            }
        )
    }

    if (showPostponeModal) {
        PostponeScheduleDialog(
            ticket = ticket,
            onDismiss = { showPostponeModal = false },
            onConfirm = { atMillis, label ->
                showPostponeModal = false
                controller.postponeTicket(ticket, atMillis, label)
            }
        )
    }

    if (showTransferDialog) {
        AssignColleagueDialog(
            ticketKey = ticket.key,
            controller = controller,
            onDismiss = { showTransferDialog = false },
            onSelect = { user ->
                showTransferDialog = false
                controller.assignToColleague(ticket.key, user)
            }
        )
    }

    if (showWorklogDialog) {
        WorklogEntryDialog(
            ticketKey = ticket.key,
            initialMinutes = if (stopwatchEntry != null) stopwatchSuggestedMins else 30,
            onDismiss = { showWorklogDialog = false },
            onSave = { mins, note ->
                showWorklogDialog = false
                controller.logWorkTime(ticket.key, mins, note)
            }
        )
    }

    if (showActPdfDialog) {
        val defaultSpent = if (stopwatchEntry != null) {
            val h = stopwatchSuggestedMins / 60
            val m = stopwatchSuggestedMins % 60
            if (h > 0) "$h ч ${m.toString().padStart(2, '0')} мин" else "$m мин"
        } else "1 ч 00 мин"
        CompletionActPdfDialog(
            ticket = ticket,
            initialTimeSpent = defaultSpent,
            onDismiss = { showActPdfDialog = false },
            onGenerate = { work, parts, spent, attach ->
                showActPdfDialog = false
                controller.generateAndAttachActPdf(ticket, work, parts, spent, attach)
            }
        )
    }

    if (showPassportDialog) {
        DesktopClientPassportDialog(
            initialPassport = controller.getClientPassport(ticket.reporterName),
            onSave = { controller.saveClientTechPassport(it) },
            onOpenInWiki = {
                showPassportDialog = false
                controller.openClientInWiki(ticket.reporterName)
            },
            onDismiss = { showPassportDialog = false }
        )
    }

    if (showTicketKbDialog) {
        DesktopKnowledgeArchiveDialog(
            initialQuery = ticket.summary,
            excludeTicketKey = ticket.key,
            state = state,
            controller = controller,
            onOpenTicket = { key -> controller.selectTicket(key) },
            onInsertSolution = { sol ->
                commentDraft = if (commentDraft.isBlank()) sol else "$commentDraft\n$sol"
            },
            onDismiss = { showTicketKbDialog = false }
        )
    }
}

@Composable
private fun WorkflowCommentDialog(
    title: String,
    placeholder: String,
    confirmText: String,
    suggestedWorklogMinutes: Int? = null,
    onDismiss: () -> Unit,
    onConfirm: ((String) -> Unit)? = null,
    onConfirmWithWorklog: ((String, Int?) -> Unit)? = null
) {
    var comment by remember { mutableStateOf("") }
    var autoLogTime by remember { mutableStateOf(suggestedWorklogMinutes != null) }
    var minutesText by remember { mutableStateOf((suggestedWorklogMinutes ?: 15).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = { Text(placeholder) }
                )
                if (suggestedWorklogMinutes != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = autoLogTime, onCheckedChange = { autoLogTime = it })
                        Text("⏱ Списать время в Worklog по таймеру:", fontSize = 12.sp)
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { minutesText = it.filter(Char::isDigit) },
                            singleLine = true,
                            modifier = Modifier.width(82.dp),
                            suffix = { Text("м", fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = comment.trim()
                    if (onConfirmWithWorklog != null) {
                        val mins = if (autoLogTime) minutesText.toIntOrNull() else null
                        onConfirmWithWorklog(clean, mins)
                    } else {
                        onConfirm?.invoke(clean)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun PostponeScheduleDialog(
    ticket: HelpdeskTicket,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit
) {
    val tomorrow = remember { java.time.LocalDate.now().plusDays(1).format(PostponeDateTimeParser.DISPLAY_DATE_FMT) }
    var dateText by remember { mutableStateOf(tomorrow) }
    var timeText by remember { mutableStateOf("09:30") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⏰ Отложить заявку ${ticket.key}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Текущая заявка будет закрыта сейчас, а в назначенное время автоматически пересоздастся со всеми данными клиента, уведомит инженера и попадёт в «Новые».",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Дата (ДД.ММ.ГГГГ)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    label = { Text("Время (ЧЧ:ММ)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = DesktopColors.StatusRed, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = PostponeDateTimeParser.parseToEpochMillis(dateText, timeText)
                    if (parsed == null || parsed.first <= System.currentTimeMillis()) {
                        error = "Укажите корректную будущую дату и время"
                    } else {
                        onConfirm(parsed.first, parsed.second)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) { Text("Закрыть и запланировать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AssignColleagueDialog(
    ticketKey: String,
    controller: DesktopController,
    onDismiss: () -> Unit,
    onSelect: (JiraAssignableUser) -> Unit
) {
    var users by remember { mutableStateOf<List<JiraAssignableUser>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(ticketKey) {
        controller.loadAssignableColleagues(ticketKey).onSuccess { users = it }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("👤 Передать заявку $ticketKey коллеге", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.width(420.dp).heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text("Поиск инженера...") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    val shown = users.filter {
                        filter.isBlank() || it.displayName.contains(filter, ignoreCase = true)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(shown) { u ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().clickable { onSelect(u) }
                            ) {
                                Text(
                                    "${u.displayName} ${u.emailAddress?.let { "($it)" }.orEmpty()}",
                                    modifier = Modifier.padding(10.dp),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun WorklogEntryDialog(
    ticketKey: String,
    initialMinutes: Int = 30,
    onDismiss: () -> Unit,
    onSave: (Int, String?) -> Unit
) {
    var minutesText by remember { mutableStateOf(initialMinutes.toString()) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⏱ Списать время в Worklog ($ticketKey)", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        FilterChip(
                            selected = minutesText == m.toString(),
                            onClick = { minutesText = m.toString() },
                            label = { Text("$m мин") }
                        )
                    }
                }
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it.filter(Char::isDigit) },
                    label = { Text("Затрачено минут") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Описание работ (необязательно)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(minutesText.toIntOrNull() ?: 30, note.ifBlank { null }) },
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) { Text("Списать в Jira") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun CompletionActPdfDialog(
    ticket: HelpdeskTicket,
    initialTimeSpent: String = "1 ч 00 мин",
    onDismiss: () -> Unit,
    onGenerate: (String, String, String, Boolean) -> Unit
) {
    var workSummary by remember { mutableStateOf("Выполнена диагностика и устранение неисправности по обращению ${ticket.key}.") }
    var partsUsed by remember { mutableStateOf("Не использовались") }
    var timeSpent by remember { mutableStateOf(initialTimeSpent) }
    var attachToJira by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📄 Сформировать Акт выполненных работ A4 PDF", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = workSummary,
                    onValueChange = { workSummary = it },
                    label = { Text("Выполненные работы") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = partsUsed,
                    onValueChange = { partsUsed = it },
                    label = { Text("Использованные материалы / комплектующие") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = timeSpent,
                    onValueChange = { timeSpent = it },
                    label = { Text("Затраченное время") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = attachToJira, onCheckedChange = { attachToJira = it })
                    Text("Автоматически прикрепить PDF-акт во вложения заявки ${ticket.key}", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onGenerate(workSummary, partsUsed, timeSpent, attachToJira) },
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) { Text("Сформировать и открыть PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun CreateTicketDialog(
    defaultProject: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, Boolean) -> Unit
) {
    var project by remember { mutableStateOf(defaultProject) }
    var issueType by remember { mutableStateOf("Service Request") }
    var summary by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var assignSelf by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("➕ Новая заявка в Jira", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = project,
                        onValueChange = { project = it.uppercase() },
                        label = { Text("Ключ проекта") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = issueType,
                        onValueChange = { issueType = it },
                        label = { Text("Тип задачи") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                }
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Тема обращения *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Подробное описание, контакты клиента") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = assignSelf, onCheckedChange = { assignSelf = it })
                    Text("Назначить исполнителем меня", fontSize = 12.5.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(project, issueType, summary, description, assignSelf) },
                enabled = summary.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) { Text("Создать в Jira") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SerialArchiveSearchDialog(
    results: List<HelpdeskTicket>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onOpenTicket: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔎 Поиск истории оборудования по S/N или инв. номеру", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.width(520.dp).heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Введите серийный номер, сервис-тег или модель...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSearch(query) },
                        enabled = query.isNotBlank() && !isSearching,
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) { Text(if (isSearching) "..." else "Найти") }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results, key = { it.key }) { t ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().clickable { onOpenTicket(t.key) }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("${t.reporterName} · ${t.key}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(t.summary, fontSize = 12.sp, maxLines = 2)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun PostponedTicketsDialog(
    items: List<com.helpdesk.jiraagent.model.PostponedTicket>,
    onCancelItem: (com.helpdesk.jiraagent.model.PostponedTicket) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⏰ Запланированные к возврату заявки (${items.size})", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.width(500.dp).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.id }) { item ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${item.reporterDisplayName.ifBlank { "Клиент" }} (исх. ${item.originalKey})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(item.summary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Возврат: ${item.scheduledDisplay}", fontSize = 11.sp, color = DesktopColors.CslOrange)
                            }
                            TextButton(onClick = { onCancelItem(item) }) {
                                Text("Отменить", color = DesktopColors.StatusRed)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

private val HISTORY_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@Composable
private fun ClientHistoryTicketRow(
    prev: HelpdeskTicket,
    onClick: () -> Unit
) {
    val dateLabel = remember(prev.createdEpochMillis) {
        if (prev.createdEpochMillis > 0L) {
            runCatching {
                Instant.ofEpochMilli(prev.createdEpochMillis)
                    .atZone(ZoneId.of("Europe/Minsk"))
                    .format(HISTORY_DATE_FMT)
            }.getOrDefault("")
        } else ""
    }

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = prev.key,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = DesktopColors.CslOrange
                )
                Text(
                    text = prev.summary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            val rightMeta = buildString {
                if (dateLabel.isNotEmpty()) {
                    append(dateLabel)
                    append(" · ")
                }
                append(prev.status)
            }
            Text(
                text = rightMeta,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DesktopQuickRepliesManagerDialog(
    templates: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit
) {
    var newTemplateDraft by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.width(600.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "⚡ Настройка быстрых шаблонов ответа",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onResetDefaults) {
                        Text("Сбросить по умолчанию", fontSize = 11.5.sp)
                    }
                }

                Text(
                    "Доступные автоподстановки (кликните для вставки в новый шаблон):",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("{клиент}", "{заявка}", "{инженер}").forEach { token ->
                        AssistChip(
                            onClick = {
                                newTemplateDraft = if (newTemplateDraft.isBlank()) {
                                    token
                                } else {
                                    "${newTemplateDraft.trimEnd()} $token"
                                }
                            },
                            label = { Text("+ $token", fontSize = 11.5.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newTemplateDraft,
                        onValueChange = { newTemplateDraft = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Например: Здравствуйте, {клиент}! Подключаюсь по {заявка}.") }
                    )
                    Button(
                        onClick = {
                            onAdd(newTemplateDraft)
                            newTemplateDraft = ""
                        },
                        enabled = newTemplateDraft.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                    ) {
                        Text("➕ Добавить")
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(templates) { tpl ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tpl,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onRemove(tpl) }) {
                                    Text("✕ Удалить", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(onClick = onDismiss) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}
