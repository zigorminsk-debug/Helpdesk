package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.NetworkDiagnosticsTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsPrivacyTest {
    @Test
    fun systemAddressLookupDoesNotPopulateExtendedDnsRecords() = runBlocking {
        val report = NetworkDiagnosticsTool.runDiagnostics(
            rawHost = "localhost",
            timeoutMs = 100,
            portsToScan = emptyList(),
            includeHttpProbe = false
        )
        assertNotNull(report.resolvedIp)
        assertTrue(report.dnsRecords.aRecords.isNotEmpty())
        assertFalse(report.dnsRecords.hasExtendedRecords)
        assertTrue(report.dnsRecords.mxRecords.isEmpty())
        assertTrue(report.dnsRecords.txtSpfRecords.isEmpty())
        assertTrue(report.dnsRecords.nsRecords.isEmpty())
    }

    @Test
    fun emptyHostFailsWithoutProbes() = runBlocking {
        val report = NetworkDiagnosticsTool.runDiagnostics(" ", portsToScan = emptyList())
        assertEquals("Пустой адрес хоста", report.errorMessage)
        assertTrue(report.portStatuses.isEmpty())
        assertFalse(report.dnsRecords.hasExtendedRecords)
    }
}
