package com.helpdesk.jiraagent.domain

import java.util.Locale
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt

/** Only manually entered and explicitly confirmed examples are accepted as training data. */
data class ConfirmedSolution(
    val id: String,
    val scope: String,
    val ticketKey: String,
    val symptoms: String,
    val solution: String,
    val votes: Map<String, Int> = emptyMap()
)

data class SolutionSuggestion(val example: ConfirmedSolution, val score: Double, val matchingTerms: List<String>)

object LocalRecommendations {
    const val MAX_EXAMPLES = 300
    const val MAX_SYMPTOMS = 1000
    const val MAX_SOLUTION = 4000
    private val stop = setOf("для", "при", "что", "как", "или", "это", "нет", "the", "and", "for", "with", "необходимо", "проблема", "ошибка")
    private val word = Regex("[\\p{L}\\p{N}]{3,}")

    fun scope(server: String, user: String): String =
        if (server.isBlank() || user.isBlank()) "" else server.trim().trimEnd('/').lowercase(Locale.ROOT) + "\n" + user.trim().lowercase(Locale.ROOT)

    fun terms(text: String): Set<String> = word.findAll(text.take(20_000).lowercase(Locale.ROOT).replace('ё', 'е'))
        .map { it.value }.filter { it !in stop }.take(500).toSet()

    fun add(items: List<ConfirmedSolution>, scope: String, key: String, symptoms: String, solution: String): List<ConfirmedSolution>? {
        val s = symptoms.trim(); val r = solution.trim()
        if (scope.isBlank() || key.isBlank() || s.length !in 3..MAX_SYMPTOMS || r.length !in 3..MAX_SOLUTION || terms(s).isEmpty() || items.size >= MAX_EXAMPLES) return null
        return items + ConfirmedSolution(UUID.randomUUID().toString(), scope, key.trim().take(100), s, r)
    }

    fun vote(items: List<ConfirmedSolution>, scope: String, id: String, ticketKey: String, value: Int): List<ConfirmedSolution> {
        if (scope.isBlank() || ticketKey.isBlank() || value !in -1..1) return items
        return items.map { example ->
            if (example.scope != scope || example.id != id) example else {
                val key = ticketKey.trim().uppercase(Locale.ROOT).take(100)
                if (key !in example.votes && example.votes.size >= 500) example else
                    example.copy(votes = if (value == 0) example.votes - key else example.votes + (key to value))
            }
        }
    }

    fun remove(items: List<ConfirmedSolution>, scope: String, id: String): List<ConfirmedSolution> =
        items.filterNot { scope.isNotBlank() && it.scope == scope && it.id == id }

    /** TF-IDF cosine similarity over reviewed symptom descriptions; feedback adjusts ranking, not eligibility. */
    fun suggest(items: List<ConfirmedSolution>, scope: String, ticketKey: String, text: String): List<SolutionSuggestion> {
        if (scope.isBlank()) return emptyList()
        val examples = items.filter { it.scope == scope && !it.ticketKey.equals(ticketKey, true) }
        val docs = examples.map { terms(it.symptoms) }
        val query = terms(text)
        if (query.isEmpty()) return emptyList()
        val weights = mutableMapOf<String, Double>()
        fun idf(t: String): Double = weights.getOrPut(t) { ln((examples.size + 1.0) / (1.0 + docs.count { t in it })) + 1.0 }
        val queryNorm = sqrt(query.sumOf { idf(it) * idf(it) })
        return examples.mapIndexedNotNull { i, example ->
            val doc = docs[i]; val common = query.intersect(doc)
            if (common.isEmpty()) return@mapIndexedNotNull null
            val denominator = queryNorm * sqrt(doc.sumOf { idf(it) * idf(it) })
            if (denominator == 0.0) return@mapIndexedNotNull null
            val similarity = common.sumOf { idf(it) * idf(it) } / denominator
            if (similarity < 0.12) return@mapIndexedNotNull null
            val votes = example.votes.values.sum().coerceIn(-10, 10)
            SolutionSuggestion(example, similarity * (1.0 + votes * 0.04), common.sorted().take(8))
        }.sortedByDescending { it.score }.take(3)
    }
}
