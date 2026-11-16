package com.oddyn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oddyn.app.BuildConfig
import com.oddyn.app.OddynViewModel
import com.oddyn.app.data.Preferences
import com.oddyn.app.domain.Mode

@Composable
internal fun SettingsScreen(model: OddynViewModel, preferences: Preferences, busy: Boolean) {
    val check by model.serverCheck.collectAsStateWithLifecycle()
    val batches by model.batchCount.collectAsStateWithLifecycle()
    var address by rememberSaveable(preferences.serverUrl) { mutableStateOf(preferences.serverUrl) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val status = check?.takeIf { it.address == preferences.serverUrl }

    PageList {
        item { Heading("Настройки") }
        item {
            PanelCard {
                Text("Режим поиска", style = MaterialTheme.typography.titleMedium)
                Mode.entries.forEach { item ->
                    val selected = item == preferences.mode
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = selected, enabled = !busy, role = Role.RadioButton,
                            onClick = { model.pickMode(item) }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.label, color = White)
                            Text(
                                if (item == Mode.DEMO) "Условные матчи и рынки без интернета." else "Прематч-расписание с вашего сервера ODDYN.",
                                style = MaterialTheme.typography.labelMedium, color = Silver
                            )
                        }
                    }
                }
            }
        }
        item {
            PanelCard {
                Text("Сервер ODDYN", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Адрес сервера") }, placeholder = { Text("https://oddyn.example.org") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true
                )
                Button(onClick = { model.saveAddress(address) }, enabled = !busy && address != preferences.serverUrl) {
                    Text("Сохранить адрес")
                }
                OutlinedButton(onClick = model::checkAddress, enabled = !busy && preferences.serverUrl.isNotBlank()) {
                    Text("Проверить связь")
                }
                when {
                    status?.health != null -> Text("Сервер ответил · API v${status.health.apiVersion}", color = PaleGreen)
                    status?.error != null -> Text(status.error, color = Amber)
                    preferences.serverUrl.isEmpty() -> Text("Адрес сервера не задан", color = Silver)
                    else -> Text("Соединение ещё не проверено", color = Silver)
                }
            }
        }
        status?.health?.coverage?.takeIf { it.isNotEmpty() }?.let { item { CoverageCard(it) } }
        item {
            PanelCard {
                Text("Хранилище и синхронизация", style = MaterialTheme.typography.titleMedium)
                Text(model.syncStatus.label, color = Silver)
                Text("Расписания, очередь и журнал хранятся на устройстве.", color = Silver)
                Text("Сохранённых запусков поиска $batches", color = Silver)
                OutlinedButton(onClick = { confirmClear = true }, enabled = !busy && batches > 0) {
                    Text("Очистить расписания и очередь")
                }
            }
        }
        item { Notice("ODDYN ${BuildConfig.VERSION_NAME}", "Версия API 1 · время событий по МСК") }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить расписания и очередь?") },
            text = { Text("Журнал прогнозов, купоны и сохранённые экспрессы останутся на устройстве.") },
            confirmButton = {
                TextButton(onClick = {
                    model.clearAnalysis()
                    confirmClear = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }
}
