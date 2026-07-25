"""F&M 스크래핑 PoC 실행/검증 스크립트.

    python test_fortnum.py [URL] [--stealth]

렌더링된 HTML을 fm_rendered.html로 덤프하고 추출 결과(JSON)를 출력한다.
"""
import sys

from scrapers.fortnum import FortnumScraper

DEFAULT_URL = "https://www.fortnumandmason.com/royal-blend-25-tea-bags"


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    url = args[0] if args else DEFAULT_URL
    stealth = "--stealth" in sys.argv

    scraper = FortnumScraper()
    print(f"[test] scraping {url} (stealth={stealth}) ...")
    result = scraper.scrape(url, stealth=stealth, dump_html="fm_rendered.html")
    print(result.model_dump_json(indent=2))


if __name__ == "__main__":
    main()
