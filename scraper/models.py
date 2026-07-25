"""
Java(core) 포트와 매핑되는 스크래핑 계약 스키마.

이 서비스의 HTTP 응답(ScrapeResult)이 Java 어댑터(ScraplingSourcingClient)에서
core 도메인으로 매핑되는 경계다. 필드는 core의 ScrapedProductDto / StockCheckResult
부분집합에 맞춰 벤더 중립으로 둔다.

- price/inStock  -> StockCheckResult(costPrice, status=IN_STOCK/OUT_OF_STOCK)
- name/currency/sku 등 -> ScrapedProductDto 보강용
"""
from __future__ import annotations

from typing import Optional
from pydantic import BaseModel, Field


class ScrapeRequest(BaseModel):
    url: str = Field(..., description="상품 상세 페이지 URL")
    vendor: Optional[str] = Field(None, description="VendorType 코드(FTN 등). 라우팅/기록용")


class ScrapeResult(BaseModel):
    ok: bool
    # 결과 구분 — Java 배치가 이걸로 분기:
    #   ok        : 정상(가격·재고 반영)
    #   not_found : 링크 소멸(404) → 품절 처리(가격 미변경)
    #   blocked   : Cloudflare/봇차단 의심 → 스킵+추적(재고 건드리지 않음!)
    #   error     : 기타 실패 → 스킵+추적
    status: str = "ok"
    httpStatus: Optional[int] = None
    sourceUrl: str
    vendor: Optional[str] = None
    name: Optional[str] = None
    price: Optional[float] = None          # 표시 통화 기준 금액 (예: 7.95)
    currency: Optional[str] = None         # ISO 4217 (예: GBP)
    inStock: Optional[bool] = None
    availabilityText: Optional[str] = None # 원문 재고 문구(디버깅/근거)
    weightGrams: Optional[float] = None    # 상품 표기 무게(g). 배대지 배송비 계산 입력. 불명 시 None
    sku: Optional[str] = None
    # 원가 산출(status=ok일 때) — Java 배치의 buyPrice(원) 입력. costKrw = (price£ + shipping£) × fx
    costKrw: Optional[int] = None
    fxGbpKrw: Optional[float] = None
    shippingGbp: Optional[float] = None
    landedGbp: Optional[float] = None
    scrapedAt: str = ""
    error: Optional[str] = None
