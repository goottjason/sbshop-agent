"""iHerb 스크래퍼 검증 스크립트 (라이브 크롤).

    python test_iherb.py                 # 목록(supplements 1p) + 상세 1건
    python test_iherb.py --list grocery  # 특정 카테고리 목록만
    python test_iherb.py --detail URL    # 특정 상품 상세만

라이브 사이트를 치므로 CI가 아니라 손으로 돌리는 확인용이다.
목록 파싱은 스코어링 신호 커버리지(평점·리뷰수·판매량)를 함께 출력한다 —
셀렉터가 조용히 깨지면 후보는 나오는데 점수만 무너지기 때문.
"""
import json
import sys

from scrapers.iherb import IherbScraper, fetch_bestsellers, fetch_detail

DEFAULT_DETAIL_URL = (
    "https://kr.iherb.com/pr/"
    "california-gold-nutrition-vitamin-d3-k2-as-mk-7-180-veggie-capsules/124745"
)
SIGNAL_FIELDS = ("nameKo", "brand", "discountPrice", "rating", "reviewCount",
                 "sales30d", "imageUrl", "rankPosition")


def check_list(slug: str, page: int = 1) -> None:
    print(f"[list] /c/{slug}?sort=10&p={page}")
    cards, err = fetch_bestsellers(slug, page)
    if err:
        print(f"  ✗ {err}")
        return
    print(f"  카드 {len(cards)}개")
    for c in cards[:3]:
        print("   ", json.dumps(c.model_dump(), ensure_ascii=False)[:180])
    print("  신호 커버리지:")
    for f in SIGNAL_FIELDS:
        n = sum(1 for c in cards if getattr(c, f) not in (None, ""))
        flag = "✓" if n >= len(cards) * 0.9 else "✗"
        print(f"    {flag} {f}: {n}/{len(cards)}")


def check_detail(url: str) -> None:
    print(f"[detail] {url}")
    d = fetch_detail(url)
    m = d.model_dump()
    m.pop("specs", None)
    m["images"] = f"{len(m['images'])}장"
    for k in ("description", "usage", "caution", "ingredientsRaw"):
        m[k] = (m.get(k) or "")[:80]
    print(json.dumps(m, ensure_ascii=False, indent=1))
    # 통관 게이트와 마켓 필수필드가 의존하는 값 — 비면 파이프라인이 REVIEW로 떨어진다.
    for k in ("ingredientsRaw", "upc", "shippingWeightGrams", "packageQuantity", "priceKrw"):
        print(f"  {'✓' if m.get(k) else '✗'} {k}")


def check_batch(url: str) -> None:
    print(f"[batch] {url}")
    print(" ", IherbScraper().scrape(url).model_dump_json())


def main() -> None:
    argv = sys.argv[1:]
    if "--list" in argv:
        check_list(argv[argv.index("--list") + 1])
    elif "--detail" in argv:
        check_detail(argv[argv.index("--detail") + 1])
    else:
        check_list("supplements")
        print()
        check_detail(DEFAULT_DETAIL_URL)
        print()
        check_batch(DEFAULT_DETAIL_URL)


if __name__ == "__main__":
    main()
