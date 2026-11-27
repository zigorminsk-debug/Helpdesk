package com.helpdesk.jiraagent.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Explicit DNS requests only. This tool never discovers or falls back to a resolver. */
enum class DnsLookupType(val label: String, val wireType: Int) {
    A("A", Type.A), AAAA("AAAA", Type.AAAA), MX("MX", Type.MX),
    TXT("TXT", Type.TXT), NS("NS", Type.NS), AD_SRV("AD SRV", Type.SRV)
}

data class DnsLookupResult(
    val server: String,
    val queryName: String,
    val type: DnsLookupType,
    val status: String,
    val records: List<String>,
    val error: String? = null
) {
    fun asText(): String = buildString {
        appendLine("DNS $server · $queryName · ${type.label}")
        appendLine(if (error == null) "Ответ: $status" else "Ошибка: $error")
        records.forEach { appendLine(it) }
        if (error == null && records.isEmpty()) appendLine("Записей в ответе нет")
    }.trimEnd()
}

object DnsLookupTool {
    /** Avoid implicit name resolution, octal/short forms, and misleading server hostnames. */
    fun parseServer(raw: String): InetAddress {
        val parts = raw.trim().split('.')
        require(parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } && (it == "0" || !it.startsWith('0')) && (it.toIntOrNull() ?: -1) in 0..255 }) {
            "Введите IPv4-адрес DNS-сервера, например 192.168.1.10"
        }
        return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
    }

    fun queryName(raw: String, type: DnsLookupType): String {
        val name = raw.trim().removeSuffix(".")
        require(name.isNotEmpty() && name.length <= 253 && name.split('.').all {
            it.isNotEmpty() && it.length <= 63 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' }
        }) { "Введите DNS-имя латинскими буквами, без схемы и порта" }
        val result = if (type == DnsLookupType.AD_SRV) "_ldap._tcp.dc._msdcs.$name" else name
        require(result.length <= 253) { "Слишком длинное DNS-имя" }
        return "$result."
    }

    suspend fun lookup(name: String, server: String, type: DnsLookupType): DnsLookupResult =
        withContext(Dispatchers.IO) { lookupBlocking(name, server, type) }

    // Port is injectable for isolated local DNS tests; UI always uses the standard port 53.
    fun lookupBlocking(name: String, server: String, type: DnsLookupType, port: Int = 53, timeoutMs: Int = 2500): DnsLookupResult {
        var qname = name.trim()
        return try {
            val address = parseServer(server)
            require(port in 1..65535 && timeoutMs in 1..30000)
            qname = queryName(name, type)
            val question = Record.newRecord(Name.fromString(qname), type.wireType, DClass.IN)
            val query = Message.newQuery(question)
            val wire = query.toWire()
            val responseBytes = DatagramSocket().use { socket ->
                socket.connect(address, port)
                socket.soTimeout = timeoutMs
                socket.send(DatagramPacket(wire, wire.size))
                val buffer = ByteArray(65535)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                packet.data.copyOf(packet.length)
            }
            var response = Message(responseBytes)
            validateResponse(query, response)
            if (response.header.getFlag(Flags.TC)) {
                response = Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    DataOutputStream(socket.getOutputStream()).apply { writeShort(wire.size); write(wire); flush() }
                    val input = DataInputStream(socket.getInputStream())
                    val size = input.readUnsignedShort()
                    require(size >= 12) { "Некорректная длина DNS-ответа" }
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    Message(bytes)
                }
                validateResponse(query, response)
                require(!response.header.getFlag(Flags.TC)) { "DNS-ответ обрезан и по TCP" }
            }
            val answer = response.getSection(Section.ANSWER)
            val records = answer.take(100).map {
                "${it.name} ${it.ttl} сек ${Type.string(it.type)} ${it.rdataToString()}"
            } + if (answer.size > 100) listOf("Показаны первые 100 записей из ${answer.size}") else emptyList()
            DnsLookupResult(server.trim(), qname, type, Rcode.string(response.rcode), records)
        } catch (e: Exception) {
            DnsLookupResult(server.trim(), qname, type, "", emptyList(), e.message?.take(200) ?: "Нет ответа или ошибка сети")
        }
    }

    private fun validateResponse(query: Message, response: Message) {
        require(response.header.id == query.header.id && response.header.getFlag(Flags.QR) &&
            response.question == query.question) { "Некорректный DNS-ответ" }
    }
}
