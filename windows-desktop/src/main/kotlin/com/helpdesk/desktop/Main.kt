package com.helpdesk.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.model.TicketStage
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
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
    var showQrDialog by remember { mutableStateOf(false) }
    var showFullGuide by remember { mutableStateOf(false) }

    val mainWindowState = rememberWindowState(
        size = DpSize(1340.dp, 860.dp),
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
                extendedState = Frame.NORMAL
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

    // When minimizeToTray is active, clicking the taskbar minimize button (_) also hides to system tray
    LaunchedEffect(mainWindowState.isMinimized, state.minimizeToTray) {
        if (state.minimizeToTray && mainWindowState.isMinimized) {
            isMainWindowVisible = false
            mainWindowState.isMinimized = false
        }
    }

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
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.F5) {
                when (state.activeTab) {
                    DesktopMainTab.WIKI -> controller.refreshWikiSpacesAndPages()
                    DesktopMainTab.REPAIR -> controller.syncRepairRegistry(silent = false)
                    DesktopMainTab.CHAT -> controller.refreshChat(silent = false)
                    else -> controller.refreshTickets(silent = false)
                }
                true
            } else {
                false
            }
        }
    ) {
        SideEffect {
            mainComposeWindow = window
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
            onDispose {
                runCatching { dropTarget.isActive = false }
            }
        }
        HelpCslDesktopTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                DesktopTopNavigationHeader(
                    state = state,
                    version = controller.currentVersion,
                    appIconBitmap = appIconBitmap,
                    newQueueCount = newQueueCount,
                    onSelectTab = { controller.selectTab(it) },
                    onRefresh = {
                        when (state.activeTab) {
                            DesktopMainTab.WIKI -> controller.refreshWikiSpacesAndPages()
                            DesktopMainTab.REPAIR -> controller.syncRepairRegistry(silent = false)
                            DesktopMainTab.CHAT -> controller.refreshChat(silent = false)
                            else -> controller.refreshTickets(silent = false)
                        }
                    },
                    onOpenToolbox = { showToolboxDialog = true },
                    onOpenAnalytics = { showShiftAnalytics = true },
                    onOpenQr = { showQrDialog = true },
                    onOpenGuide = { showFullGuide = true }
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
                        onDismiss = { showToolboxDialog = false }
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
    onSelectTab: (DesktopMainTab) -> Unit,
    onRefresh: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenQr: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val rbStatus = remember(state.settings) { BelarusWorkCalendar.evaluate(state.settings) }
    val activeRepairsCount = remember(state.repairItems) {
        state.repairItems.count { !it.isDeleted && it.status.isActiveInWorkshop }
    }

    Surface(
        color = DesktopColors.CslMidnight,
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Brand + Navigation Tabs
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = "Help-CSL",
                        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Column {
                        Text("Help-CSL", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White)
                        Text("Windows v$version", fontSize = 10.sp, color = DesktopColors.CslOrange)
                    }
                }

                Spacer(Modifier.width(6.dp))

                DesktopMainTab.entries.forEach { tab ->
                    val selected = state.activeTab == tab
                    val badgeText = when (tab) {
                        DesktopMainTab.TICKETS -> if (newQueueCount > 0) " ($newQueueCount)" else ""
                        DesktopMainTab.REPAIR -> if (activeRepairsCount > 0) " ($activeRepairsCount)" else ""
                        DesktopMainTab.CHAT -> if (state.unreadChatCount > 0) " (+${state.unreadChatCount})" else ""
                        else -> ""
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) DesktopColors.CslOrange else DesktopColors.CslNavySurface,
                        contentColor = Color.White,
                        modifier = Modifier.clickable { onSelectTab(tab) }
                    ) {
                        Text(
                            text = "${tab.title}$badgeText",
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            // Right Status & Utility Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = state.connectedUserName?.let { "🟢 $it" }
                            ?: if (state.settings.isConfigured) "🟡 Jira настроена" else "⚪ Не настроено",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    val alertModeLabel = when {
                        !state.respectWorkScheduleOnDesktop -> "🔔 Уведомления 24/7"
                        rbStatus.isAllowedNow -> "🔔 РБ смена"
                        else -> "🌙 Тихий РБ"
                    }
                    Text(
                        text = "$alertModeLabel · ${state.lastSyncDisplay}",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }

                TextButton(onClick = onOpenToolbox) {
                    Text("🛠 Инструменты", color = Color.White, fontSize = 12.sp)
                }
                TextButton(onClick = onOpenAnalytics) {
                    Text("📊 Смена", color = Color.White, fontSize = 12.sp)
                }
                TextButton(onClick = onOpenQr) {
                    Text("📲 QR", color = Color.White, fontSize = 12.sp)
                }
                TextButton(onClick = onOpenGuide) {
                    Text("📖 Гайд", color = Color.White, fontSize = 12.sp)
                }
                Button(
                    onClick = onRefresh,
                    enabled = !state.isRefreshing,
                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(if (state.isRefreshing) "..." else "🔄 Обновить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    Text("⚠ $err", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
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
                    Text("✓ $ok", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    TextButton(onClick = onDismiss) { Text("✕") }
                }
            }
        }
        upd != null -> {
            Surface(color = DesktopColors.CslOrangeContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🚀 Доступна новая версия Help-CSL для Windows: v${upd.versionName}",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = { onInstallUpdate(upd) },
                        enabled = state.updateDownloadProgress == null,
                        colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 3.dp)
                    ) {
                        Text(
                            state.updateDownloadProgress?.let { "Скачивание $it%..." } ?: "Скачать и установить setup.exe",
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
        }
    }
}
