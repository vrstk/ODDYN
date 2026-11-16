package com.oddyn.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oddyn.app.domain.Mode
import com.oddyn.app.domain.Moscow
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val Graphite = Color(0xFF0B1011)
internal val Panel = Color(0xFF172120)
internal val StrongGreen = Color(0xFF31D158)
internal val PaleGreen = Color(0xFF84F19D)
internal val Silver = Color(0xFFB7C5C0)
internal val White = Color(0xFFF1F6F2)
internal val Amber = Color(0xFFFFBD59)
internal val Border = Color(0xFF344641)
internal val Red = Color(0xFFEE6767)
private val Ink = Color(0xFF0A210F)

private val colors = darkColorScheme(
    primary = StrongGreen,
    onPrimary = Ink,
    primaryContainer = Color(0xFF203D29),
    onPrimaryContainer = PaleGreen,
    secondary = PaleGreen,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF26342F),
    onSecondaryContainer = White,
    background = Graphite,
    onBackground = White,
    surface = Panel,
    onSurface = White,
    surfaceVariant = Color(0xFF27332F),
    onSurfaceVariant = Silver,
    outline = Border,
    outlineVariant = Border,
    error = Red
)

private val type = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 31.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

internal val digits = FontFamily.Monospace

@Composable
fun OddynTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, content = content)

@Composable
internal fun PageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("page-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Panel,
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun Heading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = White)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Silver) }
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = White)
}

@Composable
internal fun Notice(title: String, text: String, warning: Boolean = false) {
    PanelCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (warning) Amber else White)
        Text(text, color = Silver, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ModeTag(mode: Mode) {
    val tint = if (mode == Mode.DEMO) Amber else PaleGreen
    Row(
        Modifier.background(tint.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(mode.label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

private val ru = Locale.forLanguageTag("ru-RU")
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", ru).withZone(Moscow)
private val stampFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm 'МСК'", ru).withZone(Moscow)
private val dayFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)

internal fun formatTime(time: Instant): String = clockFormat.format(time)
internal fun formatStamp(time: Instant): String = stampFormat.format(time)
internal fun formatStamp(raw: String): String? = runCatching { formatStamp(Instant.parse(raw)) }.getOrNull()
internal fun formatDay(date: LocalDate): String = dayFormat.format(date)

internal fun coefficient(number: BigDecimal): String {
    val normalized = number.stripTrailingZeros()
    return if (normalized.precision() - normalized.scale() > 12) {
        normalized.round(java.math.MathContext(5, RoundingMode.HALF_UP)).toEngineeringString().replace('.', ',')
    } else normalized.setScale(normalized.scale().coerceIn(2, 6), RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}

internal fun percent(number: BigDecimal?): String = number?.movePointRight(2)
    ?.setScale(1, RoundingMode.HALF_UP)?.toPlainString()?.replace('.', ',')?.plus("%") ?: "—"

internal fun rubles(value: BigDecimal): String = NumberFormat.getCurrencyInstance(ru).format(value)
