"""벤더 스크래퍼 공통 추상 + 유틸.

Java 쪽 SourcingAgent(supports(url)/scrape(url)) 추상화와 대칭이 되도록,
각 벤더 스크래퍼는 supports(url) + scrape(url) 두 메서드만 구현한다.
디스패처(registry)가 URL로 알맞은 스크래퍼를 고른다.
"""
from __future__ import annotations

import re
from abc import ABC, abstractmethod

from models import ScrapeResult

# "£7.95", "£1,299.00" 등에서 숫자만 추출
_PRICE_RE = re.compile(r"£\s*([0-9][0-9,]*\.[0-9]{2})")

# 무게: "6x25g"(개수×중량), "250g", "1.5kg" 순으로 시도
_MULTI_RE = re.compile(r"(\d+)\s*[x×]\s*(\d+(?:\.\d+)?)\s*g\b", re.IGNORECASE)
_KG_RE = re.compile(r"(\d+(?:\.\d+)?)\s*kg\b", re.IGNORECASE)
_G_RE = re.compile(r"(\d+(?:\.\d+)?)\s*g\b", re.IGNORECASE)


def parse_price(text: str) -> float | None:
    """텍스트에서 첫 번째 파운드 금액을 float로 추출. 없으면 None."""
    if not text:
        return None
    m = _PRICE_RE.search(text)
    if not m:
        return None
    return float(m.group(1).replace(",", ""))


def parse_weight_grams(text: str) -> float | None:
    """상품명/스펙 문자열에서 무게(g)를 추출. 'NxMg'는 개수×중량으로 합산. 없으면 None."""
    if not text:
        return None
    m = _MULTI_RE.search(text)
    if m:
        return float(m.group(1)) * float(m.group(2))
    m = _KG_RE.search(text)
    if m:
        return float(m.group(1)) * 1000.0
    m = _G_RE.search(text)
    if m:
        return float(m.group(1))
    return None


class VendorScraper(ABC):
    """벤더별 스크래퍼 인터페이스. Java SourcingAgent와 1:1 대응."""

    vendor: str = ""

    @abstractmethod
    def supports(self, url: str) -> bool:
        ...

    @abstractmethod
    def scrape(self, url: str) -> ScrapeResult:
        ...
