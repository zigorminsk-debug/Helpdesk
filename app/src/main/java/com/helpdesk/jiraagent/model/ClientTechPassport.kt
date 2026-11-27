package com.helpdesk.jiraagent.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.util.Locale

data class ClientTechPassport(
    val clientKey: String = "",
    val clientDisplayName: String = "",
    val addressesAndBranches: String = "",
    val gatewayAndProvider: String = "",
    val vpnAndRemoteNotes: String = "",
    val serversAndServices: String = "",
    val contactsAndNotes: String = "",
    val updatedBy: String = "",
    val updatedAtEpochMs: Long = 0L,
    val pendingRemoteSync: Boolean = false
) {
    val clientName: String
        get() = clientDisplayName

    val isEmpty: Boolean
        get() = addressesAndBranches.isBlank() &&
            gatewayAndProvider.isBlank() &&
            vpnAndRemoteNotes.isBlank() &&
            serversAndServices.isBlank() &&
            contactsAndNotes.isBlank()

    fun toCombinedSearchableText(): String = buildString {
        if (addressesAndBranches.isNotBlank()) appendLine(addressesAndBranches)
        if (gatewayAndProvider.isNotBlank()) appendLine(gatewayAndProvider)
        if (vpnAndRemoteNotes.isNotBlank()) appendLine(vpnAndRemoteNotes)
        if (serversAndServices.isNotBlank()) appendLine(serversAndServices)
        if (contactsAndNotes.isNotBlank()) appendLine(contactsAndNotes)
    }.trim()
}

object ClientTechPassportCodec {

    const val PASSPORT_PREFIX = "[HELPCSL_PASSPORT_V1]"
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun normalizeClientKey(rawName: String?): String {
        return rawName
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    }

    fun normalizeKey(rawName: String?): String = normalizeClientKey(rawName)

    fun encodeComment(passport: ClientTechPassport): String {
        val normKey = normalizeClientKey(passport.clientDisplayName.ifBlank { passport.clientKey })
        val clean = passport.copy(
            clientKey = normKey,
            pendingRemoteSync = false
        )
        return "$PASSPORT_PREFIX\n${gson.toJson(clean)}"
    }

    fun decodeComment(rawBody: String): ClientTechPassport? {
        val idx = rawBody.indexOf(PASSPORT_PREFIX)
        if (idx < 0) return null
        val after = rawBody.substring(idx + PASSPORT_PREFIX.length).trim()
        val braceStart = after.indexOf('{')
        val braceEnd = after.lastIndexOf('}')
        if (braceStart < 0 || braceEnd <= braceStart) return null
        val jsonSlice = after.substring(braceStart, braceEnd + 1)
        return runCatching {
            val parsed = gson.fromJson(jsonSlice, ClientTechPassport::class.java) ?: return@runCatching null
            val key = normalizeClientKey(parsed.clientKey.ifBlank { parsed.clientDisplayName })
            if (key.isEmpty()) null
            else parsed.copy(
                clientKey = key,
                clientDisplayName = parsed.clientDisplayName.orEmpty().ifBlank { key },
                addressesAndBranches = parsed.addressesAndBranches.orEmpty(),
                gatewayAndProvider = parsed.gatewayAndProvider.orEmpty(),
                vpnAndRemoteNotes = parsed.vpnAndRemoteNotes.orEmpty(),
                serversAndServices = parsed.serversAndServices.orEmpty(),
                contactsAndNotes = parsed.contactsAndNotes.orEmpty(),
                updatedBy = parsed.updatedBy.orEmpty(),
                pendingRemoteSync = false
            )
        }.getOrNull()
    }

    fun mergePassports(
        localMap: Map<String, ClientTechPassport>,
        remoteEvents: List<ClientTechPassport>,
        legacyLocalNotes: Map<String, String> = emptyMap()
    ): Map<String, ClientTechPassport> {
        val merged = LinkedHashMap<String, ClientTechPassport>()

        // 1. Seed from legacy plain-text local notes if present
        for ((rawKey, noteText) in legacyLocalNotes) {
            val k = normalizeClientKey(rawKey)
            if (k.isNotEmpty() && noteText.isNotBlank()) {
                merged[k] = ClientTechPassport(
                    clientKey = k,
                    clientDisplayName = rawKey.trim(),
                    contactsAndNotes = noteText.trim(),
                    updatedAtEpochMs = 1L
                )
            }
        }

        // 2. Apply remote Jira events (last-write-wins)
        for (rem in remoteEvents) {
            val k = normalizeClientKey(rem.clientKey.ifBlank { rem.clientDisplayName })
            if (k.isEmpty()) continue
            val prev = merged[k]
            if (prev == null || rem.updatedAtEpochMs >= prev.updatedAtEpochMs) {
                merged[k] = rem.copy(clientKey = k, pendingRemoteSync = false)
            }
        }

        // 3. Apply newer local entries
        for ((rawKey, loc) in localMap) {
            val k = normalizeClientKey(loc.clientKey.ifBlank { loc.clientDisplayName.ifBlank { rawKey } })
            if (k.isEmpty()) continue
            val prev = merged[k]
            if (prev == null) {
                merged[k] = loc.copy(clientKey = k)
            } else if (loc.updatedAtEpochMs > prev.updatedAtEpochMs) {
                merged[k] = loc.copy(clientKey = k, pendingRemoteSync = true)
            } else if (loc.updatedAtEpochMs == prev.updatedAtEpochMs) {
                merged[k] = loc.copy(clientKey = k, pendingRemoteSync = false)
            }
        }

        return merged
    }
}
