package com.helpdesk.jiraagent.domain

import kotlin.math.max

enum class SlaUrgency {
    FRESH,
    WARNING,
    OVERDUE
}

data class SlaWaitBadge(
    val label: String,
    val elapsedMinutes: Long,
    val urgency: SlaUrgency
)

object SlaCalculator {

    /**
     * Computes waiting badge for a NEW client ticket created at [createdEpochMillis].
     */
    fun computeWaitBadge(
        createdEpochMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        warnThresholdMinutes: Int = 15,
        overdueThresholdMinutes: Int = 45
    ): SlaWaitBadge? {
        if (createdEpochMillis <= 0L) return null
        val diffMs = max(0L, nowMillis - createdEpochMillis)
        val totalMinutes = diffMs / 60_000L

        val label = when {
            totalMinutes < 1L -> "только что"
            totalMinutes < 60L -> "$totalMinutes мин"
            totalMinutes < 24L * 60L -> {
                val hours = totalMinutes / 60L
                val mins = totalMinutes % 60L
                if (mins == 0L) "$hours ч" else "$hours ч $mins м"
            }
            else -> {
                val days = totalMinutes / (24L * 60L)
                val hours = (totalMinutes % (24L * 60L)) / 60L
                if (hours == 0L) "$days дн" else "$days дн $hours ч"
            }
        }

        val urgency = when {
            totalMinutes >= overdueThresholdMinutes -> SlaUrgency.OVERDUE
            totalMinutes >= warnThresholdMinutes -> SlaUrgency.WARNING
            else -> SlaUrgency.FRESH
        }

        return SlaWaitBadge(
            label = "⏱ $label",
            elapsedMinutes = totalMinutes,
            urgency = urgency
        )
    }
}
