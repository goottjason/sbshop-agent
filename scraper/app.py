"""sbshop-scraper — Scrapling 기반 소싱 스크래핑 마이크로서비스.

Java(core)의 ProductStockCrawlerPort/ProductInfoCrawlerPort 뒤에 붙는 어댑터
(ScraplingSourcingClient)가 이 HTTP API를 호출한다. 벤더별 스크래퍼는 registry로
URL 라우팅(현재 Fortnum & Mason). 향후 iHerb 등 편입.

실행:  ./.venv/bin/uvicorn app:app --host 0.0.0.0 --port 8099
"""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import FastAPI

from models import ScrapeRequest, ScrapeResult
from scrapers.base import VendorScraper
from scrapers.fortnum import FortnumScraper
from pricing import landed_cost_krw

# 벤더 스크래퍼 레지스트리 — supports(url)로 첫 매칭 사용(Java SourcingAgentFactory와 대칭).
SCRAPERS: list[VendorScraper] = [
    FortnumScraper(),
    # 향후: IherbScraper(), AmazonScraper(), ...
]

app = FastAPI(title="sbshop-scraper", version="0.1.0")


def _dispatch(url: str) -> VendorScraper | None:
    for s in SCRAPERS:
        if s.supports(url):
            return s
    return None


@app.get("/health")
def health() -> dict:
    return {"ok": True, "scrapers": [s.vendor for s in SCRAPERS]}


@app.post("/scrape/stock-price", response_model=ScrapeResult)
def scrape_stock_price(req: ScrapeRequest) -> ScrapeResult:
    scraper = _dispatch(req.url)
    if scraper is None:
        return ScrapeResult(
            ok=False, status="error", sourceUrl=req.url, vendor=req.vendor,
            error="지원하는 스크래퍼가 없는 URL입니다.",
            scrapedAt=datetime.now(timezone.utc).isoformat(),
        )
    result = scraper.scrape(req.url)
    # status=ok면 원가(원) 산출을 응답에 첨부(FX·배송비 포함). 실패해도 스크랩 결과는 유지.
    if result.status == "ok" and result.price is not None:
        try:
            cb = landed_cost_krw(result.price, result.weightGrams)
            result.costKrw = cb.cost_krw
            result.fxGbpKrw = cb.fx_gbp_krw
            result.shippingGbp = cb.shipping_gbp
            result.landedGbp = cb.landed_gbp
        except Exception as e:  # noqa: BLE001
            # FX 실패 시 costKrw 없이 반환 → Java가 error로 스킵(오품절/오가격 방지)
            result.ok = False
            result.status = "error"
            result.error = f"원가 산출 실패(FX): {e}"
    return result
