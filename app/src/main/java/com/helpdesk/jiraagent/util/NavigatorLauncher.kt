package com.helpdesk.jiraagent.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object NavigatorLauncher {

    fun openAddressInNavigator(context: Context, query: String) {
        val encoded = Uri.encode(query.trim())
        // 1. Prefer geo: URI so user can pick Yandex Navigator, Yandex Maps, Google Maps, OsmAnd, 2GIS, etc.
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedGeo = runCatching {
            context.startActivity(geoIntent)
            true
        }.getOrDefault(false)

        if (!launchedGeo) {
            // 2. Web fallback to Yandex Maps
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://yandex.by/maps/?text=$encoded")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(webIntent) }.onFailure {
                Toast.makeText(context, "Не найдено приложение карт/навигатора", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
