package com.helpdesk.jiraagent.shared

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helpdesk.jiraagent.domain.JiraConnectionAlarm

/** Color pulses without hiding text; retry remains available and the warning is not dismissible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JiraConnectionAlarmBanner(onRetry: () -> Unit, isRetrying: Boolean) {
    val transition = rememberInfiniteTransition(label = "jira-connection-alarm")
    val background by transition.animateColor(
        initialValue = Color(0xFF7B1B20), targetValue = Color(0xFFAB282C),
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alarm-color"
    )
    Surface(color = background, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(Modifier.weight(1f, fill = false).widthIn(min = 230.dp)) {
                Text(JiraConnectionAlarm.BANNER, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Проверьте сеть, VPN и сервер Jira. Состояние сервера для других пользователей неизвестно.",
                    style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
            OutlinedButton(onClick = onRetry, enabled = !isRetrying,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Text(if (isRetrying) "Проверка…" else "Повторить")
            }
        }
    }
}
