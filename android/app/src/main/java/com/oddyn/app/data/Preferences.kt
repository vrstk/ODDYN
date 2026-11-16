package com.oddyn.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.oddyn.app.domain.Mode
import com.oddyn.app.domain.moscowDate
import java.time.LocalDate
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("oddyn_preferences")

data class Preferences(
    val mode: Mode = Mode.DEMO,
    val date: LocalDate = moscowDate(),
    val serverUrl: String = ""
) {
    val scope: String get() = "${mode.name}|$date|${if (mode == Mode.REAL) serverUrl else ""}"
}

class PreferencesStore(context: Context) {
    private val store = context.applicationContext.preferences
    private val modeKey = stringPreferencesKey("mode")
    private val dateKey = stringPreferencesKey("date")
    private val serverKey = stringPreferencesKey("server")

    val values = store.data.map { data ->
        Preferences(
            mode = if (data[modeKey] == Mode.REAL.name) Mode.REAL else Mode.DEMO,
            date = data[dateKey]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: moscowDate(),
            serverUrl = data[serverKey].orEmpty()
        )
    }

    suspend fun setMode(mode: Mode) { store.edit { it[modeKey] = mode.name } }
    suspend fun setDate(date: LocalDate) { store.edit { it[dateKey] = date.toString() } }
    suspend fun setServer(address: String) { store.edit { it[serverKey] = address } }
}
