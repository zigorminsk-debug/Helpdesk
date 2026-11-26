package com.helpdesk.jiraagent

import com.helpdesk.jiraagent.domain.ServicePortReference
import org.junit.Assert.*
import org.junit.Test

class ServicePortReferenceTest {
    @Test fun entriesAreCompleteAndUnique() {
        val ports = ServicePortReference.entries
        assertEquals(ports.size, ports.map { it.id }.distinct().size)
        ports.forEach {
            assertTrue(it.service.isNotBlank() && it.port.isNotBlank() && it.use.isNotBlank() && it.note.isNotBlank())
        }
    }

    @Test fun searchFindsPortsAndDescriptionsWithoutNetwork() {
        assertTrue(ServicePortReference.search(" 443  ").any { it.id == "https" })
        assertTrue(ServicePortReference.search("KeRbErOs 88").any { it.id == "kerberos" })
        assertTrue(ServicePortReference.search("неизвестная-служба-1234").isEmpty())
        assertEquals(ServicePortReference.entries.size, ServicePortReference.search("").size)
    }
}
