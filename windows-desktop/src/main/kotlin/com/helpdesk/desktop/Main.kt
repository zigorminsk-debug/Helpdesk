package com.helpdesk.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.model.DarkThemePreset
import com.helpdesk.jiraagent.model.TicketStage
import java.awt.AWTEvent
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

fun main(args: Array<String>) = application {
    val startMinimized = args.any { it.equals("--minimized", ignoreCase = true) }
    val controller = remember { DesktopController() }
    val state by controller.state.collectAsState()

    var isMainWindowVisible by remember { mutableStateOf(!startMinimized) }
    var showShiftAnalytics by remember { mutableStateOf(false) }
    var showToolboxDialog by remember { mutableStateOf(false) }
    var toolboxInitialTab by remember { mutableStateOf(0) }

    LaunchedEffect(state.openWatchlistToolboxStamp) {
        if (state.openWatchlistToolboxStamp > 0L) {
            toolboxInitialTab = 3
            showToolboxDialog = true
        }
    }
    var showQrDialog by remember { mutableStateOf(false) }
    var showFullGuide by remember { mutableStateOf(false) }

    val initialWindowSize = remember {
        val bounds = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        }.getOrNull()
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            val targetW = (bounds.width * 0.90f).toInt().coerceIn(960, 1480)
            val targetH = (bounds.height * 0.90f).toInt().coerceIn(620, 900)
            DpSize(targetW.dp, targetH.dp)
        } else {
            DpSize(1280.dp, 780.dp)
        }
    }

    val mainWindowState = rememberWindowState(
        placement = WindowPlacement.Maximized,
        size = initialWindowSize,
        position = WindowPosition(Alignment.Center)
    )

    val baseAwtIcon = remember {
        runCatching {
            DesktopController::class.java.getResourceAsStream("/help-csl.png")!!.use { stream ->
                ImageIO.read(stream)
            }
        }.getOrElse {
            BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        }
    }
    val appIconBitmap = remember(baseAwtIcon) { baseAwtIcon.toComposeImageBitmap() }
    val appIconPainter = remember(appIconBitmap) { BitmapPainter(appIconBitmap) }
    var mainComposeWindow by remember { mutableStateOf<ComposeWindow?>(null) }

    // Windows System Tray
    val trayState = rememberTrayState()
    val newQueueCount = remember(state.tickets) {
        state.tickets.count { it.isClientTicket && it.stage == TicketStage.NEW }
    }
    val trayIconPainter = remember(baseAwtIcon, newQueueCount) {
        BitmapPainter(
            DesktopTrayIconRenderer.renderTrayIcon(baseAwtIcon, newQueueCount).toComposeImageBitmap()
        )
    }

    val restoreMainWindow: () -> Unit = {
        SwingUtilities.invokeLater {
            isMainWindowVisible = true
            mainWindowState.isMinimized = false
            mainComposeWindow?.apply {
                isVisible = true
                if ((extendedState and Frame.ICONIFIED) != 0) {
                    extendedState = extendedState and Frame.ICONIFIED.inv()
                }
                toFront()
                requestFocus()
            }
        }
    }

    LaunchedEffect(Unit) {
        controller.bindWindowActivator {
            restoreMainWindow()
        }
        controller.bindTrayNotifier { title, message ->
            runCatching {
                trayState.sendNotification(
                    Notification(
                        title = title,
                        message = message,
                        type = Notification.Type.Warning
                    )
                )
            }
        }
    }

    // NOTE: Clicking the standard minimize button (_) minimizes normally to the Windows Taskbar.
    // Hiding to the Windows System Tray happens ONLY when closing the window via the X button (onCloseRequest).

    Tray(
        icon = trayIconPainter,
        state = trayState,
        tooltip = "Help-CSL v${controller.currentVersion} · Новых заявок: $newQueueCount",
        onAction = { restoreMainWindow() },
        menu = {
            Item(
                "Открыть Help-CSL (Новых: $newQueueCount)",
                onClick = { restoreMainWindow() }
            )
            Item(
                "Синхронизировать заявки сейчас",
                onClick = { controller.refreshTickets(silent = false) }
            )
            Item(
                "Проверить уведомление и звук",
                onClick = { controller.triggerTestNotification() }
            )
            Separator()
            Item(
                "Выход из Help-CSL",
                onClick = ::exitApplication
            )
        }
    )

    // Main Application Window (kept mounted with visible=isMainWindowVisible for reliable background tray operation)
    Window(
        onCloseRequest = {
            if (state.minimizeToTray) {
                isMainWindowVisible = false
            } else {
                exitApplication()
            }
        },
        visible = isMainWindowVisible,
        state = mainWindowState,
        title = "Help-CSL v${controller.currentVersion} — Служба технической поддержки (Windows)",
        icon = appIconPainter,
        onPreviewKeyEvent = { ev ->
            val hasTopModalOpen = showShiftAnalytics ||
                showToolboxDialog ||
                showQrDialog ||
                showFullGuide ||
                state.pendingTransitionPrompt != null
            when {
                ev.type == KeyEventType.KeyDown && ev.key == Key.F5 -> {
                    when (state.activeTab) {
                        DesktopMainTab.ZABBIX -> controller.refreshZabbix(silent = false)
                        DesktopMainTab.WIKI -> controller.refreshWikiSpacesAndPages()
                        DesktopMainTab.REPAIR -> controller.syncRepairRegistry(silent = false)
                        DesktopMainTab.CHAT -> controller.refreshChat(silent = false)
                        else -> controller.refreshTickets(silent = false)
                    }
                    true
                }
                ev.type == KeyEventType.KeyDown && ev.isAltPressed && ev.key == Key.DirectionLeft && !hasTopModalOpen -> {
                    controller.navigateBackToFreshHome()
                    true
                }
                ev.type == KeyEventType.KeyDown && ev.key == Key.Escape && !hasTopModalOpen && !controller.isAtFreshHome(state) -> {
                    controller.navigateBackToFreshHome()
                    true
                }
                else -> false
            }
        }
    ) {
        SideEffect {
            mainComposeWindow = window
            val minDim = java.awt.Dimension(860, 560)
            if (window.minimumSize != minDim) {
                window.minimumSize = minDim
            }
        }
        DisposableEffect(window) {
            val dropTarget = DropTarget(window, object : DropTargetAdapter() {
                override fun drop(dtde: DropTargetDropEvent) {
                    try {
                        if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            dtde.acceptDrop(DnDConstants.ACTION_COPY)
                            val rawList = dtde.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                            val droppedFiles = rawList?.filterIsInstance<File>().orEmpty()
                            if (droppedFiles.isNotEmpty()) {
                                controller.uploadDroppedFiles(droppedFiles)
                            }
                            dtde.dropComplete(true)
                        } else {
                            dtde.rejectDrop()
                        }
                    } catch (_: Throwable) {
                        runCatching { dtde.dropComplete(false) }
                    }
                }
            })
            // Hardware Mouse Back button (XButton1 / button 4) returns straight to «Свежие»
            val mouseBackListener = AWTEventListener { event ->
                val me = event as? MouseEvent ?: return@AWTEventListener
                if (me.id == MouseEvent.MOUSE_PRESSED && me.button == 4) {
                    val srcWin = SwingUtilities.getWindowAncestor(me.component) ?: (me.component as? java.awt.Window)
                    if (srcWin === window && !controller.isAtFreshHome()) {
                        controller.navigateBackToFreshHome()
                    }
                }
            }
            runCatching {
                Toolkit.getDefaultToolkit().addAWTEventListener(mouseBackListener, AWTEvent.MOUSE_EVENT_MASK)
            }
            onDispose {
                runCatching { dropTarget.isActive = false }
                runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(mouseBackListener) }
            }
        }
        HelpCslDesktopTheme(
            darkTheme = state.darkTheme,
            darkPreset = state.settings.darkThemePreset
        ) {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                DesktopTopNavigationHeader(
                    state = state,
                    version = controller.currentVersion,
                    appIconBitmap = appIconBitmap,
                    newQueueCount = newQueueCount,
                    canNavigateBackToFresh = !controller.isAtFreshHome(state),
                    onNavigateBackToFresh = { controller.navigateBackToFreshHome() },
                    onSelectTab = { controller.selectTab(it) },
                    onRefresh = {
                        when (state.activeTab) {
                            DesktopMainTab.ZABBIX -> controller.refreshZabbix(silent = false)
                            DesktopMainTab.WIKI -> controller.refreshWikiSpacesAndPages()
                            DesktopMainTab.REPAIR -> controller.syncRepairRegistry(silent = false)
                            DesktopMainTab.CHAT -> controller.refreshChat(silent = false)
                            else -> controller.refreshTickets(silent = false)
                        }
                    },
                    onOpenToolbox = { showToolboxDialog = true },
                    onOpenAnalytics = { showShiftAnalytics = true },
                    onOpenQr = { showQrDialog = true },
                    onOpenGuide = { showFullGuide = true },
                    onToggleDarkTheme = { controller.toggleDarkTheme() },
                    onCycleDarkPreset = {
                        val presets = DarkThemePreset.entries
                        val nextIdx = (presets.indexOf(state.settings.darkThemePreset) + 1) % presets.size
                        controller.setDarkThemePreset(presets[nextIdx])
                    }
                )

                // Status / Error / New Update Strip
                DesktopStatusBannerStrip(
                    state = state,
                    onDismiss = { controller.dismissBanners() },
                    onInstallUpdate = { upd -> controller.downloadAndRunWindowsSetup(upd) }
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (state.activeTab) {
                        DesktopMainTab.TICKETS -> DesktopTicketsWorkspace(state, controller)
                        DesktopMainTab.ZABBIX -> DesktopZabbixWorkspace(state, controller)
                        DesktopMainTab.REPAIR -> DesktopRepairWorkspace(state, controller)
                        DesktopMainTab.WIKI -> DesktopWikiWorkspace(state, controller)
                        DesktopMainTab.CHAT -> DesktopChatWorkspace(state, controller)
                        DesktopMainTab.FILTER -> DesktopFilterWorkspace(state, controller)
                        DesktopMainTab.SETTINGS -> DesktopSettingsWorkspace(
                            state = state,
                            controller = controller,
                            onOpenQrDialog = { showQrDialog = true }
                        )
                    }
                }
            }

                // Manual Jira Transition Selector (if Jira workflow has ambiguous transitions)
                state.pendingTransitionPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { controller.cancelTransitionPrompt() },
                        title = { Text("Выберите переход Jira для ${prompt.ticketKey}", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                prompt.transitions.forEach { tr ->
                                    Button(
                                        onClick = {
                                            if (prompt.isPostpone) {
                                                val t = state.tickets.firstOrNull { it.key == prompt.ticketKey }
                                                if (t != null) {
                                                    controller.postponeTicket(
                                                        ticket = t,
                                                        scheduledAtMillis = prompt.postponeAtMillis,
                                                        scheduledDisplay = prompt.postponeDisplay,
                                                        explicitTransitionId = tr.id
                                                    )
                                                }
                                            } else if (prompt.action != null) {
                                                controller.performTicketAction(
                                                    ticketKey = prompt.ticketKey,
                                                    action = prompt.action,
                                                    commentText = prompt.commentText,
                                                    explicitTransitionId = tr.id
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                                    ) {
                                        Text("${tr.name} → ${tr.toStatusName}")
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { controller.cancelTransitionPrompt() }) { Text("Отмена") }
                        }
                    )
                }

                if (showShiftAnalytics) {
                    DesktopShiftAnalyticsDialog(
                        state = state,
                        controller = controller,
                        onDismiss = { showShiftAnalytics = false }
                    )
                }

                if (showToolboxDialog) {
                    DesktopSysadminToolboxDialog(
                        state = state,
                        controller = controller,
                        initialTabIndex = toolboxInitialTab,
                        onDismiss = {
                            showToolboxDialog = false
                            toolboxInitialTab = 0
                        }
                    )
                }

                if (showQrDialog) {
                    DesktopQrTransferDialog(
                        state = state,
                        controller = controller,
                        onDismiss = { showQrDialog = false }
                    )
                }

                if (showFullGuide) {
                    DesktopFullGuideDialog(
                        version = controller.currentVersion,
                        onDismiss = { showFullGuide = false }
                    )
                }
            }
        }
}

@Composable
private fun DesktopTopNavigationHeader(
    state: DesktopUiState,
    version: String,
    appIconBitmap: androidx.compose.ui.graphics.ImageBitmap,
    newQueueCount: Int,
    canNavigateBackToFresh: Boolean,
    onNavigateBackToFresh: () -> Unit,
    onSelectTab: (DesktopMainTab) -> Unit,
    onRefresh: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenQr: () -> Unit,
    onOpenGuide: () -> Unit,
    onToggleDarkTheme: () -> Unit,
    onCycleDarkPreset: () -> Unit
) {
    val rbStatus = remember(state.settings) { BelarusWorkCalendar.evaluate(state.settings) }
    val activeRepairsCount = remember(state.repairItems) {
        state.repairItems.count { !it.isDeleted && it.status.isActiveInWorkshop }
    }
    val activeZabbixAlertsCount = remember(
        state.zabbixSnapshot,
        state.settings.zabbixMinAlertSeverity,
        state.dismissedZabbixKeys,
        state.mutedZabbixRules
    ) {
        val minSev = state.settings.normalizedZabbixMinSeverity
        val mutedKeySet = state.mutedZabbixRules.flatMap { it.allMatchKeys() }.toSet()
        state.zabbixSnapshot?.problems?.count {
            !it.acknowledged &&
                !it.isHiddenFromActiveFeed(state.dismissedZabbixKeys, mutedKeySet) &&
                it.shouldTriggerBanner(minSev)
        } ?: 0
    }

    val connectionDotColor = when {
        state.connectedUserName != null -> DesktopColors.StatusGreen
        state.settings.isConfigured -> DesktopColors.StatusAmber
        else -> Color(0xFF94A3B8)
    }
    val connectionTitle = state.connectedUserName
        ?: if (state.settings.isConfigured) "Jira настроена" else "Не настроено"
    val alertModeLabel = when {
        !state.respectWorkScheduleOnDesktop -> "24/7"
        rbStatus.isAllowedNow -> "РБ смена"
        else -> "Тихий РБ"
    }

    @Composable
    fun NavTabsRow(modifier: Modifier = Modifier) {
        DesktopHorizontalScrollRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            showScrollbar = false,
            arrowDarkSurface = true,
            buttonHeight = 32.dp
        ) {
            if (canNavigateBackToFresh) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = DesktopColors.CslNavySurface,
                    border = BorderStroke(1.dp, DesktopColors.CslOrange),
                    contentColor = Color.White,
                    modifier = Modifier
                        .height(34.dp)
                        .clickable(onClick = onNavigateBackToFresh)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "← Назад",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = DesktopColors.CslOrange,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            DesktopMainTab.entries.forEach { tab ->
                val selected = state.activeTab == tab
                val badgeCount = when (tab) {
                    DesktopMainTab.TICKETS -> newQueueCount
                    DesktopMainTab.ZABBIX -> activeZabbixAlertsCount
                    DesktopMainTab.REPAIR -> activeRepairsCount
                    DesktopMainTab.CHAT -> state.unreadChatCount
                    else -> 0
                }
                val isUrgentBadge = (tab == DesktopMainTab.TICKETS && newQueueCount > 0) ||
                    (tab == DesktopMainTab.ZABBIX && activeZabbixAlertsCount > 0) ||
                    (tab == DesktopMainTab.CHAT && state.unreadChatCount > 0)

                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected) DesktopColors.CslOrange else DesktopColors.CslNavySurface,
                    border = BorderStroke(
                        1.dp,
                        if (selected) DesktopColors.CslOrange else DesktopColors.CslNavyBorder
                    ),
                    contentColor = Color.White,
                    modifier = Modifier
                        .height(34.dp)
                        .clickable { onSelectTab(tab) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tab.title,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (badgeCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(99.dp),
                                color = when {
                                    selected -> Color.White.copy(alpha = 0.24f)
                                    isUrgentBadge -> DesktopColors.CslOrange
                                    else -> Color.White.copy(alpha = 0.14f)
                                }
                            ) {
                                Text(
                                    text = if (tab == DesktopMainTab.CHAT) "+$badgeCount" else badgeCount.toString(),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HeaderUtilityPill(label: String, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DesktopColors.CslNavySurface,
            border = BorderStroke(1.dp, DesktopColors.CslNavyBorder),
            contentColor = Color.White,
            modifier = Modifier
                .height(32.dp)
                .clickable(onClick = onClick)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }

    @Composable
    fun UtilityPillsItems(compactSyncText: Boolean) {
        // Compact Connection Status Pill
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DesktopColors.CslNavySurface.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, DesktopColors.CslNavyBorder),
            modifier = Modifier.height(32.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(connectionDotColor)
                )
                Text(
                    text = connectionTitle,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false
                )
                val syncShort = state.lastSyncDisplay.removePrefix("обновлено в ")
                val isNotSyncedYet = syncShort.contains("не синхр", ignoreCase = true)
                val suffix = when {
                    state.isRefreshing -> "· $alertModeLabel · синхр…"
                    isNotSyncedYet && state.tickets.isNotEmpty() -> "· $alertModeLabel · кэш (${state.tickets.size})"
                    isNotSyncedYet && compactSyncText -> "· $alertModeLabel"
                    else -> "· $alertModeLabel · $syncShort"
                }
                Text(
                    text = suffix,
                    fontSize = 10.5.sp,
                    color = Color.White.copy(alpha = 0.68f),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Prominent Employee KPI & Activity Monitor button right in the top header
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DesktopColors.CslOrange.copy(alpha = 0.22f),
            border = BorderStroke(1.2.dp, DesktopColors.CslOrange),
            contentColor = Color.White,
            modifier = Modifier
                .height(32.dp)
                .clickable(onClick = onOpenAnalytics)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📈 KPI сотрудников",
                    fontSize = 11.8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        HeaderUtilityPill(if (state.darkTheme) "☀ Светлая" else "🌙 Тёмная", onToggleDarkTheme)
        if (state.darkTheme) {
            HeaderUtilityPill("🎨 ${state.settings.darkThemePreset.shortTitle}", onCycleDarkPreset)
        }
        HeaderUtilityPill("⊞ QR", onOpenQr)
        HeaderUtilityPill("📖 Гайд", onOpenGuide)
    }

    @Composable
    fun RefreshActionButton() {
        Button(
            onClick = onRefresh,
            enabled = !state.isRefreshing,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DesktopColors.CslOrange,
                disabledContainerColor = DesktopColors.CslOrange.copy(alpha = 0.5f)
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(
                text = if (state.isRefreshing) "Синхр…" else "🔄 Обновить",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isWideSingleRow = maxWidth >= 1680.dp
        val isCompactToolbar = maxWidth < 1360.dp

        Surface(
            color = DesktopColors.CslMidnight,
            contentColor = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isWideSingleRow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Image(
                            bitmap = appIconBitmap,
                            contentDescription = "Help-CSL",
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Column {
                            Text(
                                "Help-CSL",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                                lineHeight = 16.sp,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                "v$version",
                                fontSize = 10.sp,
                                lineHeight = 11.sp,
                                color = DesktopColors.CslOrange,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    NavTabsRow(modifier = Modifier.weight(1f))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HeaderUtilityPill("🛠 Инструменты", onOpenToolbox)
                        UtilityPillsItems(compactSyncText = false)
                        RefreshActionButton()
                    }
                }
            } else {
                // Clean 2-tier layout for normal/restored windows & 125%-150% DPI laptops (zero clipping!)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Image(
                                bitmap = appIconBitmap,
                                contentDescription = "Help-CSL",
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(7.dp))
                            )
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    "Help-CSL",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.5.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    "v$version",
                                    fontSize = 10.5.sp,
                                    color = DesktopColors.CslOrange,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        HeaderUtilityPill("🛠 Инструменты", onOpenToolbox)

                        DesktopHorizontalScrollRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                            showScrollbar = false,
                            arrowDarkSurface = true,
                            buttonHeight = 30.dp
                        ) {
                            UtilityPillsItems(compactSyncText = isCompactToolbar)
                        }

                        RefreshActionButton()
                    }

                    HorizontalDivider(thickness = 1.dp, color = DesktopColors.CslNavyBorder.copy(alpha = 0.7f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DesktopColors.SubHeaderBg)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavTabsRow(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopStatusBannerStrip(
    state: DesktopUiState,
    onDismiss: () -> Unit,
    onInstallUpdate: (DesktopReleaseUpdate) -> Unit
) {
    val err = state.errorBanner
    val ok = state.statusBanner
    val upd = state.updateInfo

    when {
        err != null -> {
            Surface(color = DesktopColors.StatusRed, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text("⚠ $err", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = onDismiss) { Text("✕", color = Color.White) }
                }
            }
        }
        ok != null -> {
            Surface(color = DesktopColors.StatusGreen.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text("✓ $ok", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = onDismiss) { Text("✕") }
                }
            }
        }
        upd != null -> {
            Surface(
                color = DesktopColors.CslOrangeContainer,
                contentColor = if (LocalDesktopDarkTheme.current) Color.White else DesktopColors.TextPrimary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🚀 Доступна новая версия Help-CSL для Windows: v${upd.versionName}",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (LocalDesktopDarkTheme.current) Color.White else DesktopColors.TextPrimary
                    )
                    Button(
                        onClick = { onInstallUpdate(upd) },
                        enabled = state.updateDownloadProgress == null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DesktopColors.CslOrange,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp)
                    ) {
                        Text(
                            state.updateDownloadProgress?.let { "Скачивание $it%..." } ?: "Скачать и установить setup.exe",
                            fontSize = 11.5.sp,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
