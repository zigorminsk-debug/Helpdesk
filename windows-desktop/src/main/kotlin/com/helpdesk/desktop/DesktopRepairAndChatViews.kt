package com.helpdesk.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helpdesk.jiraagent.model.AdminChatMessage
import com.helpdesk.jiraagent.model.ClientDirectoryCodec
import com.helpdesk.jiraagent.model.ClientDirectoryEntry
import com.helpdesk.jiraagent.model.RepairEquipmentCodec
import com.helpdesk.jiraagent.model.RepairEquipmentItem
import com.helpdesk.jiraagent.model.RepairStatus
import com.helpdesk.jiraagent.model.RepairSubTab
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val REPAIR_TS_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Minsk"))

// =============================================================================
//  1. WORKSHOP «В РЕМОНТ» REGISTRY + «УДАЛЁННЫЕ» AUDIT + HELPDESK CLIENTS
// =============================================================================

@Composable
fun DesktopRepairWorkspace(
    state: DesktopUiState,
    controller: DesktopController
) {
    var activeSubTab by remember { mutableStateOf(RepairSubTab.ACTIVE) }
    var searchQuery by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<RepairEquipmentItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDeleteItem by remember { mutableStateOf<RepairEquipmentItem?>(null) }

    // Open intake pre-filled from ticket when engineer clicked «🔧 Принять в ремонт»
    LaunchedEffect(state.pendingRepairPrefillTicket) {
        val prefill = state.pendingRepairPrefillTicket ?: return@LaunchedEffect
        controller.consumePendingRepairPrefill()
        editingItem = RepairEquipmentItem(
            intakeNumber = RepairEquipmentCodec.generateNextIntakeNumber(state.repairItems),
            clientName = prefill.reporterName,
            clientPhone = prefill.phoneNumbers.firstOrNull()?.e164.orEmpty(),
            linkedTicketKey = prefill.key,
            reportedDefect = "${prefill.summary}\n${prefill.description}".trim().take(500),
            assignedEngineer = controller.currentEngineerDisplayName()
        )
        showEditor = true
    }

    val counts = remember(state.repairItems) {
        RepairSubTab.entries.associateWith { tab ->
            state.repairItems.count { tab.matchesItem(it) }
        }
    }

    val visibleItems = remember(state.repairItems, activeSubTab, searchQuery) {
        state.repairItems.filter { item ->
            activeSubTab.matchesItem(item) && RepairEquipmentCodec.matchesSearch(item, searchQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        "Поиск по REM-номеру, S/N (сканер штрихкодов USB), модели, клиенту, кто удалил...",
                        fontSize = 12.5.sp
                    )
                }
            )
            OutlinedButton(
                onClick = { controller.refreshClientsDirectory(silent = false) },
                enabled = !state.isLoadingClientsDirectory
            ) {
                Text(
                    if (state.isLoadingClientsDirectory) "Загрузка..."
                    else "👥 Клиенты Helpdesk (${state.clientsDirectory.size})",
                    fontSize = 12.sp
                )
            }
            OutlinedButton(
                onClick = { controller.syncRepairRegistry(silent = false) },
                enabled = !state.isSyncingRepair
            ) {
                Text(if (state.isSyncingRepair) "Синхр..." else "🔄 Синхронизировать", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    editingItem = RepairEquipmentItem(
                        intakeNumber = RepairEquipmentCodec.generateNextIntakeNumber(state.repairItems),
                        assignedEngineer = controller.currentEngineerDisplayName()
                    )
                    showEditor = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) {
                Text("➕ Принять технику в ремонт", fontWeight = FontWeight.Bold)
            }
        }

        // Sub-tabs including «Удалённые» (red audit tab)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RepairSubTab.entries.forEach { tab ->
                val selected = activeSubTab == tab
                val isDeletedTab = tab == RepairSubTab.DELETED
                val activeBg = if (isDeletedTab) DesktopColors.DeletedRed else DesktopColors.CslOrange
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) activeBg else MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(
                        1.dp,
                        if (selected) activeBg else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { activeSubTab = tab }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconPrefix = if (isDeletedTab) "🗑 " else ""
                        Text(
                            "$iconPrefix${tab.label} (${counts[tab] ?: 0})",
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (visibleItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (activeSubTab == RepairSubTab.DELETED) {
                        "Список удалённых позиций пуст. При удалении любой карточки она сохраняется здесь с записью, кто и когда её удалил."
                    } else {
                        "В разделе «${activeSubTab.label}» сейчас нет оборудования. Нажмите «➕ Принять технику в ремонт»."
                    },
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleItems, key = { it.id }) { item ->
                    RepairItemDesktopCard(
                        item = item,
                        onEdit = {
                            editingItem = item
                            showEditor = true
                        },
                        onAdvanceStatus = { next ->
                            controller.saveRepairItem(item.copy(status = next))
                        },
                        onPrintPdf = { attach ->
                            controller.printOrOpenRepairReceiptPdf(item, attachToTicket = attach)
                        },
                        onOpenLinkedTicket = { key ->
                            controller.selectTab(DesktopMainTab.TICKETS)
                            controller.selectTicket(key)
                        },
                        onRequestSoftDelete = { pendingDeleteItem = item },
                        onRestore = { controller.restoreRepairItem(item) }
                    )
                }
            }
        }
    }

    if (showEditor && editingItem != null) {
        RepairItemEditorDialog(
            initial = editingItem!!,
            clientsDirectory = state.clientsDirectory,
            isLoadingClients = state.isLoadingClientsDirectory,
            onRefreshClients = { controller.refreshClientsDirectory(silent = false) },
            onDismiss = {
                showEditor = false
                editingItem = null
            },
            onSave = { updated ->
                showEditor = false
                editingItem = null
                controller.saveRepairItem(updated)
            }
        )
    }

    pendingDeleteItem?.let { target ->
        SoftDeleteRepairConfirmDialog(
            item = target,
            engineerName = controller.currentEngineerDisplayName(),
            onDismiss = { pendingDeleteItem = null },
            onConfirm = { reason ->
                pendingDeleteItem = null
                controller.softDeleteRepairItem(target, reason)
            }
        )
    }
}

@Composable
private fun RepairItemDesktopCard(
    item: RepairEquipmentItem,
    onEdit: () -> Unit,
    onAdvanceStatus: (RepairStatus) -> Unit,
    onPrintPdf: (Boolean) -> Unit,
    onOpenLinkedTicket: (String) -> Unit,
    onRequestSoftDelete: () -> Unit,
    onRestore: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (item.isDeleted) DesktopColors.DeletedRed.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Audit banner for soft-deleted items in «Удалённые»
            if (item.isDeleted) {
                val deletedWhen = item.deletedAtEpochMs?.let { REPAIR_TS_FMT.format(Instant.ofEpochMilli(it)) } ?: "—"
                Surface(
                    color = DesktopColors.DeletedRed.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "🗑 Удалено: ${item.deletedBy?.ifBlank { "Неизвестно" } ?: "Неизвестно"} · $deletedWhen",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = DesktopColors.DeletedRed
                            )
                            item.deleteReason?.takeIf { it.isNotBlank() }?.let { r ->
                                Text("Причина: $r", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        FilledTonalButton(onClick = onRestore) {
                            Text("↩ Восстановить", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.clientName.ifBlank { "Клиент не указан" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = item.intakeNumber,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DesktopColors.CslOrange
                    )
                    if (item.linkedTicketKey.isNotBlank()) {
                        AssistChip(
                            onClick = { onOpenLinkedTicket(item.linkedTicketKey) },
                            label = { Text("Заявка ${item.linkedTicketKey} ↗", fontSize = 11.sp) }
                        )
                    }
                }

                Surface(
                    color = DesktopColors.CslOrange.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = item.status.title,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = DesktopColors.CslOrange,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "🔧 ${item.deviceCategory}: ${item.deviceModel.ifBlank { "Модель не указана" }}  ·  S/N: ${item.serialNumber.ifBlank { "Б/Н" }}  ·  Полка: ${item.shelfLocation.ifBlank { "—" }}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Неисправность: ${item.reportedDefect.ifBlank { "—" }}",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.repairNotes.isNotBlank()) {
                Text(
                    text = "Диагностика / Работы: ${item.repairNotes}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Принял: ${item.assignedEngineer.ifBlank { "—" }} · ${REPAIR_TS_FMT.format(Instant.ofEpochMilli(item.createdAtEpochMs))}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!item.isDeleted) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.status.nextLogicalStatus()?.let { next ->
                            Button(
                                onClick = { onAdvanceStatus(next) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                            ) {
                                Text("→ ${next.shortTitle}", fontSize = 11.5.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = { onPrintPdf(false) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("📄 PDF квитанция", fontSize = 11.5.sp)
                        }
                        if (item.linkedTicketKey.isNotBlank()) {
                            OutlinedButton(
                                onClick = { onPrintPdf(true) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("📎 PDF в Jira", fontSize = 11.5.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = onEdit,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("✏ Изменить", fontSize = 11.5.sp)
                        }
                        TextButton(
                            onClick = onRequestSoftDelete,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("🗑 В удалённые", fontSize = 11.5.sp, color = DesktopColors.DeletedRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairItemEditorDialog(
    initial: RepairEquipmentItem,
    clientsDirectory: List<ClientDirectoryEntry>,
    isLoadingClients: Boolean,
    onRefreshClients: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (RepairEquipmentItem) -> Unit
) {
    var clientName by remember { mutableStateOf(initial.clientName) }
    var clientPhone by remember { mutableStateOf(initial.clientPhone) }
    var showClientSuggestions by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(initial.deviceCategory) }
    var model by remember { mutableStateOf(initial.deviceModel) }
    var serial by remember { mutableStateOf(initial.serialNumber) }
    var accessories by remember { mutableStateOf(initial.accessories) }
    var externalCond by remember { mutableStateOf(initial.externalCondition) }
    var defect by remember { mutableStateOf(initial.reportedDefect) }
    var notes by remember { mutableStateOf(initial.repairNotes) }
    var linkedTicket by remember { mutableStateOf(initial.linkedTicketKey) }
    var shelf by remember { mutableStateOf(initial.shelfLocation) }
    var status by remember { mutableStateOf(initial.status) }

    val matchingClients = remember(clientsDirectory, clientName) {
        ClientDirectoryCodec.filterSuggestions(clientsDirectory, clientName, limit = 30)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "🔧 Карточка техники ${initial.intakeNumber.ifBlank { "новая" }}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .width(620.dp)
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Client / Organization field with Helpdesk Directory Dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = clientName,
                        onValueChange = {
                            clientName = it
                            showClientSuggestions = true
                        },
                        label = { Text("Клиент / Организация * (из справочника Helpdesk или новый)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            TextButton(onClick = { showClientSuggestions = !showClientSuggestions }) {
                                Text(if (showClientSuggestions) "▲" else "▼ (${clientsDirectory.size})")
                            }
                        }
                    )
                    OutlinedButton(onClick = onRefreshClients, enabled = !isLoadingClients) {
                        Text(if (isLoadingClients) "..." else "🔄 Из Jira", fontSize = 11.5.sp)
                    }
                }

                if (showClientSuggestions && matchingClients.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 155.dp)
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
                            matchingClients.take(15).forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            clientName = entry.name
                                            if (clientPhone.isBlank() && !entry.phone.isNullOrBlank()) {
                                                clientPhone = entry.phone
                                            }
                                            showClientSuggestions = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(entry.name, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    Text(
                                        listOfNotNull(entry.phone, entry.source).joinToString(" · "),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = clientPhone,
                        onValueChange = { clientPhone = it },
                        label = { Text("Телефон клиента") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = linkedTicket,
                        onValueChange = { linkedTicket = it.uppercase() },
                        label = { Text("Связанная заявка (напр. CSL-37368)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Category chips
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RepairEquipmentCodec.DEFAULT_CATEGORIES.take(5).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Модель устройства * (напр. HP ProBook 450 G8)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f)
                    )
                    OutlinedTextField(
                        value = serial,
                        onValueChange = { serial = it },
                        label = { Text("Серийный / инв. номер (USB-сканер)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = accessories,
                        onValueChange = { accessories = it },
                        label = { Text("Комплектность (БП, кабель, сумка...)") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = shelf,
                        onValueChange = { shelf = it },
                        label = { Text("Полка / Место в мастерской") },
                        singleLine = true,
                        modifier = Modifier.weight(0.9f)
                    )
                }

                OutlinedTextField(
                    value = externalCond,
                    onValueChange = { externalCond = it },
                    label = { Text("Внешнее состояние (потёртости, сколы, пломбы)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = defect,
                    onValueChange = { defect = it },
                    label = { Text("Заявленная неисправность *") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Результаты диагностики / выполненные работы / запчасти") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                // Status selector
                Text("Текущий статус ремонта:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    RepairStatus.entries.forEach { st ->
                        FilterChip(
                            selected = status == st,
                            onClick = { status = st },
                            label = { Text(st.shortTitle, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            clientName = clientName.trim(),
                            clientPhone = clientPhone.trim(),
                            deviceCategory = category.trim(),
                            deviceModel = model.trim(),
                            serialNumber = serial.trim(),
                            accessories = accessories.trim(),
                            externalCondition = externalCond.trim(),
                            reportedDefect = defect.trim(),
                            repairNotes = notes.trim(),
                            linkedTicketKey = linkedTicket.trim(),
                            shelfLocation = shelf.trim(),
                            status = status
                        )
                    )
                },
                enabled = clientName.isNotBlank() && (model.isNotBlank() || defect.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) {
                Text("💾 Сохранить карточку")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SoftDeleteRepairConfirmDialog(
    item: RepairEquipmentItem,
    engineerName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🗑 Переместить ${item.intakeNumber} в «Удалённые»?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Позиция НЕ удаляется бесследно: она перейдёт на вкладку «Удалённые» с записью автора удаления ($engineerName) и времени, откуда её всегда можно восстановить.",
                    fontSize = 12.5.sp
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Причина удаления (необязательно, напр. дубликат)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason) },
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.DeletedRed)
            ) { Text("Переместить в «Удалённые»") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

// =============================================================================
//  2. INTERNAL ENGINEERS «ЧАТ» WORKSPACE
// =============================================================================

@Composable
fun DesktopChatWorkspace(
    state: DesktopUiState,
    controller: DesktopController
) {
    var draft by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<AdminChatMessage?>(null) }
    var showEditPinDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.pendingChatDraft) {
        if (state.pendingChatDraft.isNotBlank()) {
            draft = state.pendingChatDraft
            controller.consumePendingChatDraft()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header + Pinned Shift Note
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, DesktopColors.CslOrange.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "📌 Дежурная смена / Закреп инженеров ${if (state.chatRoomKey.isNotBlank()) "(${state.chatRoomKey})" else ""}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = DesktopColors.CslOrange
                    )
                    Text(
                        text = state.chatPinnedNote.ifBlank { "Нет закреплённого объявления смены. Нажмите «Изменить закреп», чтобы оставить информацию коллегам." },
                        fontSize = 12.5.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showEditPinDialog = true }) {
                        Text("✏ Изменить закреп", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { controller.refreshChat(silent = false) },
                        enabled = !state.isSyncingChat
                    ) {
                        Text(if (state.isSyncingChat) "..." else "🔄 Обновить", fontSize = 12.sp)
                    }
                }
            }
        }

        // Messages list
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (state.chatMessages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Сообщений пока нет. Напишите коллегам — чат синхронизируется между Android и Windows версиями Help-CSL!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.chatMessages, key = { it.id }) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (msg.isOwnMessage) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (msg.isOwnMessage) DesktopColors.CslOrangeContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.widthIn(max = 580.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            msg.authorDisplayName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = DesktopColors.CslOrange
                                        )
                                        Text(
                                            msg.createdIso.take(16).replace('T', ' '),
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (!msg.replyToAuthor.isNullOrBlank()) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                "↩ ${msg.replyToAuthor}: ${msg.replyToPreview.orEmpty()}",
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Text(msg.text, fontSize = 13.sp)

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        msg.mentionedTicketKeys.forEach { key ->
                                            AssistChip(
                                                onClick = {
                                                    controller.selectTab(DesktopMainTab.TICKETS)
                                                    controller.selectTicket(key)
                                                },
                                                label = { Text("Открыть $key ↗", fontSize = 11.sp) }
                                            )
                                        }
                                        TextButton(
                                            onClick = { replyTarget = msg },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                        ) { Text("↩ Ответить", fontSize = 11.sp) }
                                        if (msg.isOwnMessage) {
                                            TextButton(
                                                onClick = { controller.deleteChatMessage(msg.id) },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                            ) { Text("🗑 Удалить", fontSize = 11.sp, color = DesktopColors.StatusRed) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Reply banner + Composer
        replyTarget?.let { rt ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DesktopColors.CslOrangeContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "↩ Ответ для ${rt.authorDisplayName}: ${rt.text.take(80)}",
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { replyTarget = null }) { Text("✕") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && ev.isCtrlPressed && draft.isNotBlank()) {
                            val textToSend = draft
                            val rt = replyTarget
                            draft = ""
                            replyTarget = null
                            controller.sendChatMessage(textToSend, rt?.authorDisplayName, rt?.text)
                            true
                        } else false
                    },
                maxLines = 3,
                placeholder = { Text("Сообщение коллегам (можно упомянуть CSL-37368; Ctrl+Enter — отправить)...") }
            )
            Button(
                onClick = {
                    val textToSend = draft
                    val rt = replyTarget
                    draft = ""
                    replyTarget = null
                    controller.sendChatMessage(textToSend, rt?.authorDisplayName, rt?.text)
                },
                enabled = draft.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
            ) {
                Text("Отправить ➤", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showEditPinDialog) {
        var noteDraft by remember { mutableStateOf(state.chatPinnedNote) }
        AlertDialog(
            onDismissRequest = { showEditPinDialog = false },
            title = { Text("📌 Закреп дежурной смены", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    minLines = 3,
                    modifier = Modifier.width(460.dp),
                    placeholder = { Text("Кто сегодня дежурит, текущие работы на серверах, важные контакты...") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEditPinDialog = false
                        controller.savePinnedShiftNote(noteDraft)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DesktopColors.CslOrange)
                ) { Text("Сохранить закреп") }
            },
            dismissButton = { TextButton(onClick = { showEditPinDialog = false }) { Text("Отмена") } }
        )
    }
}
