import json
import logging
import time
from collections.abc import Callable, Iterable
from datetime import UTC, date, datetime, timedelta
from functools import lru_cache
from pathlib import Path
from threading import Lock
from typing import Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

from .models import Coverage, DemoFixture, Discovery, Event, Evidence, Mode, ProviderResult, Sport

MOSCOW = ZoneInfo("Europe/Moscow")
logger = logging.getLogger(__name__)


class Provider(Protocol):
    sport: Sport

    def coverage(self) -> Coverage: ...
    def discover(self, day: date, now: datetime) -> ProviderResult: ...


@lru_cache(maxsize=1)
def demo_fixture() -> DemoFixture:
    path = Path(__file__).resolve().parents[2] / "shared" / "demo.json"
    return DemoFixture.model_validate_json(path.read_text(encoding="utf-8"))


def demo_discover(day: date, sport: Sport | None = None) -> Discovery:
    fixture = demo_fixture()

    def shift(timestamp: datetime) -> datetime:
        wall_time = timestamp.astimezone(MOSCOW).time()
        return datetime.combine(day, wall_time, MOSCOW).astimezone(UTC)

    def shift_evidence(source: Evidence) -> Evidence:
        stamp = shift(source.checked_at) if source.checked_at else None
        return source.model_copy(update={"checked_at": stamp})

    events = []
    for fixture_event in fixture.events:
        if sport and fixture_event.sport != sport:
            continue
        markets = []
        for market in fixture_event.markets:
            quote = market.quote
            if quote:
                quote = quote.model_copy(update={"source": shift_evidence(quote.source)})
            markets.append(market.model_copy(update={"quote": quote}))
        events.append(fixture_event.model_copy(update={
            "id": f"demo-{day}-{fixture_event.id}",
            "starts_at": shift(fixture_event.starts_at),
            "sources": [shift_evidence(source) for source in fixture_event.sources],
            "markets": markets,
        }))

    return Discovery(
        date=day, mode=Mode.DEMO, events=sorted(events, key=lambda event: event.starts_at),
        coverage=[item for item in fixture.coverage if sport is None or item.sport == sport],
    )


def missing_source(sport: Sport) -> Coverage:
    return Coverage(
        sport=sport, source="Поставщик не подключён", status="disabled",
        details="Для этого вида спорта пока нет настроенного источника расписания.",
    )


def coverage_for(providers: Iterable[Provider], sport: Sport | None = None) -> list[Coverage]:
    configured = {provider.sport: provider.coverage() for provider in providers}
    return [configured.get(kind, missing_source(kind)) for kind in Sport if sport is None or sport == kind]


def real_discover(day: date, providers: Iterable[Provider], now: datetime, sport: Sport | None) -> Discovery:
    coverage = {item.sport: item for item in coverage_for(providers, sport)}
    events: dict[str, Event] = {}
    if day >= now.astimezone(MOSCOW).date():
        for provider in providers:
            if sport is not None and provider.sport != sport:
                continue
            result = provider.discover(day, now)
            coverage[provider.sport] = result.coverage
            for event in result.events:
                if (
                    event.mode == Mode.REAL and event.sport == provider.sport
                    and event.starts_at > now and event.starts_at.astimezone(MOSCOW).date() == day
                ):
                    events[event.id] = event
    return Discovery(
        date=day, mode=Mode.REAL, events=sorted(events.values(), key=lambda event: event.starts_at),
        coverage=list(coverage.values()),
    )


FOOTBALL_ATTRIBUTION = "Football data provided by the Football-Data.org API"
COMPETITIONS = {
    "CL": "Лига чемпионов", "PL": "Английская Премьер-лига", "ELC": "Чемпионшип",
    "PD": "Ла Лига", "SA": "Серия А", "BL1": "Бундеслига", "FL1": "Лига 1",
    "DED": "Эредивизи", "PPL": "Примейра-лига", "BSA": "Серия А (Бразилия)",
    "WC": "Чемпионат мира", "EC": "Чемпионат Европы",
}
TEAMS = {
    "Real Madrid CF": "Реал Мадрид", "FC Barcelona": "Барселона", "FC Bayern München": "Бавария",
    "Liverpool FC": "Ливерпуль", "Arsenal FC": "Арсенал", "Manchester City FC": "Манчестер Сити",
    "Manchester United FC": "Манчестер Юнайтед", "Chelsea FC": "Челси", "Paris Saint-Germain FC": "Пари Сен-Жермен",
    "AC Milan": "Милан", "FC Internazionale Milano": "Интер", "Juventus FC": "Ювентус",
}
Fetch = Callable[[Request], dict]


def fetch_json(request: Request) -> dict:
    with urlopen(request, timeout=8) as response:
        cap = 4 * 1024 * 1024
        body = response.read(cap + 1)
        if len(body) > cap:
            raise ValueError("upstream response is too large")
    result = json.loads(body)
    if not isinstance(result, dict):
        raise ValueError("upstream response must be an object")
    return result


class FootballDataProvider:
    sport = Sport.FOOTBALL

    def __init__(self, token: str, fetch: Fetch = fetch_json, monotonic: Callable[[], float] = time.monotonic):
        self.token = token.strip()
        self.fetch = fetch
        self.monotonic = monotonic
        self.lock = Lock()
        self.cache: dict[date, tuple[float, ProviderResult]] = {}
        self.next_request = float("-inf")

    def coverage(self) -> Coverage:
        return Coverage(
            sport=self.sport, source=FOOTBALL_ATTRIBUTION,
            status="available" if self.token else "disabled",
            details=(
                "Поддерживаемые турниры Football-Data.org. В бесплатном тарифе возможна задержка. "
                "Котировки, составы и травмы не загружаются."
                if self.token else "На сервере не настроен бесплатный ключ Football-Data.org."
            ),
        )

    def discover(self, day: date, now: datetime) -> ProviderResult:
        status = self.coverage()
        if not self.token:
            return ProviderResult(events=[], coverage=status)

        with self.lock:
            clock = self.monotonic()
            self.cache = {key: entry for key, entry in self.cache.items() if entry[0] > clock}
            cached = self.cache.get(day)
            if cached:
                return cached[1]
            if clock < self.next_request:
                return self._failure(status, "rate_limited", "Повторите запрос чуть позже. Действует лимит источника.")

            self.next_request = clock + 6.1
            params = urlencode({"dateFrom": day - timedelta(days=1), "dateTo": day + timedelta(days=1)})
            url = f"https://api.football-data.org/v4/matches?{params}"
            request = Request(url, headers={"Accept": "application/json"})
            request.add_unredirected_header("X-Auth-Token", self.token)
            try:
                matches = self.fetch(request).get("matches")
                if not isinstance(matches, list):
                    raise ValueError("upstream response is missing matches")
                events = [event for row in matches if (event := self._parse(row, now)) is not None]
            except HTTPError as exc:
                logger.info("Football-Data schedule returned HTTP %s", exc.code)
                if exc.code == 429:
                    self.next_request = self.monotonic() + 60
                    return self._failure(status, "rate_limited", "Football-Data.org ограничил запросы. Повторите через минуту.")
                details = "Ключ Football-Data.org отклонён." if exc.code in (401, 403) else "Football-Data.org временно недоступен."
                return self._failure(status, "unavailable", details)
            except (URLError, OSError, ValueError, TypeError):
                logger.info("Football-Data schedule could not be read")
                return self._failure(status, "unavailable", "Football-Data.org не вернул пригодное расписание.")

            result = ProviderResult(events=events, coverage=status)
            self.cache[day] = (self.monotonic() + 60, result)
            return result

    @staticmethod
    def _failure(status: Coverage, code: str, details: str) -> ProviderResult:
        return ProviderResult(events=[], coverage=status.model_copy(update={"status": code, "details": details}))

    @staticmethod
    def _parse(row: object, checked_at: datetime) -> Event | None:
        if not isinstance(row, dict) or row.get("status") != "TIMED":
            return None
        try:
            match_id = row["id"]
            stamp = datetime.fromisoformat(row["utcDate"].replace("Z", "+00:00"))
            competition = row["competition"]
            home = row["homeTeam"]["name"]
            away = row["awayTeam"]["name"]
            if (
                type(match_id) is not int or match_id <= 0 or stamp.tzinfo is None
                or not isinstance(home, str) or not home.strip()
                or not isinstance(away, str) or not away.strip()
            ):
                return None
            code = competition["code"]
            tournament = COMPETITIONS.get(code) or competition["name"]
            if not isinstance(tournament, str) or not tournament.strip():
                return None
        except (KeyError, ValueError, TypeError, AttributeError, OverflowError):
            return None

        return Event(
            id=f"fd-{match_id}", mode=Mode.REAL, sport=Sport.FOOTBALL, tournament=tournament,
            home=TEAMS.get(home, home), away=TEAMS.get(away, away), starts_at=stamp.astimezone(UTC),
            sources=[Evidence(
                name=FOOTBALL_ATTRIBUTION, kind="schedule",
                url=f"https://api.football-data.org/v4/matches/{match_id}", checked_at=checked_at,
            )],
            facts=["Время начала опубликовано поставщиком расписания."],
        )
