package com.oddyn.app.data

import android.content.Context
import com.oddyn.app.BuildConfig
import com.oddyn.app.domain.DemoFixture
import com.oddyn.app.domain.Discovery
import com.oddyn.app.domain.Event
import com.oddyn.app.domain.Evidence
import com.oddyn.app.domain.Health
import com.oddyn.app.domain.Mode
import com.oddyn.app.domain.Moscow
import com.oddyn.app.domain.isPublicSource
import com.oddyn.app.domain.moscowDate
import com.oddyn.app.domain.oddynJson
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

interface ScheduleProvider {
    suspend fun discover(day: LocalDate): Discovery
}

class DemoSchedule(context: Context) : ScheduleProvider {
    private val assets = context.applicationContext.assets
    private val fixture by lazy {
        assets.open("demo.json").bufferedReader().use { oddynJson.decodeFromString<DemoFixture>(it.readText()) }
    }

    override suspend fun discover(day: LocalDate): Discovery = withContext(Dispatchers.IO) {
        fun shift(stamp: String): String {
            val time = Instant.parse(stamp).atZone(Moscow).toLocalTime()
            return day.atTime(time).atZone(Moscow).toInstant().toString()
        }
        fun evidence(source: Evidence): Evidence =
            source.copy(checkedAt = source.checkedAt?.let(::shift))

        val events = fixture.events.map { event ->
            event.copy(
                id = "demo-$day-${event.id}",
                startsAt = shift(event.startsAt),
                sources = event.sources.map(::evidence),
                markets = event.markets.map { market ->
                    market.copy(quote = market.quote?.let { it.copy(source = evidence(it.source)) })
                }
            )
        }
        Discovery(date = day.toString(), mode = Mode.DEMO, events = events, coverage = fixture.coverage)
    }
}

class SourceUnavailable(message: String) : IOException(message)

fun serverAddress(value: String): HttpUrl? {
    val url = value.trim().toHttpUrlOrNull() ?: return null
    val localDebug = BuildConfig.DEBUG && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
    return url.takeIf {
        (it.isHttps || localDebug) && it.username.isEmpty() && it.password.isEmpty() &&
            it.query == null && it.fragment == null
    }
}

class ServerSchedule(address: String) : ScheduleProvider {
    private val base = serverAddress(address) ?: throw SourceUnavailable("Укажите адрес сервера в настройках.")

    override suspend fun discover(day: LocalDate): Discovery {
        val url = endpoint("events").addQueryParameter("date", day.toString())
            .addQueryParameter("mode", "real").build()
        val result: Discovery = request(url)
        if (result.apiVersion != 1 || result.date != day.toString() || result.mode != Mode.REAL) {
            throw SourceUnavailable("Ответ сервера не соответствует запросу и версии API ODDYN.")
        }
        val now = Instant.now()
        return result.copy(events = result.events.filter { event -> event.acceptable(day, now) }
            .distinctBy(Event::id).sortedBy(Event::startsAt))
    }

    suspend fun health(): Health {
        val status: Health = request(endpoint("health").build())
        if (status.apiVersion != 1 || status.status != "ok") {
            throw SourceUnavailable("Сервер не подтвердил совместимость с ODDYN API v1.")
        }
        return status
    }

    private fun endpoint(name: String) = base.newBuilder()
        .addPathSegments("v1/$name")

    private suspend inline fun <reified T> request(url: HttpUrl): T = withContext(Dispatchers.IO) {
        try {
            val query = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(query).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SourceUnavailable("Сервер вернул HTTP ${response.code}. Проверьте адрес и состояние API.")
                }
                val source = response.body?.source() ?: throw SourceUnavailable("Ответ сервера пуст.")
                if (source.request(4L * 1024 * 1024 + 1)) {
                    throw SourceUnavailable("Ответ сервера превышает допустимый размер.")
                }
                oddynJson.decodeFromString<T>(source.readUtf8())
            }
        } catch (exception: SourceUnavailable) {
            throw exception
        } catch (_: SocketTimeoutException) {
            throw SourceUnavailable("Сервер не ответил вовремя. Повторите попытку.")
        } catch (_: IOException) {
            throw SourceUnavailable("Сервер недоступен. Проверьте интернет, адрес и HTTPS.")
        } catch (_: SerializationException) {
            throw SourceUnavailable("Сервер вернул данные неизвестного формата.")
        }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}

private val eventId = Regex("[a-zA-Z0-9_:-]{1,120}")

private fun Event.acceptable(day: LocalDate, now: Instant): Boolean {
    val start = runCatching { kickoff }.getOrNull() ?: return false
    return mode == Mode.REAL && eventId.matches(id) && start > now && moscowDate(start) == day &&
        home.isNotBlank() && away.isNotBlank() && tournament.isNotBlank() &&
        markets.all { eventId.matches(it.id) } && markets.distinctBy { it.id }.size == markets.size &&
        sources.any {
            val checked = it.checkedAt?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
            it.kind == "schedule" && it.name.isNotBlank() && isPublicSource(it.url) && checked != null && checked <= now
        } && sources.none { it.kind == "demo" }
}
