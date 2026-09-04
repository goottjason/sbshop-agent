"""D-291·D-292 — 브랜드 상세 크롤 검증 (네트워크 불필요).

    python test_brand_detail.py
"""
from scrapers.jsonld import _brand_name, OcadoScraper
from scrapers.fortnum import FortnumScraper
from models import ScrapeResult


def test_brand_object() -> None:
    assert _brand_name({"@type": "Organization", "name": "Marmite"}) == "Marmite"


def test_brand_plain_string() -> None:
    assert _brand_name("Filippo Berio") == "Filippo Berio"


def test_brand_missing() -> None:
    assert _brand_name(None) is None
    assert _brand_name({}) is None
    assert _brand_name("  ") is None


def test_jsonld_detail_carries_brand() -> None:
    result = ScrapeResult(ok=True, status="ok", sourceUrl="https://www.ocado.com/x",
                          vendor="OCD", name="Marmite 600g", price=5.5, currency="GBP",
                          inStock=True, brandKo="Marmite", scrapedAt="t")
    detail = OcadoScraper().to_detail(result)
    assert detail.ok is True
    assert detail.brandKo == "Marmite"
    assert detail.nameKo == "Marmite 600g"
    assert detail.priceKrw is None


def test_jsonld_detail_failure_passthrough() -> None:
    result = ScrapeResult(ok=False, status="not_found", sourceUrl="https://www.ocado.com/x",
                          vendor="OCD", error="404", scrapedAt="t")
    detail = OcadoScraper().to_detail(result)
    assert detail.ok is False
    assert detail.status == "not_found"
    assert detail.brandKo is None


def test_fortnum_detail_is_constant_brand() -> None:
    detail = FortnumScraper().fetch_detail("https://www.fortnumandmason.com/royal-blend")
    assert detail.ok is True
    assert detail.brandKo == "Fortnum & Mason (포트넘앤메이슨)"


def main() -> None:
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for fn in fns:
        fn()
        print("ok -", fn.__name__)
    print(f"{len(fns)} tests passed")


if __name__ == "__main__":
    main()
