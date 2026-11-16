import re
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation, localcontext
from urllib.parse import urlsplit

from .models import Evidence, Market, MarketMetrics, Mode

ODDS_PATTERN = re.compile(r"[0-9]{1,6}(?:[.,][0-9]{1,6})?")
PROBABILITY_PATTERN = re.compile(r"(?:0(?:\.[0-9]{1,20})?|1(?:\.0{1,20})?)")
QUOTE_LIFETIME = timedelta(hours=1)


def public_source(url: str | None) -> bool:
    try:
        parts = urlsplit(url or "")
        return parts.scheme == "https" and bool(parts.hostname) and parts.username is None
    except ValueError:
        return False


def time_checked(source: Evidence, now: datetime) -> bool:
    return source.checked_at is not None and source.checked_at <= now


def market_metrics(market: Market, mode: Mode, now: datetime) -> MarketMetrics:
    odds = None
    quote = market.quote
    if quote and quote.bookmaker.strip() and quote.source.name.strip():
        source = quote.source
        valid_demo = mode == Mode.DEMO and source.kind == "demo" and source.checked_at is not None
        valid_real = (
            mode == Mode.REAL and source.kind == "odds" and public_source(source.url)
            and time_checked(source, now) and source.checked_at + QUOTE_LIFETIME >= now
        )
        raw = quote.decimal.strip()
        if (valid_demo or valid_real) and ODDS_PATTERN.fullmatch(raw):
            decimal = Decimal(raw.replace(",", "."))
            if decimal > 1:
                odds = decimal

    probability = None
    model = market.model
    if (
        mode == Mode.REAL and model and model.method.strip() and model.validation.strip()
        and model.sample_size > 0 and bool(model.evidence)
        and all(
            item.kind != "demo" and item.name.strip() and public_source(item.url)
            and time_checked(item, now)
            for item in model.evidence
        )
        and PROBABILITY_PATTERN.fullmatch(model.value)
    ):
        try:
            probability = Decimal(model.value)
        except InvalidOperation:
            pass

    binary = market.settlement == "binary"
    with localcontext() as ctx:
        ctx.prec = 34
        return MarketMetrics(
            break_even=str(1 / odds) if binary and odds is not None else None,
            model_probability=str(probability) if probability is not None else None,
            expected_value=str(probability * odds - 1) if binary and probability is not None and odds is not None else None,
        )
