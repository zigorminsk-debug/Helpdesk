package com.helpdesk.jiraagent.domain

/** Stable logical IDs: watchlist shortcuts and dialog content use these IDs, not visual positions. */
data class SysadminToolboxTab(val id: Int, val androidTitle: String, val desktopTitle: String)

object SysadminToolboxTabs {
    const val WAKE_ON_LAN = 0
    const val SUBNET = 1
    const val PASSWORDS = 2
    const val WATCHLIST = 3
    const val COMMANDS = 4
    const val PORTS = 5

    // Commands first, so they remain visible without horizontally scrolling on phones.
    val ordered = listOf(
        SysadminToolboxTab(COMMANDS, "⌨ Команды", "⌨ Команды"),
        SysadminToolboxTab(PORTS, "🔌 Порты", "🔌 Порты"),
        SysadminToolboxTab(WAKE_ON_LAN, "⚡ WOL", "⚡ Wake-on-LAN"),
        SysadminToolboxTab(SUBNET, "🌐 Подсети", "🌐 IP / Подсети"),
        SysadminToolboxTab(PASSWORDS, "🔐 Пароли", "🔐 Пароли"),
        SysadminToolboxTab(WATCHLIST, "🛡 SSL / Узлы", "🛡 SSL / Узлы")
    )

    fun positionFor(id: Int): Int = ordered.indexOfFirst { it.id == id }.coerceAtLeast(0)
    fun validIdOrDefault(id: Int): Int = if (ordered.any { it.id == id }) id else WAKE_ON_LAN
}
