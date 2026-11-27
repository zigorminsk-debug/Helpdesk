package com.helpdesk.jiraagent.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.helpdesk.jiraagent.MainActivity
import com.helpdesk.jiraagent.R
import com.helpdesk.jiraagent.data.SettingsRepository
import com.helpdesk.jiraagent.model.TicketStage
import com.helpdesk.jiraagent.notification.NotificationHelper
import com.helpdesk.jiraagent.service.JiraMonitorService

class HelpCslAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_REFRESH) {
            JiraMonitorService.triggerImmediatePoll(context)
            requestUpdateAll(context)
        }
    }

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.helpdesk.jiraagent.action.WIDGET_REFRESH"

        fun requestUpdateAll(context: Context) {
            val appCtx = context.applicationContext
            val manager = AppWidgetManager.getInstance(appCtx)
            val component = ComponentName(appCtx, HelpCslAppWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids != null && ids.isNotEmpty()) {
                for (id in ids) {
                    updateSingleWidget(appCtx, manager, id)
                }
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val repo = SettingsRepository.getInstance(context)
            val allTickets = repo.tickets.value
            val clientTickets = allTickets.filter { it.isClientTicket }
            val newTickets = clientTickets.filter { it.stage == TicketStage.NEW }
            val inWorkTickets = clientTickets.filter { it.stage == TicketStage.IN_PROGRESS }
            val postponedCount = repo.postponedTickets.value.size

            val views = RemoteViews(context.packageName, R.layout.widget_help_csl)
            views.setTextViewText(R.id.widget_new_count, "Новые: ${newTickets.size}")
            views.setTextViewText(R.id.widget_work_count, "В работе: ${inWorkTickets.size}")
            views.setTextViewText(R.id.widget_postponed_count, "Отлож.: $postponedCount")

            val topItems = (newTickets + inWorkTickets).take(3)
            val rowIds = listOf(R.id.widget_ticket_1, R.id.widget_ticket_2, R.id.widget_ticket_3)

            if (topItems.isEmpty()) {
                views.setViewVisibility(R.id.widget_empty_text, View.VISIBLE)
                rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            } else {
                views.setViewVisibility(R.id.widget_empty_text, View.GONE)
                for (i in rowIds.indices) {
                    val viewId = rowIds[i]
                    val item = topItems.getOrNull(i)
                    if (item == null) {
                        views.setViewVisibility(viewId, View.GONE)
                    } else {
                        views.setViewVisibility(viewId, View.VISIBLE)
                        val prefix = if (item.stage == TicketStage.NEW) "● " else "⚙ "
                        views.setTextViewText(
                            viewId,
                            "$prefix${item.reporterName}: ${item.summary} (${item.key})"
                        )
                        val openTicketIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(NotificationHelper.EXTRA_OPEN_TICKET_KEY, item.key)
                        }
                        val pi = PendingIntent.getActivity(
                            context,
                            7000 + i + appWidgetId * 10,
                            openTicketIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(viewId, pi)
                    }
                }
            }

            val openMainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openMainPi = PendingIntent.getActivity(
                context,
                6000 + appWidgetId,
                openMainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openMainPi)

            val refreshIntent = Intent(context, HelpCslAppWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
            }
            val refreshPi = PendingIntent.getBroadcast(
                context,
                6500 + appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
