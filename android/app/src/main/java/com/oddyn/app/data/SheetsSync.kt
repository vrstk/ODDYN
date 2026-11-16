package com.oddyn.app.data

enum class SyncStatus(val label: String) {
    DISCONNECTED("Google Sheets не подключён")
}

interface JournalSync {
    val status: SyncStatus
}

object SheetsSync : JournalSync {
    override val status = SyncStatus.DISCONNECTED
}
