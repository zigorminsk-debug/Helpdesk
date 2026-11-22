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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.helpdesk.jiraagent.data.TicketWorkflowAction
import com.helpdesk.jiraagent.domain.BelarusWorkCalendar
import com.helpdesk.jiraagent.model.TicketStage
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun main(args: Array<String>) = application {
    val startMinimized = args.any { it.equals("--minimized", ignoreCase = true) }
    val controller = remember { DesktopController() }
    val state by controller.state.collectAsState()

    var isMainWindowVisible by remember { mutableStateOf(!startMinimized) }
    var showShiftAnalytics by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showFullGuide by remember { mutableStateOf(false) }

    val mainWindowState = rememberWindowState(
        size = DpSize(1340.dp, 860.dp),
        position = WindowPosition(Alignment.Center)
    )

    val appIconBitmap = remember {
        runCatching {
            DesktopController::class.java.getResourceAsStream("/help-csl.png")!!.use { stream ->
                ImageIO.read(stream).toComposeImageBitmap()
            }
        }.getOrElse {
            BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).toComposeImageBitmap()
        }
    }
    val appIconPainter = remember(appIconBitmap) { BitmapPainter(appIconBitmap) }

    LaunchedEffect(Unit) {
        controller.bindWindowActivator {
            isMainWindowVisible = true
            if (mainWindowState.isMinimized) {
                mainWindowState.isMinimized = false
            }
        }
    }

    // Auto-hide popup alert after 18 seconds if untouched
    LaunchedEffect(state.activePopupAlert?.id) {
        val currentId = state.activePopupAlert?.id ?: return@LaunchedEffect
        delay(18_000L)
        if (controller.state.value.activePopupAlert?.id == currentId) {
            controller.dismissPopupAlert()
        }
    }

    // Windows System Tray
    val trayState = rememberTrayState()
    val newQueueCount = remember(state.tickets) {
        state.tickets.count { it.isClientTicket && it.stage == TicketStage.NEW }
    }
    Tray(
        icon = appIconPainter,
        state = trayState,
        tooltip = "Help-CSL v${controller.currentVersion} · Новых заявок: $newQueueCount",
        onAction = {
            isMainWindowVisible = true
            mainWindowState.isMinimized = false
        },
        menu = {
            Item(
                "Открыть Help-CSL (Новых: $newQueueCount)",
                onClick = {
                    isMainWindowVisible = true
                    mainWindowState.isMinimized = false
                }
            )
            Item(
                "Синхронизировать заявки сейчас",
                onClick = { controller.refreshTickets(silent = false) }
            )
            Separator()
            Item(
                "Выход из Help-CSL",
                onClick = ::exitApplication
            )
        }
    )

    // Main Application Window
    if (isMainWindowVisible) {
        Window(
            onCloseRequest = {
                if (state.minimizeToTray) {
                    isMainWindowVisible = false
                } else {
                    exitApplication()
                }
            },
            state = mainWindowState,
            title = "Help-CSL v${controller.currentVersion} — Служба технической поддержки (Windows)",
            icon = appIconPainter
        ) {
            HelpCslDesktopTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    DesktopTopNavigationHeader(
                        state = state,
                        version = controller.currentVersion,
                        appIconBitmap = appIconBitmap,
                        newQueueCount = newQueueCount,
                        onSelectTab = { controller.selectTab(it) },
                        onRefresh = { controller.refreshTickets(silent = false) },
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
                        onDismiss = { showShiftAnalytics = false }
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

    // Bottom-Right Always-On-Top Popup Toast Banner for New Tickets & Chat
    state.activePopupAlert?.let { alert ->
        val bounds = remember {
            runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            }.getOrNull()
        }
        val toastWidth = 420
        val toastHeight = 148
        val posX = ((bounds?.maxX?.toInt() ?: 1920) - toastWidth - 20).coerceAtLeast(20)
        val posY = ((bounds?.maxY?.toInt() ?: 1040) - toastHeight - 20).coerceAtLeast(20)

        Window(
            onCloseRequest = { controller.dismissPopupAlert() },
            state = rememberWindowState(
                placement = WindowPlacement.Floating,
                position = WindowPosition(posX.dp, posY.dp),
                size = DpSize(toastWidth.dp, toastHeight.dp)
            ),
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
            focusable = false,
            title = "Help-CSL Уведомление"
        ) {
            HelpCslDesktopTheme {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = DesktopColors.CslMidnight,
                    contentColor = Color.White,
                    border = BorderStroke(2.dp, DesktopColors.CslOrange),
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = alert.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = DesktopColors.CslOrange,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "✕",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable { controller.dismissPopupAlert() }
                            )
                        }

                        Text(
                            text = alert.subtitle,
                            fontSize = 12.5.sp,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (alert.ticketKey != null && !alert.isStormSummary) {
                                OutlinedButton(
                                    onClick = {
                                        val key = alert.ticketKey
                                        controller.dismissPopupAlert()
                                        controller.performTicketAction(key, TicketWorkflowAction.TAKE_IN_WORK)
                                    },
                                    border = BorderStroke(1.dp, DesktopColors.CslOrange),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("▶ В работу", color = Color.White, fontSize = 11.5.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { controller.bringWindowToFrontAndOpenTicket(alert.ticketKey) },
                                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Открыть", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
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
                    Text(
                        text = "${if (rbStatus.isAllowedNow) "🔔 РБ смена" else "🌙 Тихий РБ"} · ${state.lastSyncDisplay}",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.72f)
                    )
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
