import os
from collections.abc import Callable, Sequence
from datetime import UTC, date, datetime
from typing import Annotated

from fastapi import FastAPI, HTTPException, Path, Query

from .analytics import market_metrics
from .models import Discovery, Event, Health, MarketMetrics, MarketReview, Mode, Sport
from .providers import FootballDataProvider, Provider, coverage_for, demo_discover, real_discover


def create_app(
    providers: Sequence[Provider] | None = None,
    clock: Callable[[], datetime] = lambda: datetime.now(UTC),
) -> FastAPI:
    if providers is None:
        providers = (FootballDataProvider(os.getenv("FOOTBALL_DATA_TOKEN", "")),)
    app = FastAPI(title="ODDYN API", version="1.0")

    @app.get("/v1/health", response_model=Health)
    def health() -> Health:
        return Health(coverage=coverage_for(providers))

    @app.get("/v1/events", response_model=Discovery)
    def events(
        day: Annotated[date, Query(alias="date")], mode: Mode = Mode.REAL, sport: Sport | None = None,
    ) -> Discovery:
        if mode == Mode.DEMO:
            return demo_discover(day, sport)
        return real_discover(day, providers, clock(), sport)

    @app.get("/v1/events/{event_id}", response_model=Event)
    def event(
        event_id: Annotated[str, Path(min_length=1, max_length=120)],
        day: Annotated[date, Query(alias="date")], mode: Mode = Mode.REAL,
    ) -> Event:
        match = next((item for item in events(day, mode).events if item.id == event_id), None)
        if match is None:
            raise HTTPException(status_code=404, detail="Событие в указанном расписании не найдено.")
        return match

    @app.post("/v1/analytics/market", response_model=MarketMetrics)
    def review_market(request: MarketReview) -> MarketMetrics:
        return market_metrics(request.market, request.mode, clock())

    return app


app = create_app()
