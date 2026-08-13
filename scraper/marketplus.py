"""Cafe24 마켓플러스(mp.cafe24.com) 자동화.

G마켓·옥션에는 상품등록 공개 API가 없다. Cafe24에 등록된 상품을 마켓플러스의
'미판매 상품'(일괄보내기) 목록에서 골라 '일괄보내기'로 내보내는 것이 유일한 경로다.

이 모듈은 Task 7 스파이크 범위 — 로그인과 목록 진입까지만 한다. 실제 전송
(send_to_market)은 다음 태스크다. 아래 셀렉터는 전부 2026-08-13 실측으로 확정했다.
근거는 docs/normalize/working_history/20260813_marketplus_스파이크.md 참조.
"""
from __future__ import annotations

import os

from scrapling.fetchers import DynamicFetcher

# mp.cafe24.com/ 는 로그인 폼이 아니라 마케팅 페이지다(www.cafe24.com으로 리다이렉트).
# 보호된 URL을 치면 아래 eclogin으로 튕기므로, 처음부터 여기로 간다.
LOGIN_URL = "https://eclogin.cafe24.com/Shop/?mode=mp"
NO_SALE_URL = "https://mp.cafe24.com/mp/product/front/noSaleAll"
MANAGE_LIST_URL = "https://mp.cafe24.com/mp/product/front/manageList"

# --- 로그인 폼(실측) ---
# 주의: mall_id/userpasswd는 name이 아니라 id다. 로그인 버튼도 type=submit이 아닌 type=button이라
# button[type='submit']으로는 절대 잡히지 않는다.
SEL_LOGIN_ID = "input[name='loginId']"
SEL_LOGIN_PW = "input[name='loginPasswd']"
SEL_LOGIN_BTN = "#frm_user button.btnStrong"

# --- 일괄보내기(noSaleAll) 화면(실측) ---
SEL_SEARCH_TYPE = "select[name='search_word_type']"      # product_name | product_code | product_price
SEL_SEARCH_INPUT = "input[name='search_word']"           # search_word_type=product_name일 때
SEL_SEARCH_TEXTAREA = "#eSearchWordTextarea"             # product_code일 때 이쪽으로 전환된다(최대 100개)
SEL_SEARCH_SUBMIT = "button.eBtnSubmit"
SEL_ROW_CHECKBOX = "input[name='prd_code[]']"            # value = Cafe24 product_code
SEL_MARKET_LABEL = "label.eRelationList"                 # market_code/market_user_id 속성 보유
SEL_BATCH_SEND_BTN = "#btnRegisterAll"                   # 새 팝업 창(/mp/product/front/registerall)을 연다


class CredentialsMissing(Exception):
    """자격증명 미설정 — 실패를 성공으로 위장하지 않기 위한 명시적 예외."""


def _credentials() -> tuple[str, str]:
    user = os.getenv("CAFE24_MP_USERNAME", "")
    password = os.getenv("CAFE24_MP_PASSWORD", "")
    if not user or not password:
        raise CredentialsMissing("CAFE24_MP_USERNAME/PASSWORD 미설정")
    return user, password


def _login_and_open_list(page, user: str, password: str):
    """로그인 후 일괄보내기 목록까지 이동한다.

    networkidle은 마켓플러스에서 광고/트래킹 스크립트 때문에 잘 끝나지 않는다 →
    commit + 고정 대기로 간다(F&M 스크래퍼가 wait를 쓰는 이유와 같다).
    """
    page.goto(LOGIN_URL, wait_until="commit")
    page.wait_for_timeout(4000)
    page.fill(SEL_LOGIN_ID, user)
    page.fill(SEL_LOGIN_PW, password)
    page.click(SEL_LOGIN_BTN)
    page.wait_for_timeout(9000)
    page.goto(NO_SALE_URL, wait_until="commit")
    page.wait_for_timeout(9000)
    return page


def probe() -> dict:
    """스파이크용: 로그인 후 일괄보내기 목록에 진입해 화면 상태를 보고한다.

    ok는 최종 URL로 판정한다 — 자격증명이 틀리면 목록으로 못 가고 eclogin에 그대로
    남으므로, 로그인 실패가 ok=true로 위장되지 않는다.
    """
    user, password = _credentials()
    page = DynamicFetcher.fetch(
        LOGIN_URL,
        headless=True,
        page_action=lambda p: _login_and_open_list(p, user, password),
    )
    # 목록 건수는 반드시 이 셀렉터로 읽는다. 본문 전체에서 "총 N건"을 정규식으로 긁으면
    # 마켓 필터의 "[총 2건]"을 먼저 물어 상품 건수를 잘못 보고한다(실측으로 걸린 함정).
    total = page.css(".table-top-info span.txt-inline strong::text").get()
    markets = [
        f"{el.attrib.get('market_code')}|{el.attrib.get('market_user_id')}"
        for el in page.css(SEL_MARKET_LABEL)
    ]
    return {
        "ok": "noSaleAll" in (page.url or ""),
        "url": page.url,
        "title": page.css("title::text").get(),
        "totalCount": total,
        # 목록에 실제로 뜬 Cafe24 상품코드 — sbCode가 아니다(마켓플러스는 자체상품코드를 검색하지 못한다).
        "productCodes": [el.attrib.get("value") for el in page.css(SEL_ROW_CHECKBOX)],
        "marketAccounts": sorted(set(markets)),
    }
