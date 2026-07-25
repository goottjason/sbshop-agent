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
FX_URL = "https://open.er-api.com/v6/latest/GBP"


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

_FX_CACHE: dict = {"rate": None, "ts": 0.0}
_FX_TTL_S = 3600.0  # open.er-api는 일 1회 갱신 → 1시간 캐시로 배치 중 API 호출 최소화


def fetch_gbp_krw(timeout: float = 12.0, use_cache: bool = True) -> float:
    """open.er-api.com에서 최신 GBP→KRW 환율(1시간 캐시)."""
    now = time.time()
    if use_cache and _FX_CACHE["rate"] and (now - _FX_CACHE["ts"]) < _FX_TTL_S:
        return _FX_CACHE["rate"]
    with urllib.request.urlopen(FX_URL, timeout=timeout) as r:
        d = json.load(r)
    if d.get("result") != "success":
        raise RuntimeError(f"FX API 실패: {d.get('error-type', d.get('result'))}")
    rate = float(d["rates"]["KRW"])
    _FX_CACHE["rate"], _FX_CACHE["ts"] = rate, now
    return rate


@dataclass
class CostBreakdown:
    price_gbp: float
    weight_grams: float | None
    shipping_gbp: float
    landed_gbp: float          # price + shipping
    fx_gbp_krw: float
    cost_krw: int              # 원화 원가(반올림) = 판매가 계산의 buyPrice 입력


def landed_cost_krw(price_gbp: float, weight_grams: float | None, fx: float | None = None) -> CostBreakdown:
    fx = fx if fx is not None else fetch_gbp_krw()
    ship = shipping_gbp(weight_grams)
    landed = price_gbp + ship
    return CostBreakdown(
        price_gbp=price_gbp,
        weight_grams=weight_grams,
        shipping_gbp=ship,
        landed_gbp=round(landed, 2),
        fx_gbp_krw=round(fx, 2),
        cost_krw=round(landed * fx),
    )
