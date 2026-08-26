import json
import sys

from scrapers import vitabiotics as vtb

BASE = "https://www.vitabiotics.com/collections/all-vitabiotics-products/products"
SINGLE_VARIANT_URL = f"{BASE}/wellkid-multi-vitamin-liquid"
MULTI_VARIANT_URL = f"{BASE}/perfectil-original-tablets"
GONE_URL = f"{BASE}/omega-h3-liquid"
RENAMED_URL = f"{BASE}/ultra-folic-acid"

failures: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    print(f"  {'PASS' if cond else 'FAIL'}  {name}{(' — ' + detail) if detail else ''}")
    if not cond:
        failures.append(name)


def check_pure() -> None:
    print("[offline] URL/variant 선택 규칙")
    check("supports는 vitabiotics 도메인만 받는다",
          vtb.supports(SINGLE_VARIANT_URL) and not vtb.supports("https://kr.iherb.com/pr/x/1"))
    check("collections 경로에서도 /products/{handle}.js 를 만든다",
          vtb.product_json_url(SINGLE_VARIANT_URL)
          == "https://www.vitabiotics.com/products/wellkid-multi-vitamin-liquid.js")
    check("상품 URL이 아니면 None",
          vtb.product_json_url("https://www.vitabiotics.com/collections/all") is None)
    check("?variant= 가 있으면 그 id를 읽는다",
          vtb.requested_variant_id(SINGLE_VARIANT_URL + "?variant=123") == "123")

    one = {"variants": [{"id": 1, "barcode": "5021265246656", "title": "150ml"}]}
    two = {"variants": [{"id": 1, "barcode": "a", "title": "30 Tablets"},
                        {"id": 2, "barcode": "b", "title": "90 Tablets"}]}
    v, reason = vtb.select_variant(one, None)
    check("variant 1개면 그것을 고른다", v is not None and reason is None)
    v, reason = vtb.select_variant(two, None)
    check("variant 여러 개면 고르지 않고 사유를 남긴다",
          v is None and reason is not None and "특정할 수 없음" in reason, str(reason))
    v, reason = vtb.select_variant(two, "2")
    check("URL이 variant를 지목하면 그것을 고른다", v is not None and v["barcode"] == "b")
    v, reason = vtb.select_variant(two, "999")
    check("지목한 variant가 없으면 고르지 않는다", v is None and reason is not None)
    v, reason = vtb.select_variant({"variants": []}, None)
    check("variant 목록이 비면 고르지 않는다", v is None and reason is not None)

    check("핸들 토큰이 전부 제목에 있으면 같은 상품으로 본다",
          vtb.title_matches_handle("Ultra Folic Acid", "ultra-folic-acid"))
    check("핸들 토큰이 하나라도 빠지면 다른 상품으로 본다",
          not vtb.title_matches_handle("Perfectil Original", "perfectil-plus-nails"))
    check("제목이 없으면 같은 상품으로 보지 않는다",
          not vtb.title_matches_handle(None, "ultra-iron"))


def check_live() -> None:
    print("[live] vitabiotics 상품 JSON")
    d = vtb.fetch_detail(SINGLE_VARIANT_URL)
    print("   ", json.dumps(d.model_dump(), ensure_ascii=False)[:220])
    check("단일 variant 상품에서 EAN-13을 얻는다",
          d.ok and d.upc is not None and len(d.upc) == 13 and d.upc.isdigit(), str(d.upc))

    d = vtb.fetch_detail(MULTI_VARIANT_URL)
    check("다중 variant 상품은 추측하지 않고 실패로 돌려준다",
          not d.ok and d.upc is None, str(d.error))

    d = vtb.fetch_detail(GONE_URL)
    check("복구 불가한 핸들은 not_found로 구분한다", d.status == "not_found", str(d.error))

    d = vtb.fetch_detail(RENAMED_URL)
    check("이름이 바뀐 핸들은 제목 대조를 통과할 때만 복구한다",
          d.ok and d.upc is not None and d.specs.get("renamedFrom") == "ultra-folic-acid",
          f"{d.upc} / renamedFrom={d.specs.get('renamedFrom')}")


if __name__ == "__main__":
    check_pure()
    if "--offline" not in sys.argv:
        check_live()
    print(f"\n{'FAILED: ' + ', '.join(failures) if failures else 'ALL PASS'}")
    sys.exit(1 if failures else 0)
