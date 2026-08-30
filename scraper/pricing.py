"""F&M 원가(landed cost) 계산 — 구매원가(£) + 배대지 배송비(£) → GBP환율 → 원화.

배송비 규칙(사용자 확정): 0.5kg까지 10.5 GBP, 이후 0.5kg마다 +2 GBP.
무게 불명 시 10.5 GBP 고정.
GBP→KRW 최신환율: open.er-api.com (무료·무키, 일 1회 갱신).

⚠️ 기타비용 수식/환율 출처는 잠정. Java 배치에 태우기 전 실데이터 검증용.
"""
from __future__ import annotations

import json
import math
import urllib.request
from dataclasses import dataclass

BASE_SHIP_GBP = 10.5      # 0.5kg 까지
STEP_GBP = 2.0            # 추가 0.5kg 마다
STEP_KG = 0.5
FX_URL_TEMPLATE = "https://open.er-api.com/v6/latest/{base}"


def shipping_gbp(weight_grams: float | None) -> float:
    """배대지 배송비(£). 무게 불명/0이면 기본 10.5."""
    if not weight_grams or weight_grams <= 0:
        return BASE_SHIP_GBP
    kg = weight_grams / 1000.0
    if kg <= STEP_KG:
        return BASE_SHIP_GBP
    extra_steps = math.ceil((kg - STEP_KG) / STEP_KG)
    return BASE_SHIP_GBP + STEP_GBP * extra_steps


import time

_FX_CACHE: dict[str, dict] = {}
_FX_TTL_S = 3600.0  # open.er-api는 일 1회 갱신 → 1시간 캐시로 배치 중 API 호출 최소화


def fetch_fx_krw(base: str = "GBP", timeout: float = 12.0, use_cache: bool = True) -> float:
    """open.er-api.com에서 base→KRW 환율(통화별 1시간 캐시)."""
    base = (base or "GBP").upper()
    if base == "KRW":
        return 1.0
    now = time.time()
    hit = _FX_CACHE.get(base)
    if use_cache and hit and (now - hit["ts"]) < _FX_TTL_S:
        return hit["rate"]
    with urllib.request.urlopen(FX_URL_TEMPLATE.format(base=base), timeout=timeout) as r:
        d = json.load(r)
    if d.get("result") != "success":
        raise RuntimeError(f"FX API 실패({base}): {d.get('error-type', d.get('result'))}")
    rate = float(d["rates"]["KRW"])
    _FX_CACHE[base] = {"rate": rate, "ts": now}
    return rate


def fetch_gbp_krw(timeout: float = 12.0, use_cache: bool = True) -> float:
    return fetch_fx_krw("GBP", timeout, use_cache)


@dataclass
class CostBreakdown:
    price_gbp: float
    weight_grams: float | None
    shipping_gbp: float
    landed_gbp: float          # price + shipping
    fx_gbp_krw: float
    goods_krw: int             # 상품 원가(배송비 제외) = 묶음수량이 곱해지는 단가
    shipping_krw: int          # 배대지 배송비(원) = 주문당 1회만 가산(묶음수량 무관)
    cost_krw: int              # goods+shipping(참고/표시용)


def landed_cost_krw(price_gbp: float, weight_grams: float | None, fx: float | None = None,
                    currency: str = "GBP") -> CostBreakdown:
    fx = fx if fx is not None else fetch_fx_krw(currency)
    ship = shipping_gbp(weight_grams)
    goods_krw = round(price_gbp * fx)
    shipping_krw = round(ship * fx)
    return CostBreakdown(
        price_gbp=price_gbp,
        weight_grams=weight_grams,
        shipping_gbp=ship,
        landed_gbp=round(price_gbp + ship, 2),
        fx_gbp_krw=round(fx, 2),
        goods_krw=goods_krw,
        shipping_krw=shipping_krw,
        cost_krw=goods_krw + shipping_krw,
    )
