package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.DnsLookupTool
import com.helpdesk.jiraagent.domain.DnsLookupType
import org.junit.Assert.*
import org.junit.Test
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class DnsLookupToolTest {
    @Test
    fun validatesInputsWithoutResolvingServerNames() {
        listOf("8.8.8.8.example", "localhost", "127.1", "010.0.0.1", "256.1.1.1", "1.2.3.4:53").forEach {
            assertTrue(runCatching { DnsLookupTool.parseServer(it) }.isFailure)
        }
        assertEquals("192.168.1.2", DnsLookupTool.parseServer(" 192.168.1.2 ").hostAddress)
        assertEquals("_ldap._tcp.dc._msdcs.corp.example.", DnsLookupTool.queryName("corp.example.", DnsLookupType.AD_SRV))
        listOf("https://example.com", "x..com", "example.com:53", "сеть.local").forEach {
            assertTrue(runCatching { DnsLookupTool.queryName(it, DnsLookupType.A) }.isFailure)
        }
    }

    @Test
    fun queriesOnlyTheSelectedServerAndReportsAnswers() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { socket ->
            socket.soTimeout = 3000
            val worker = thread {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val query = Message(packet.data.copyOf(packet.length))
                val response = Message(query.header.id).apply {
                    header.setFlag(Flags.QR.toInt())
                    addRecord(query.question, Section.QUESTION)
                    addRecord(ARecord(query.question.name, DClass.IN, 60, InetAddress.getByAddress(byteArrayOf(10, 1, 2, 3))), Section.ANSWER)
                }.toWire()
                socket.send(DatagramPacket(response, response.size, packet.socketAddress))
            }
            val result = DnsLookupTool.lookupBlocking("corp.example", "127.0.0.1", DnsLookupType.A, socket.localPort)
            worker.join(4000)
            assertFalse(worker.isAlive)
            assertNull(result.error)
            assertEquals("NOERROR", result.status)
            assertTrue(result.records.single().contains("10.1.2.3"))
            assertTrue(result.asText().contains("127.0.0.1"))
        }
    }

    @Test
    fun retriesTruncatedUdpResponseOverTcpToSameServer() {
        ServerSocket().use { tcp ->
            tcp.bind(InetSocketAddress("127.0.0.1", 0))
            tcp.soTimeout = 4000
            DatagramSocket(tcp.localPort, InetAddress.getByName("127.0.0.1")).use { udp ->
                udp.soTimeout = 4000
                val worker = thread {
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    udp.receive(packet)
                    val query = Message(packet.data.copyOf(packet.length))
                    val truncated = Message(query.header.id).apply {
                        header.setFlag(Flags.QR.toInt()); header.setFlag(Flags.TC.toInt())
                        addRecord(query.question, Section.QUESTION)
                    }.toWire()
                    udp.send(DatagramPacket(truncated, truncated.size, packet.socketAddress))
                    tcp.accept().use { connection ->
                        val input = DataInputStream(connection.getInputStream())
                        val bytes = ByteArray(input.readUnsignedShort())
                        input.readFully(bytes)
                        val tcpQuery = Message(bytes)
                        val answer = Message(tcpQuery.header.id).apply {
                            header.setFlag(Flags.QR.toInt())
                            addRecord(tcpQuery.question, Section.QUESTION)
                            addRecord(ARecord(tcpQuery.question.name, DClass.IN, 30, InetAddress.getByAddress(byteArrayOf(10, 5, 6, 7))), Section.ANSWER)
                        }.toWire()
                        DataOutputStream(connection.getOutputStream()).apply { writeShort(answer.size); write(answer); flush() }
                    }
                }
                val result = DnsLookupTool.lookupBlocking("host.local", "127.0.0.1", DnsLookupType.A, tcp.localPort)
                worker.join(5000)
                assertFalse(worker.isAlive)
                assertNull(result.error)
                assertTrue(result.records.single().contains("10.5.6.7"))
            }
        }
    }

    @Test
    fun distinguishesNxDomainFromTransportFailure() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { socket ->
            socket.soTimeout = 3000
            val worker = thread {
                val packet = DatagramPacket(ByteArray(1024), 1024)
                socket.receive(packet)
                val query = Message(packet.data.copyOf(packet.length))
                val response = Message(query.header.id).apply {
                    header.setFlag(Flags.QR.toInt())
                    header.rcode = Rcode.NXDOMAIN
                    addRecord(query.question, Section.QUESTION)
                }.toWire()
                socket.send(DatagramPacket(response, response.size, packet.socketAddress))
            }
            val result = DnsLookupTool.lookupBlocking("missing.local", "127.0.0.1", DnsLookupType.A, socket.localPort)
            worker.join(4000)
            assertNull(result.error)
            assertEquals("NXDOMAIN", result.status)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test
    fun rejectsMismatchedResponseAndReportsNoRecords() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { socket ->
            socket.soTimeout = 3000
            val worker = thread {
                val packet = DatagramPacket(ByteArray(1024), 1024)
                socket.receive(packet)
                val query = Message(packet.data.copyOf(packet.length))
                val response = Message((query.header.id + 1) and 0xffff).apply {
                    header.setFlag(Flags.QR.toInt())
                    addRecord(query.question, Section.QUESTION)
                }.toWire()
                socket.send(DatagramPacket(response, response.size, packet.socketAddress))
            }
            val result = DnsLookupTool.lookupBlocking("host.local", "127.0.0.1", DnsLookupType.A, socket.localPort)
            worker.join(4000)
            assertNotNull(result.error)
            assertTrue(result.records.isEmpty())
        }
    }
}
