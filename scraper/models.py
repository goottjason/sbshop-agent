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


class DiscoverRequest(BaseModel):
    """베스트셀러 후보 발굴 요청 — Java SourcingDiscoveryUseCase(S0)가 호출."""

    categories: list[str] = Field(..., description="iHerb 카테고리 slug (supplements, grocery, ...)")
    pages: int = Field(3, ge=1, le=20, description="카테고리당 크롤 페이지 수")
    vendor: str = Field("IHB", description="VendorType 코드")


class CandidateCard(BaseModel):
    """목록 페이지 카드 1장에서 뽑은 후보. 스코어링에 필요한 신호가 전부 여기 있다."""

    vendor: str = "IHB"
    externalId: str
    sourceUrl: str
    partNumber: Optional[str] = None
    brand: Optional[str] = None
    brandCode: Optional[str] = None
    nameKo: Optional[str] = None
    categorySlug: Optional[str] = None
    currency: str = "KRW"
    listPrice: Optional[float] = None       # 정가(원)
    discountPrice: Optional[float] = None   # 실제 구매가(원) — 원가 산정 기준
    discountPct: Optional[int] = None
    rating: Optional[float] = None
    reviewCount: Optional[int] = None
    sales30d: Optional[int] = None          # "30일 동안 N개 판매" 파싱값
    rankPosition: Optional[int] = None
    isOutOfStock: bool = False
    isDiscontinued: bool = False
    isSponsored: bool = False
    imageUrl: Optional[str] = None


class DiscoverFailure(BaseModel):
    categorySlug: str
    page: int
    reason: str


class DiscoverResult(BaseModel):
    ok: bool
    cards: list[CandidateCard] = []
    failures: list[DiscoverFailure] = []
    scrapedAt: str = ""


class ProductDetail(BaseModel):
    """상세 페이지 크롤 결과 — 통관 게이트(성분)와 마켓 필수필드(중량·바코드·수량)의 원천."""

    ok: bool
    status: str = "ok"                      # ok / not_found / blocked / error
    httpStatus: Optional[int] = None
    sourceUrl: str
    externalId: Optional[str] = None
    nameKo: Optional[str] = None
    brandKo: Optional[str] = None           # "California Gold Nutrition (캘리포니아골드뉴트리션)"
    brandCode: Optional[str] = None
    rootCategory: Optional[str] = None      # "보충제"
    rootCategoryId: Optional[str] = None
    isDiscontinued: bool = False
    partNumber: Optional[str] = None
    upc: Optional[str] = None               # 바코드
    priceKrw: Optional[float] = None
    listPriceKrw: Optional[float] = None
    currency: str = "KRW"
    inStock: Optional[bool] = None
    shippingWeightGrams: Optional[float] = None
    packageQuantity: Optional[int] = None   # "180 개"
    dimensions: Optional[str] = None
    ingredientsRaw: Optional[str] = None    # 성분 원문(한글) — 반입차단 대조 입력
    mainIngredients: Optional[str] = None
    otherIngredients: Optional[str] = None
    description: Optional[str] = None       # 제품소개
    usage: Optional[str] = None             # 사용법
    caution: Optional[str] = None           # 주의사항
    images: list[str] = []                  # 원본(/l/) 이미지 URL
    specs: dict = {}
    scrapedAt: str = ""
    error: Optional[str] = None


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
    # 원가 산출(status=ok일 때). goodsKrw=상품원가(묶음수량 곱 대상), shippingKrw=배송비(주문당 1회).
    goodsKrw: Optional[int] = None
    shippingKrw: Optional[int] = None
    costKrw: Optional[int] = None      # goods+shipping (참고/표시)
    fxGbpKrw: Optional[float] = None
    shippingGbp: Optional[float] = None
    landedGbp: Optional[float] = None
    scrapedAt: str = ""
    error: Optional[str] = None
