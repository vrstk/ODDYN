from datetime import UTC, datetime
from decimal import Decimal
from urllib.error import HTTPError

from fastapi.testclient import TestClient

from oddyn.main import create_app
from oddyn.providers import FootballDataProvider

NOW = datetime(2026, 11, 16, 18, tzinfo=UTC)
DAY = "2026-11-17"


def client(*providers):
    return TestClient(create_app(providers, clock=lambda: NOW))


def row(match_id, stamp, status="TIMED"):
    return {
        "id": match_id, "utcDate": stamp, "status": status,
        "competition": {"code": "PD", "name": "Primera Division"},
        "homeTeam": {"name": "Real Madrid CF"}, "awayTeam": {"name": "FC Barcelona"},
    }


def test_demo_is_explicit_and_shifts_moscow_wall_time():
    api = client()
    response = api.get("/v1/events", params={"date": DAY, "mode": "demo", "sport": "hockey"})
    assert response.status_code == 200
    (event,) = response.json()["events"]
    assert event["starts_at"] == "2026-11-17T16:15:00Z"
    assert event["mode"] == "demo"
    assert event["sources"][0]["kind"] == "demo"
    detail = api.get(f"/v1/events/{event['id']}", params={"date": DAY, "mode": "demo"})
    assert detail.json() == event
    assert api.get(f"/v1/events/{event['id']}", params={"date": DAY}).status_code == 404


def test_real_mode_reports_disabled_sources_and_empty_result():
    api = client(FootballDataProvider(""))
    assert all(source["status"] == "disabled" for source in api.get("/v1/health").json()["coverage"])
    real = api.get("/v1/events", params={"date": DAY}).json()
    assert real["mode"] == "real" and real["events"] == []
    assert api.get("/v1/events", params={"date": "bad_date"}).status_code == 422


def test_provider_uses_moscow_day_and_confirmed_start_time():
    requests = []

    def fetch(request):
        requests.append(request)
        return {"matches": [
            row(1, "2026-11-16T21:15:00Z"),
            row(2, "2026-11-17T20:59:00Z"),
            row(3, "2026-11-17T21:00:00Z"),
            row(4, "2026-11-17T12:00:00Z", "SCHEDULED"),
            row(5, "2026-11-16T20:59:00Z"),
            row(6, "2026-11-17T15:00:00Z", "POSTPONED"),
            row(7, "2026-11-17T16:00:00"),
        ]}

    api = client(FootballDataProvider("unit-test", fetch=fetch))
    events = api.get("/v1/events", params={"date": DAY}).json()["events"]
    assert [event["id"] for event in events] == ["fd-1", "fd-2"]
    first = events[0]
    assert first["home"] == "Реал Мадрид" and first["tournament"] == "Ла Лига"
    assert first["markets"] == []
    assert first["sources"][0]["url"] == "https://api.football-data.org/v4/matches/1"
    assert first["sources"][0]["checked_at"] == "2026-11-16T18:00:00Z"
    assert "dateFrom=2026-11-16&dateTo=2026-11-18" in requests[0].full_url
    assert api.get("/v1/events/fd-1", params={"date": DAY}).status_code == 200
    assert len(requests) == 1


def test_rate_limit_does_not_insert_demo_events():
    def fetch(request):
        raise HTTPError(request.full_url, 429, "rate limited", {}, None)

    response = client(FootballDataProvider("unit-test", fetch=fetch)).get(
        "/v1/events", params={"date": DAY, "sport": "football"}
    ).json()
    assert response["events"] == [] and response["coverage"][0]["status"] == "rate_limited"


def test_market_math_needs_evidence_and_binary_settlement():
    api = client()
    market = api.get("/v1/events", params={"date": DAY, "mode": "demo"}).json()["events"][0]["markets"][0]
    demo = api.post("/v1/analytics/market", json={"mode": "demo", "market": market}).json()
    assert Decimal(demo["break_even"]).quantize(Decimal("0.0001")) == Decimal("0.5556")
    assert demo["expected_value"] is None
    assert api.post("/v1/analytics/market", json={"mode": "real", "market": market}).json()["break_even"] is None
    market["quote"] = {
        "bookmaker": "Тестовая БК", "decimal": "2.00",
        "source": {"kind": "odds", "name": "Тест", "checked_at": NOW.isoformat(), "url": "https://example.org/quote"},
    }
    market["model"] = {
        "value": "0.55", "method": "Тест модели", "sample_size": 20, "validation": "Тестовая выборка",
        "evidence": [market["quote"]["source"]],
    }
    confirmed = api.post("/v1/analytics/market", json={"mode": "real", "market": market}).json()
    assert Decimal(confirmed["expected_value"]) == Decimal("0.10")
    market["settlement"] = "push"
    refunded = api.post("/v1/analytics/market", json={"mode": "real", "market": market}).json()
    assert refunded["expected_value"] is None and refunded["break_even"] is None
