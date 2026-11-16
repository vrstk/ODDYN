from datetime import date, datetime
from enum import StrEnum
from typing import Literal

from pydantic import AwareDatetime, BaseModel, Field


class Mode(StrEnum):
    DEMO = "demo"
    REAL = "real"


class Sport(StrEnum):
    FOOTBALL = "football"
    HOCKEY = "hockey"
    TENNIS = "tennis"


class Evidence(BaseModel):
    name: str
    kind: str
    url: str | None = None
    checked_at: AwareDatetime | None = None


class Quote(BaseModel):
    decimal: str
    bookmaker: str
    source: Evidence


class Probability(BaseModel):
    value: str
    method: str
    sample_size: int
    validation: str
    evidence: list[Evidence]


class Market(BaseModel):
    id: str
    family: str
    label: str
    selection: str
    line: str | None = None
    period: str
    rules: str
    settlement: Literal["binary", "push", "split", "unknown"]
    exclusive_group: str | None = None
    quote: Quote | None = None
    model: Probability | None = None
    rationale: list[str] = Field(default_factory=list)
    risk: str


class Event(BaseModel):
    id: str
    mode: Mode
    sport: Sport
    tournament: str
    home: str
    away: str
    starts_at: AwareDatetime
    sources: list[Evidence]
    facts: list[str] = Field(default_factory=list)
    markets: list[Market] = Field(default_factory=list)


class Coverage(BaseModel):
    sport: Sport
    source: str
    status: Literal["demo", "available", "disabled", "unavailable", "rate_limited"]
    details: str


class Discovery(BaseModel):
    api_version: Literal[1] = 1
    date: date
    mode: Mode
    events: list[Event]
    coverage: list[Coverage]


class Health(BaseModel):
    api_version: Literal[1] = 1
    status: Literal["ok"] = "ok"
    coverage: list[Coverage]


class DemoFixture(BaseModel):
    reference_date: date
    events: list[Event]
    coverage: list[Coverage]


class MarketReview(BaseModel):
    mode: Mode
    market: Market


class MarketMetrics(BaseModel):
    break_even: str | None = None
    model_probability: str | None = None
    expected_value: str | None = None


class ProviderResult(BaseModel):
    events: list[Event]
    coverage: Coverage
