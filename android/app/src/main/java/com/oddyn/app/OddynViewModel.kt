package com.oddyn.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oddyn.app.data.InputProblem
import com.oddyn.app.data.OddynRepository
import com.oddyn.app.data.Preferences
import com.oddyn.app.data.PreferencesStore
import com.oddyn.app.data.SearchResults
import com.oddyn.app.data.ServerSchedule
import com.oddyn.app.data.SourceUnavailable
import com.oddyn.app.data.TicketInput
import com.oddyn.app.data.serverAddress
import com.oddyn.app.domain.Health
import com.oddyn.app.domain.Leg
import com.oddyn.app.domain.Mode
import com.oddyn.app.domain.TicketResult
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Dashboard(val preferences: Preferences = Preferences(), val search: SearchResults = SearchResults())
data class ServerCheck(val address: String, val health: Health? = null, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class OddynViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OddynRepository(application)
    private val settings = PreferencesStore(application)
    private val busyState = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyState
    private val messages = Channel<String>(Channel.BUFFERED)
    val notices = messages.receiveAsFlow()
    private val check = MutableStateFlow<ServerCheck?>(null)
    val serverCheck: StateFlow<ServerCheck?> = check
    private val warning = MutableStateFlow<Pair<String, String>?>(null)
    val searchWarning: StateFlow<Pair<String, String>?> = warning

    val dashboard = settings.values.flatMapLatest { preferences ->
        repository.observe(preferences).map { Dashboard(preferences, it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Dashboard())

    val forecasts = repository.forecasts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val expresses = repository.expresses.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tickets = repository.tickets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val draft = repository.draft.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val batchCount = repository.batches.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val syncStatus = repository.sync.status

    fun pickMode(mode: Mode) = action { settings.setMode(mode) }
    fun pickDate(date: LocalDate) = action { settings.setDate(date) }
    fun search() = action {
        val preferences = settings.values.first()
        warning.value = null
        try {
            repository.search(preferences)
        } catch (error: SourceUnavailable) {
            warning.value = preferences.scope to error.message.orEmpty()
        } catch (error: InputProblem) {
            warning.value = preferences.scope to error.message.orEmpty()
        }
    }

    fun saveForecast(leg: Leg) = action {
        messages.send(if (repository.saveForecast(leg)) "Прогноз сохранён на устройстве." else "Этот прогноз уже в журнале.")
    }

    fun addDraft(leg: Leg) = action {
        repository.addDraft(leg)
        messages.send("Плечо добавлено в экспресс.")
    }

    fun removeDraft(mode: Mode, key: String) = action { repository.removeDraft(mode, key) }

    fun saveExpress(mode: Mode) = action {
        repository.saveExpress(mode)
        messages.send("Подборка сохранена на устройстве.")
    }

    fun addTicket(input: TicketInput, onSaved: () -> Unit) = action {
        repository.addTicket(input)
        onSaved()
        messages.send("Купон добавлен в журнал.")
    }

    fun settleTicket(id: String, result: TicketResult, payout: String, onSaved: () -> Unit) = action {
        repository.settleTicket(id, result, payout)
        onSaved()
        messages.send("Фактический расчёт сохранён.")
    }

    fun saveAddress(address: String) = action {
        val normalized = if (address.isBlank()) "" else serverAddress(address)?.toString()
            ?: throw InputProblem("Укажите HTTPS-адрес сервера без логина, параметров и фрагмента.")
        settings.setServer(normalized)
        if (check.value?.address != normalized) check.value = null
        messages.send(if (normalized.isEmpty()) "Адрес сервера удалён." else "Адрес сервера сохранён.")
    }

    fun checkAddress() = action {
        val url = settings.values.first().serverUrl
        check.value = try {
            ServerCheck(url, health = ServerSchedule(url).health())
        } catch (error: SourceUnavailable) {
            ServerCheck(url, error = error.message)
        }
    }

    fun clearAnalysis() = action {
        repository.clearAnalysis()
        warning.value = null
        messages.send("Расписания и очередь удалены с устройства.")
    }

    private fun action(run: suspend () -> Unit) {
        if (!busyState.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                run()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SourceUnavailable) {
                messages.send(error.message ?: "Не удалось загрузить данные.")
            } catch (error: InputProblem) {
                messages.send(error.message ?: "Проверьте данные.")
            } catch (error: Exception) {
                android.util.Log.e("ODDYN", "Local operation failed", error)
                messages.send("Операция не завершена. Повторите попытку.")
            } finally {
                busyState.value = false
            }
        }
    }
}
