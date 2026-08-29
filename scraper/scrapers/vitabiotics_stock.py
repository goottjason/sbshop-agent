"""Vitabiotics 재고/가격 스크래퍼 (D-239).

Vitabiotics 는 Shopify 스토어라 `/products/{handle}.js` 가 가격·재고·무게를
JSON 으로 준다. HTML 렌더링이 필요 없어 F&M(DynamicFetcher)보다 훨씬 싸고 안정적이다.

상세 스크래퍼(vitabiotics.py)와 같은 엔드포인트를 쓰되, 이쪽은 재고/가격만 본다.

**모르면 모른다고 답한다.** 가격·재고를 확정할 수 없으면 status=error 로 돌려보내
Java 배치가 재고를 건드리지 않게 한다(품절로 단정하지 않는다).
"""
from __future__ import annotations

from datetime import datetime, timezone

from models import ScrapeResult
from scrapers.base import VendorScraper
from scrapers.vitabiotics import (
    _get, product_json_url, requested_variant_id, select_variant,
)

import json


class VitabioticsScraper(VendorScraper):
    vendor = "VTB"

    def supports(self, url: str) -> bool:
        return bool(url) and "vitabiotics.com" in url.lower()

    def scrape(self, url: str) -> ScrapeResult:
        now = datetime.now(timezone.utc).isoformat()
        api = product_json_url(url)
        if api is None:
            return ScrapeResult(ok=False, status="error", sourceUrl=url, vendor=self.vendor,
                                error="vitabiotics 상품 URL 형식이 아님(/products/{handle} 없음)",
                                scrapedAt=now)

        http, body = _get(api)
        if http == 404:
            return ScrapeResult(ok=False, status="not_found", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor, error="상품 페이지 없음(404)", scrapedAt=now)
        if http == 429:
            return ScrapeResult(ok=False, status="blocked", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor, error="요청 한도 초과(429)", scrapedAt=now)
        if http != 200 or not body:
            return ScrapeResult(ok=False, status="error", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor,
                                error="상품 JSON 응답 실패(HTTP %s)" % http, scrapedAt=now)

        try:
            payload = json.loads(body)
        except ValueError as e:
            return ScrapeResult(ok=False, status="error", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor, error="상품 JSON 파싱 실패: %s" % e,
                                scrapedAt=now)

        variant, reason = select_variant(payload, requested_variant_id(url))
        if variant is None:
            return ScrapeResult(ok=False, status="error", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor, error=reason, scrapedAt=now)

        pence = variant.get("price")
        if pence is None:
            return ScrapeResult(ok=False, status="error", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor,
                                error="variant 에 price 가 없음 — 가격 확정 불가", scrapedAt=now)
        available = variant.get("available")
        if available is None:
            return ScrapeResult(ok=False, status="error", httpStatus=http, sourceUrl=url,
                                vendor=self.vendor,
                                error="variant 에 available 이 없음 — 재고 판정 불가", scrapedAt=now)

        grams = variant.get("grams")
        weight = float(grams) if grams else None

        return ScrapeResult(
            ok=True, status="ok", httpStatus=http, sourceUrl=url, vendor=self.vendor,
            name=payload.get("title"),
            price=float(pence) / 100.0, currency="GBP",
            inStock=bool(available),
            availabilityText="available" if available else "sold out",
            weightGrams=weight,
            sku=variant.get("sku"),
            scrapedAt=now,
        )
