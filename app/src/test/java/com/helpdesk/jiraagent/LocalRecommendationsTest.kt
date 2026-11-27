package com.helpdesk.jiraagent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.helpdesk.jiraagent.domain.ConfirmedSolution
import com.helpdesk.jiraagent.domain.LocalRecommendations as Model
import org.junit.Assert.*
import org.junit.Test

class LocalRecommendationsTest {
    private val scope = Model.scope("https://jira.example/", "Agent")
    private fun add(items: List<ConfirmedSolution>, s: String, key: String, text: String, solution: String = "Проверенное решение") =
        requireNotNull(Model.add(items, s, key, text, solution))

    @Test fun learnsOnlyFromConfirmedExamplesAndRanksByText() {
        assertTrue(Model.suggest(emptyList(), scope, "HD-3", "принтер печать").isEmpty())
        var data = add(emptyList(), scope, "HD-1", "принтер печать драйвер")
        data = add(data, scope, "HD-2", "vpn соединение шлюз")
        val suggestions = Model.suggest(data, scope, "HD-3", "принтер печать драйвер")
        assertEquals(listOf("HD-1"), suggestions.map { it.example.ticketKey })
        assertTrue(Model.suggest(data, scope, "HD-1", "принтер печать драйвер").isEmpty())
        assertNull(Model.add(data, scope, "HD-4", "  ", "ответ"))
    }

    @Test fun feedbackChangesRankingAndCanBeReplacedOrCleared() {
        var data = add(emptyList(), scope, "HD-1", "принтер печать")
        data = add(data, scope, "HD-2", "принтер печать")
        val second = data[1].id
        data = Model.vote(data, scope, second, "HD-3", 1)
        assertEquals(second, Model.suggest(data, scope, "HD-3", "принтер печать").first().example.id)
        data = Model.vote(data, scope, second, "hd-3", -1)
        assertEquals(-1, data[1].votes["HD-3"])
        data = Model.vote(data, scope, second, "HD-3", 0)
        assertTrue(data[1].votes.isEmpty())
    }

    @Test fun scopeDeletionAndJsonRoundTrip() {
        val other = Model.scope("https://jira.example", "other")
        var data = add(emptyList(), scope, "HD-1", "vpn связь")
        data = add(data, other, "HD-2", "vpn связь")
        data = Model.vote(data, scope, data[0].id, "HD-3", 1)
        val type = object : TypeToken<List<ConfirmedSolution>>() {}.type
        val restored: List<ConfirmedSolution> = Gson().fromJson(Gson().toJson(data), type)
        assertEquals(data, restored)
        assertEquals(listOf("HD-1"), Model.suggest(restored, scope, "HD-3", "vpn связь").map { it.example.ticketKey })
        assertEquals(restored, Model.remove(restored, other, restored[0].id))
        val remaining = Model.remove(restored, scope, restored[0].id)
        assertEquals(listOf("HD-2"), remaining.map { it.ticketKey })
        assertTrue(Model.suggest(remaining, scope, "HD-3", "vpn связь").isEmpty())
        assertTrue(Model.suggest(data, "", "HD-3", "vpn связь").isEmpty())
    }
}
