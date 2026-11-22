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
    ISSUED("Выдано");

    fun matches(status: RepairStatus): Boolean = when (this) {
        ACTIVE -> status == RepairStatus.RECEIVED ||
            status == RepairStatus.DIAGNOSTICS ||
            status == RepairStatus.IN_REPAIR
        WAITING -> status == RepairStatus.WAITING_PARTS
        READY -> status == RepairStatus.READY
        ISSUED -> status == RepairStatus.ISSUED
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
            item.shelfLocation
        ).any { field ->
            field.lowercase(Locale.ROOT).contains(q)
        }
    }
}
