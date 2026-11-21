package com.helpdesk.jiraagent.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.helpdesk.jiraagent.R
import com.helpdesk.jiraagent.data.SettingsRepository

/**
 * Android Quick Settings Tile ("Дежурство Help-CSL") allowing admins to toggle
 * non-working-hours notifications (24/7 duty vs RB working calendar) in 1 tap from the shade.
 */
class HelpCslDutyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileVisuals()
    }

    override fun onClick() {
        super.onClick()
        val repo = SettingsRepository.getInstance(applicationContext)
        val current = repo.appSettings.value
        val updated = current.copy(workScheduleEnabled = !current.workScheduleEnabled)
        repo.saveAppSettings(updated)
        JiraMonitorService.syncServiceState(applicationContext)
        refreshTileVisuals()

        val msg = if (!updated.workScheduleEnabled) {
            "Дежурство 24/7 включено: уведомления приходят в любое время"
        } else {
            "Включён график РБ (${updated.workStartTime}–${updated.workEndTime})"
        }
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun refreshTileVisuals() {
        val tile = qsTile ?: return
        val settings = SettingsRepository.getInstance(applicationContext).appSettings.value
        val is24x7Duty = !settings.workScheduleEnabled

        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification_ticket)
        tile.state = if (is24x7Duty) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (is24x7Duty) "Help-CSL 24/7" else "Help-CSL РБ"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (is24x7Duty) {
                "Круглосуточно"
            } else {
                "${settings.workStartTime}–${settings.workEndTime}"
            }
        }
        tile.updateTile()
    }
}
