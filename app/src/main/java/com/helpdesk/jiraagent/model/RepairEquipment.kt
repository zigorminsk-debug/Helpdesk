package com.helpdesk.jiraagent.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.Locale
import java.util.UUID

enum class RepairStatus(
    val title: String,
    val shortTitle: String
) {
    RECEIVED("Принято в ремонт", "Принято"),
    DIAGNOSTICS("На диагностике", "Диагностика"),
    WAITING_PARTS("Ждёт запчасти / согласования", "Ждёт ЗЧ"),
    IN_REPAIR("В ремонте", "В ремонте"),
    READY("Готово к выдаче", "Готово"),
    ISSUED("Выдано клиенту", "Выдано");

    val isActiveInWorkshop: Boolean
        get() = this != ISSUED

    fun nextLogicalStatus(): RepairStatus? = when (this) {
        RECEIVED -> DIAGNOSTICS
        DIAGNOSTICS -> IN_REPAIR
        WAITING_PARTS -> IN_REPAIR
        IN_REPAIR -> READY
        READY -> ISSUED
        ISSUED -> null
    }
}

enum class RepairSubTab(val label: String) {
    ACTIVE("В работе"),
    WAITING("Ждёт ЗЧ"),
    READY("Готово"),
    ISSUED("Выдано"),
    DELETED("Удалённые");

    fun matchesItem(item: RepairEquipmentItem): Boolean {
        if (this == DELETED) return item.isDeleted
        if (item.isDeleted) return false
        return when (this) {
            ACTIVE -> item.status == RepairStatus.RECEIVED ||
                item.status == RepairStatus.DIAGNOSTICS ||
                item.status == RepairStatus.IN_REPAIR
            WAITING -> item.status == RepairStatus.WAITING_PARTS
            READY -> item.status == RepairStatus.READY
            ISSUED -> item.status == RepairStatus.ISSUED
            DELETED -> false
        }
    }
}

data class RepairEquipmentItem(
    val id: String = UUID.randomUUID().toString(),
    val intakeNumber: String = "",
    val deviceCategory: String = "Ноутбук",
    val deviceModel: String = "",
    val serialNumber: String = "",
    val accessories: String = "",
    val externalCondition: String = "",
    val reportedDefect: String = "",
    val repairNotes: String = "",
    val clientName: String = "",
    val clientPhone: String = "",
    val linkedTicketKey: String = "",
    val assignedEngineer: String = "",
    val shelfLocation: String = "",
    val status: RepairStatus = RepairStatus.RECEIVED,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val issuedAtEpochMs: Long? = null,
    val isDeleted: Boolean = false,
    val deletedBy: String? = null,
    val deletedAtEpochMs: Long? = null,
    val deleteReason: String? = null,
    val pendingRemoteSync: Boolean = true
)

object RepairEquipmentCodec {

    const val REPAIR_REGISTRY_MARKER = "HELPCSL_REPAIR_REGISTRY_ROOM"
    const val REPAIR_REGISTRY_LABEL = "helpcsl_repair_registry"
    const val REPAIR_REGISTRY_SUMMARY =
        "[HELPCSL_REPAIR_REGISTRY_ROOM] Журнал техники в ремонте"
    const val REPAIR_REGISTRY_DESCRIPTION =
        "Служебный реестр техники, принятой в ремонт инженерами Help-CSL. Не удаляйте эту задачу."
    const val ENTRY_PREFIX = "[HELPCSL_REPAIR_ENTRY_V1]"

    val DEFAULT_CATEGORIES = listOf(
        "Ноутбук",
        "ПК / Системный блок",
        "Принтер / МФУ",
        "Сервер / СХД",
        "Сетевое (MikroTik/Cisco)",
        "ИБП (UPS)",
        "Монитор",
        "Другое"
    )

    val DEFAULT_ACCESSORIES = listOf(
        "Блок питания / ЗУ",
        "Кабель 220В",
        "Сумка / Чехол",
        "Мышь / Донгл",
        "Картридж / Лоток",
        "Коробка",
        "Без комплекта"
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun generateNextIntakeNumber(existing: List<RepairEquipmentItem>): String {
        val maxNum = existing.mapNotNull { item ->
            item.intakeNumber
                .removePrefix("REM-")
                .trim()
                .toIntOrNull()
        }.maxOrNull() ?: 1000
        return "REM-${maxNum + 1}"
    }

    fun isRepairRegistryStorageIssue(
        summary: String,
        labels: List<String>
    ): Boolean {
        if (summary.contains(REPAIR_REGISTRY_MARKER, ignoreCase = true)) {
            return true
        }
        return labels.any { it.equals(REPAIR_REGISTRY_LABEL, ignoreCase = true) }
    }

    fun encodeEntryComment(item: RepairEquipmentItem): String {
        val clean = item.copy(pendingRemoteSync = false)
        return "$ENTRY_PREFIX\n${gson.toJson(clean)}"
    }

    fun decodeEntryComment(rawBody: String): RepairEquipmentItem? {
        val idx = rawBody.indexOf(ENTRY_PREFIX)
        if (idx < 0) return null
        val after = rawBody.substring(idx + ENTRY_PREFIX.length).trim()
        val braceStart = after.indexOf('{')
        val braceEnd = after.lastIndexOf('}')
        if (braceStart < 0 || braceEnd <= braceStart) return null
        val jsonSlice = after.substring(braceStart, braceEnd + 1)
        return runCatching {
            val parsed = gson.fromJson(jsonSlice, RepairEquipmentItem::class.java)
            if (parsed?.id.isNullOrBlank()) null
            else parsed.copy(
                status = parsed.status ?: RepairStatus.RECEIVED,
                pendingRemoteSync = false
            )
        }.getOrNull()
    }

    /**
     * Merges local items with remote append-only Jira events using last-write-wins (`updatedAtEpochMs`).
     * Items marked `isDeleted = true` are hidden from the returned active/archive list.
     */
    fun mergeItems(
        localItems: List<RepairEquipmentItem>,
        remoteEvents: List<RepairEquipmentItem>
    ): List<RepairEquipmentItem> {
        val byId = LinkedHashMap<String, RepairEquipmentItem>()

        for (remote in remoteEvents) {
            if (remote.id.isBlank()) continue
            val prev = byId[remote.id]
            if (prev == null || remote.updatedAtEpochMs >= prev.updatedAtEpochMs) {
                byId[remote.id] = remote.copy(pendingRemoteSync = false)
            }
        }

        for (local in localItems) {
            if (local.id.isBlank()) continue
            val remote = byId[local.id]
            if (remote == null) {
                byId[local.id] = local
            } else if (local.updatedAtEpochMs > remote.updatedAtEpochMs) {
                byId[local.id] = local.copy(pendingRemoteSync = true)
            } else if (local.updatedAtEpochMs == remote.updatedAtEpochMs) {
                byId[local.id] = remote.copy(pendingRemoteSync = false)
            }
        }

        return byId.values
            .sortedWith(
                compareByDescending<RepairEquipmentItem> { it.updatedAtEpochMs }
                    .thenByDescending { it.createdAtEpochMs }
            )
    }

    fun matchesSearch(item: RepairEquipmentItem, rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        return listOf(
            item.intakeNumber,
            item.deviceCategory,
            item.deviceModel,
            item.serialNumber,
            item.clientName,
            item.clientPhone,
            item.linkedTicketKey,
            item.reportedDefect,
            item.repairNotes,
            item.accessories,
            item.assignedEngineer,
            item.shelfLocation,
            item.deletedBy.orEmpty(),
            item.deleteReason.orEmpty()
        ).any { field ->
            field.lowercase(Locale.ROOT).contains(q)
        }
    }

    const val QR_STICKER_PREFIX = "HELPCSL_REM:"

    fun encodeQrStickerPayload(item: RepairEquipmentItem): String {
        val code = item.intakeNumber.ifBlank { item.id.take(8).uppercase(Locale.ROOT) }
        return "$QR_STICKER_PREFIX$code|${item.id}"
    }

    fun findByScannedQrOrCode(
        items: List<RepairEquipmentItem>,
        rawScan: String
    ): RepairEquipmentItem? {
        val trimmed = rawScan.trim()
        if (trimmed.isEmpty()) return null
        val active = items.filter { !it.isDeleted }

        if (trimmed.startsWith(QR_STICKER_PREFIX, ignoreCase = true)) {
            val payload = trimmed.substring(QR_STICKER_PREFIX.length)
            val parts = payload.split('|')
            val intake = parts.getOrNull(0)?.trim().orEmpty()
            val itemId = parts.getOrNull(1)?.trim().orEmpty()
            if (itemId.isNotEmpty()) {
                active.firstOrNull { it.id.equals(itemId, ignoreCase = true) }?.let { return it }
            }
            if (intake.isNotEmpty()) {
                active.firstOrNull { it.intakeNumber.equals(intake, ignoreCase = true) }?.let { return it }
            }
        }

        // Direct REM-XXXX or S/N match
        active.firstOrNull { it.intakeNumber.equals(trimmed, ignoreCase = true) }?.let { return it }
        active.firstOrNull {
            it.serialNumber.isNotBlank() && it.serialNumber.equals(trimmed, ignoreCase = true)
        }?.let { return it }

        return null
    }

    fun buildClientReadyMessage(item: RepairEquipmentItem): String {
        val client = item.clientName.trim().substringBefore(" (").ifBlank { "Уважаемый клиент" }
        val dev = listOf(item.deviceCategory.trim(), item.deviceModel.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { "оборудование" }
        val rem = item.intakeNumber.ifBlank { item.id.take(8).uppercase(Locale.ROOT) }
        val statusNote = when (item.status) {
            RepairStatus.READY -> "готово к выдаче"
            RepairStatus.WAITING_PARTS -> "прошло диагностику (ожидается согласование / поставка комплектующих)"
            RepairStatus.ISSUED -> "выдано из сервисного центра"
            else -> "находится на этапе «${item.status.title}»"
        }
        return buildString {
            append("Здравствуйте, $client! ")
            append("Ваше оборудование ($dev, квитанция $rem) $statusNote ")
            append("в сервисном центре ООО «Компьютерная безопасность» (csl.by): ")
            append("г. Минск, ул. Фабрициуса, 9, Пн–Пт 09:00–17:00, тел. +375 (29) 337-14-12.")
            if (item.repairNotes.isNotBlank() && item.status == RepairStatus.READY) {
                append(" Выполненные работы: ${item.repairNotes.trim().take(180)}")
            }
        }
    }
}

data class ClientDirectoryEntry(
    val name: String = "",
    val phone: String? = null,
    val source: String? = null,
    val lastSeenEpochMs: Long = 0L
)

object ClientDirectoryCodec {

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val WORD_SPLIT_REGEX = Regex("[\\s\"'«»().,/-]+")

    fun normalizeKey(rawName: String): String =
        rawName.trim().replace(WHITESPACE_REGEX, " ").lowercase(Locale.ROOT)

    fun isValidClientName(
        rawName: String,
        filterSettings: FilterSettings = FilterSettings()
    ): Boolean {
        val clean = rawName.trim().replace(WHITESPACE_REGEX, " ")
        if (clean.length < 2) return false
        val low = clean.lowercase(Locale.ROOT)
        if (low == "клиент" || low == "unknown" || low == "не указан" || low == "клиент не указан") {
            return false
        }
        if (low.contains(AdminChatCodec.ADMIN_CHAT_MARKER.lowercase(Locale.ROOT)) ||
            low.contains(RepairEquipmentCodec.REPAIR_REGISTRY_MARKER.lowercase(Locale.ROOT))
        ) {
            return false
        }
        val excludedTokens = filterSettings.excludedReporters
            .split(',', ';', '\n')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 3 }
        if (excludedTokens.any { token -> low == token || low.contains(token) }) {
            return false
        }
        return true
    }

    fun mergeEntries(
        existing: List<ClientDirectoryEntry>,
        incoming: List<ClientDirectoryEntry>,
        filterSettings: FilterSettings = FilterSettings()
    ): List<ClientDirectoryEntry> {
        val byKey = LinkedHashMap<String, ClientDirectoryEntry>()

        fun putOne(entry: ClientDirectoryEntry, isIncoming: Boolean) {
            val cleanName = entry.name.trim().replace(WHITESPACE_REGEX, " ")
            if (!isValidClientName(cleanName, filterSettings)) return
            val key = cleanName.lowercase(Locale.ROOT)
            val cleanPhone = entry.phone?.trim()?.takeIf { it.isNotEmpty() }
            val cleanSource = entry.source?.trim()?.takeIf { it.isNotEmpty() }
            val prev = byKey[key]
            if (prev == null) {
                byKey[key] = ClientDirectoryEntry(
                    name = cleanName,
                    phone = cleanPhone,
                    source = cleanSource,
                    lastSeenEpochMs = entry.lastSeenEpochMs
                )
            } else {
                val preferNewName = isIncoming && (
                    cleanSource == "В ремонт" ||
                        (prev.name == prev.name.lowercase(Locale.ROOT) && cleanName != cleanName.lowercase(Locale.ROOT))
                    )
                val chosenPhone = if (isIncoming && cleanPhone != null && entry.lastSeenEpochMs >= prev.lastSeenEpochMs) {
                    cleanPhone
                } else {
                    prev.phone ?: cleanPhone
                }
                byKey[key] = ClientDirectoryEntry(
                    name = if (preferNewName) cleanName else prev.name,
                    phone = chosenPhone,
                    source = cleanSource ?: prev.source,
                    lastSeenEpochMs = maxOf(prev.lastSeenEpochMs, entry.lastSeenEpochMs)
                )
            }
        }

        for (e in existing) putOne(e, isIncoming = false)
        for (i in incoming) putOne(i, isIncoming = true)

        return byKey.values
            .sortedWith(
                compareByDescending<ClientDirectoryEntry> { it.lastSeenEpochMs }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .take(1000)
    }

    fun filterSuggestions(
        entries: List<ClientDirectoryEntry>,
        rawQuery: String,
        limit: Int = 50
    ): List<ClientDirectoryEntry> {
        val q = rawQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            return entries.take(limit)
        }
        val qDigits = q.filter { it.isDigit() }
        return entries.mapNotNull { entry ->
            val lowName = entry.name.lowercase(Locale.ROOT)
            val lowPhone = entry.phone.orEmpty().lowercase(Locale.ROOT)
            val phoneDigits = lowPhone.filter { it.isDigit() }
            val words = lowName.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }
            val rank = when {
                lowName.startsWith(q) -> 0
                words.any { it.startsWith(q) } -> 1
                lowName.contains(q) -> 2
                qDigits.length >= 3 && phoneDigits.contains(qDigits) -> 3
                else -> return@mapNotNull null
            }
            rank to entry
        }.sortedWith(
            compareBy<Pair<Int, ClientDirectoryEntry>> { it.first }
                .thenByDescending { it.second.lastSeenEpochMs }
                .thenBy { it.second.name.lowercase(Locale.ROOT) }
        ).map { it.second }
            .take(limit)
    }
}
