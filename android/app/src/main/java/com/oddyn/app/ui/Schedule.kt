package com.oddyn.app.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oddyn.app.Dashboard
import com.oddyn.app.data.SearchResults
import com.oddyn.app.domain.Coverage
import com.oddyn.app.domain.Event
import com.oddyn.app.domain.Mode
import com.oddyn.app.domain.Sport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@Composable
internal fun ScheduleScreen(
    dashboard: Dashboard,
    busy: Boolean,
    warning: Pair<String, String>?,
    home: Boolean,
    onDate: (LocalDate) -> Unit,
    onSearch: () -> Unit,
    onEvent: (String) -> Unit
) {
    val preferences = dashboard.preferences
    val search = dashboard.search
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var sport by rememberSaveable { mutableStateOf<Sport?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val now = Instant.now()
    val upcoming = search.events.filter { it.mode == Mode.DEMO || it.kickoff > now }
    val filtered = upcoming.filter {
        (sport == null || sport == it.sport) &&
            (query.isBlank() || listOf(it.title, it.tournament).any { label -> label.contains(query.trim(), ignoreCase = true) })
    }

    PageList {
        if (home) {
            item {
                FinderCard(preferences.date, search, busy, onCalendar = { showCalendar = true }, onSearch = onSearch)
            }
        } else {
            item {
                Heading("Матчи", if (preferences.mode == Mode.DEMO) "Демонстрационное расписание" else "Предстоящие события")
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CalendarButton(preferences.date, busy, { showCalendar = true }, Modifier.weight(1f))
                    OutlinedButton(onClick = onSearch, enabled = !busy) {
                        Text(if (search.waiting > 0) "Продолжить" else "Обновить")
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Команда, игрок или турнир") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true
                )
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        warning?.takeIf { it.first == preferences.scope }?.let {
            item { Notice("Данные не обновлены", it.second, warning = true) }
        }
        item(key = "filters") {
            SportFilters(sport, onSelected = { sport = it })
        }
        item {
            SectionTitle(if (home) "Найденные события" else "Расписание по МСК")
        }
        if (filtered.isEmpty()) {
            item {
                val (title, detail) = when {
                    busy -> "Поиск и проверка рынков" to "Найденные события появятся здесь."
                    !search.hasRun -> "Расписание ещё не загружено" to "Выберите дату и запустите поиск."
                    upcoming.isNotEmpty() -> "Совпадений нет" to "Измените фильтр или поисковый запрос."
                    preferences.mode == Mode.REAL && search.coverage.none { it.status == "available" } ->
                        "Нет доступного источника" to "Проверьте подключение и охват поставщиков в настройках."
                    else -> "Нет предстоящих матчей" to "На выбранную дату в доступном расписании событий нет."
                }
                Notice(title, detail)
            }
        }
        items(filtered, key = Event::id) { event ->
            EventCard(event, onClick = { onEvent(event.id) })
        }
        if (search.coverage.isNotEmpty()) {
            item { CoverageCard(search.coverage) }
        }
    }
    if (showCalendar) {
        MoscowDatePicker(preferences.date, close = { showCalendar = false }, select = {
            onDate(it)
            showCalendar = false
        })
    }
}

@Composable
private fun FinderCard(date: LocalDate, results: SearchResults, busy: Boolean, onCalendar: () -> Unit, onSearch: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, ColorFinderBorder)) {
        Box(Modifier.fillMaxWidth().then(FinderGradient)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Поиск ставок",
                    style = MaterialTheme.typography.headlineLarge,
                    color = White,
                    modifier = Modifier.semantics { heading() }
                )
                CalendarButton(date, busy, onCalendar, Modifier.fillMaxWidth())
                Button(
                    onClick = onSearch, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Icon(Icons.Filled.Search, null, modifier = Modifier.size(20.dp))
                    Text(
                        if (results.waiting > 0) "Продолжить анализ" else "Найти ставки",
                        modifier = Modifier.padding(start = 8.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (results.hasRun) {
                    val waiting = if (results.waiting > 0) " · в очереди ${results.waiting}" else ""
                    Text(
                        "Просмотрено ${results.processed} из ${results.found}$waiting",
                        style = MaterialTheme.typography.labelMedium, color = PaleGreen
                    )
                }
            }
        }
    }
}

private val ColorFinderBorder = androidx.compose.ui.graphics.Color(0xFF3B6051)
private val FinderGradient = Modifier.background(
    Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF20382D), Graphite))
)

@Composable
private fun CalendarButton(date: LocalDate, busy: Boolean, open: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = open, enabled = !busy, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Filled.DateRange, null, modifier = Modifier.size(18.dp), tint = Silver)
        Text("${formatDay(date)} · МСК", modifier = Modifier.padding(start = 8.dp), color = White)
    }
}

@Composable
private fun SportFilters(sport: Sport?, onSelected: (Sport?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(selected = sport == null, onClick = { onSelected(null) }, label = { Text("Все") })
        Sport.entries.forEach { item ->
            FilterChip(selected = item == sport, onClick = { onSelected(item) }, label = { Text(item.label) })
        }
    }
}

@Composable
internal fun EventCard(event: Event, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp), color = Panel, border = BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.sport.label, color = PaleGreen, style = MaterialTheme.typography.labelMedium)
                Text(event.title, color = White, style = MaterialTheme.typography.titleMedium)
                Text(event.tournament, color = Silver, style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(formatTime(event.kickoff), fontFamily = digits, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("МСК", color = Silver, fontSize = 11.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Silver, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun CoverageCard(coverage: List<Coverage>) {
    PanelCard {
        Text("Охват источников", style = MaterialTheme.typography.titleMedium)
        coverage.forEach { item ->
            Text("${item.sport.label} · ${item.source}", color = if (item.status == "available" || item.status == "demo") PaleGreen else Amber)
            Text(item.details, color = Silver, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoscowDatePicker(date: LocalDate, close: () -> Unit, select: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val ruConfiguration = remember(configuration) {
        Configuration(configuration).apply { setLocales(LocaleList(Locale.forLanguageTag("ru-RU"))) }
    }
    val russianContext = remember(context, ruConfiguration) { context.createConfigurationContext(ruConfiguration) }
    CompositionLocalProvider(LocalContext provides russianContext, LocalConfiguration provides ruConfiguration) {
        val calendar = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = close,
            confirmButton = {
                TextButton(
                    enabled = calendar.selectedDateMillis != null,
                    onClick = {
                        calendar.selectedDateMillis?.let { select(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) }
                    }
                ) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Отмена") } }
        ) {
            DatePicker(state = calendar, showModeToggle = false)
        }
    }
}
