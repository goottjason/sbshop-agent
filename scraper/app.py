"""sbshop-scraper — Scrapling 기반 소싱 스크래핑 마이크로서비스.

Java(core)의 ProductStockCrawlerPort/ProductInfoCrawlerPort 뒤에 붙는 어댑터
(ScraplingSourcingClient)가 이 HTTP API를 호출한다. 벤더별 스크래퍼는 registry로
URL 라우팅(현재 Fortnum & Mason). 향후 iHerb 등 편입.

실행:  ./.venv/bin/uvicorn app:app --host 0.0.0.0 --port 8099
"""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import FastAPI

from models import (
    CandidateCard, DiscoverFailure, DiscoverRequest, DiscoverResult,
    ProductDetail, ScrapeRequest, ScrapeResult,
)
from scrapers.base import VendorScraper
from scrapers.fortnum import FortnumScraper
from scrapers import iherb as iherb_mod
from scrapers.iherb import IherbScraper
from scrapers import vitabiotics as vtb_mod
from scrapers.vitabiotics_stock import VitabioticsScraper
from scrapers.jsonld import CostcoUkScraper, OcadoScraper, TescoScraper
from pricing import landed_cost_krw

# 벤더 스크래퍼 레지스트리 — supports(url)로 첫 매칭 사용(Java SourcingAgentFactory와 대칭).
SCRAPERS: list[VendorScraper] = [
    FortnumScraper(),
    IherbScraper(),
    VitabioticsScraper(),
    OcadoScraper(),
    CostcoUkScraper(),
    TescoScraper(),
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
    # kr.iherb.com처럼 이미 원화로 표기되는 벤더는 환산 대상이 아니다(스크래퍼가 goodsKrw를 채운다).
    if result.currency == "KRW":
        return result
    if result.status == "ok" and result.price is not None:
        try:
            cb = landed_cost_krw(result.price, result.weightGrams)
            result.goodsKrw = cb.goods_krw
            result.shippingKrw = cb.shipping_krw
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


@app.post("/discover/bestsellers", response_model=DiscoverResult)
def discover_bestsellers(req: DiscoverRequest) -> DiscoverResult:
    """카테고리별 베스트셀러를 긁어 후보 카드를 반환한다(신규 상품 등록 S0).

    페이지 단위 실패는 조용히 삼키지 않고 failures에 담아 반환한다 — 일부 카테고리가
    차단당했는데 "후보가 적다"로 오인하면 추천 품질이 조용히 무너진다.
    """
    cards: list[CandidateCard] = []
    failures: list[DiscoverFailure] = []
    seen: set[str] = set()

    for slug in req.categories:
        for page in range(1, req.pages + 1):
            page_cards, err = iherb_mod.fetch_bestsellers(slug, page)
            if err:
                failures.append(DiscoverFailure(categorySlug=slug, page=page, reason=err))
                # 첫 페이지부터 막히면 뒷 페이지도 막힐 가능성이 높다 → 해당 카테고리 조기 중단.
                if page == 1:
                    break
                continue
            for c in page_cards:
                # 같은 상품이 여러 카테고리에 걸쳐 나온다 — 첫 등장(상위 랭킹)만 남긴다.
                key = f"{c.vendor}:{c.externalId}"
                if key in seen:
                    continue
                seen.add(key)
                cards.append(c)

    return DiscoverResult(
        ok=bool(cards),
        cards=cards,
        failures=failures,
        scrapedAt=datetime.now(timezone.utc).isoformat(),
    )


@app.post("/scrape/product-detail", response_model=ProductDetail)
def scrape_product_detail(req: ScrapeRequest) -> ProductDetail:
    """상품 상세 크롤 — 성분(통관 게이트) · 중량 · UPC · 이미지 · 사용법/주의사항."""
    if iherb_mod.IherbScraper().supports(req.url):
        return iherb_mod.fetch_detail(req.url)
    if vtb_mod.supports(req.url):
        return vtb_mod.fetch_detail(req.url)
    return ProductDetail(
        ok=False, status="error", sourceUrl=req.url,
        error="상세 크롤을 지원하지 않는 URL입니다(현재 iHerb·vitabiotics만 지원).",
        scrapedAt=datetime.now(timezone.utc).isoformat(),
    )
