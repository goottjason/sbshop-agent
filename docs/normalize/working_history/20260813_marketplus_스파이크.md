# 마켓플러스(mp.cafe24.com) 로그인·일괄보내기 스파이크 실측 (2026-08-13)

G마켓·옥션에는 상품등록 공개 API가 없다. 유일한 경로는 Cafe24에 등록된 상품을
마켓플러스 '일괄보내기'(미판매 상품) 목록에서 골라 내보내는 것이다. 이 문서는 그
자동화를 짜기 전에 화면을 **직접 열어 확인한 사실**만 적는다. 추측은 "미확인"으로 남겼다.

계정: `<MALL_ID>` 쇼핑몰 / 마켓계정 2개 — `gmarket|<MALL_ID>`, `auction|shouldbe2480`.
(`<MALL_ID>`는 로그인 아이디와 같은 값이라 이 문서에서 가렸다. 자동화는 이 값을 하드코딩하지
말고 목록의 `label.eRelationList[market_user_id]`에서 읽으면 된다.)
실측 방식: Playwright(Chromium, headless) 로 실제 로그인 후 DOM 덤프. **전송은 하지 않았다.**

---

## 확정 셀렉터

### 로그인

| 항목 | 값 |
|------|-----|
| 진입 URL | `https://eclogin.cafe24.com/Shop/?mode=mp` |
| 아이디 | `input[name='loginId']` (id는 `mall_id`) |
| 비밀번호 | `input[name='loginPasswd']` (id는 `userpasswd`) |
| 로그인 버튼 | `#frm_user button.btnStrong` |
| 폼 | `#frm_user`, POST `https://eclogin.cafe24.com/Shop/?mode=mp` |

- `https://mp.cafe24.com/` 은 로그인 화면이 **아니다** — `www.cafe24.com/commerce/channel/market.html`
  (마케팅 페이지)로 리다이렉트된다. 보호된 URL(`/mp/product/front/noSaleAll`)을 치면
  위 eclogin으로 튕기므로, 자동화는 처음부터 eclogin URL로 간다.
- 로그인 성공 시 `https://mp.cafe24.com/mp/main/front/service` 로 랜딩.
- `networkidle`은 광고·트래킹 스크립트 때문에 잘 끝나지 않는다 → `wait_until="commit"` + 고정 대기.

### 일괄보내기 목록 (`https://mp.cafe24.com/mp/product/front/noSaleAll`)

| 항목 | 셀렉터 |
|------|--------|
| 검색 조건 | `select[name='search_word_type']` — `product_name` / `product_code` / `product_price` |
| 검색어(상품명) | `input[name='search_word']` |
| 검색어(상품코드) | `#eSearchWordTextarea` (name=`search_word_textarea`, 콤마·엔터 구분 **최대 100개**) |
| 검색 버튼 | `button.eBtnSubmit` |
| 총 건수 | `.table-top-info span.txt-inline strong` |
| 상품 행 체크박스 | `input[name='prd_code[]']` (value = Cafe24 `product_code`) |
| 전체 선택 | `.allCk` |
| 마켓별 체크박스 | `input[name="prd_entity_no[<product_code>][]"]` (class `market_check`) |
| 마켓 식별 | 위 체크박스의 부모 `label.eRelationList[market_code][market_user_id]` |
| 일괄보내기 버튼 | `#btnRegisterAll` |

`search_word_type`을 `product_code`로 바꾸면 입력칸이 input에서 textarea로 **바뀐다**.
input에 채우려 하면 "element is not visible"로 죽는다.

### 일괄등록/전송 팝업 (`/mp/product/front/registerall`)

`#btnRegisterAll` → `postWindowOpen('/mp/product/front/registerall', ...)` — **새 창**으로 form POST.
Playwright에서는 `context.expect_page()` 로 받아야 한다. 전달되는 필드(요약):
`prd_info[<prd_code>][prd_no|prd_code|shop_no|prd_name|prd_price|sign]`,
`prd_info[...][market_info][n][prd_entity_no|market_code|market_user_id]`,
`check_market[<market_code>|<user_id>]`, `check_product[...][]`, `shop_no`, `total_count`.

| 항목 | 셀렉터 |
|------|--------|
| 마켓 ON/OFF 토글 | `input[name='market_checked[]'][value='<market_code>\|<user_id>']` |
| 템플릿 | `select[name='template_no[<market_code>\|<user_id>]']` |
| 마켓 카테고리 | `select[name='market_category1..4[<market_code>\|<user_id>]']` |
| 표준카테고리 검색 | 상단 키워드 입력 + `#btnGetStandardCategory` |
| **전송 버튼** | `#btnSubmit` |

### 마켓상품관리 (`/mp/product/front/manageList`) — 전송 결과 조회용

| 항목 | 셀렉터 |
|------|--------|
| 검색 조건 | `select[name='search_word_type']` — `K`키워드 / `P`상품명 / `N`상품코드 / `C`마켓상품코드 / **`U`자체상품코드** / `R`판매가 / `T`상품 수식어 |
| 기간 기준 | `select[name='search_time_type']` — `B`판매 시작일(기본) / `E`판매 종료일 / **`S`최근 전송일** |
| 기간 | `input[name*='begin_ymd']`, `input[name*='end_ymd']` |
| 검색 버튼 | `button.eBtnSearch` |
| 컬럼 | 마켓(계정) · 상품코드 · **마켓상품코드** · **자체상품코드** · 전송상태 · 판매상태 · 전송일 |

---

## 6개 질문에 대한 답

### 1. 로그인 폼의 실제 input name / 2단계 인증 유무 — **확인함**

`input[name='loginId']` / `input[name='loginPasswd']` / `#frm_user button.btnStrong`.
브리프의 추측(`input[name='mall_id']`, `input[name='userpasswd']`, `button[type='submit']`)은
**전부 틀렸다** — 앞 둘은 name이 아니라 id이고, 로그인 버튼은 `type="button"`이라
`button[type='submit']`으로는 영영 안 잡힌다.

**2단계 인증·캡차 없음.** 아이디/비밀번호만으로 바로 `mp/main/front/service` 진입.
단, 전송 팝업(registerall)에는 reCAPTCHA가 로드된다(아래 6번 참조).

### 2. `noSaleAll` 검색 셀렉터 / sbCode(`custom_product_code`)로 검색이 되는가 — **확인함: 안 된다**

셀렉터는 위 표대로. sbCode 검색은 **불가**하다. 실측:

| 검색어(`product_code`) | 결과 |
|------|------|
| `251127IHB001` (= 이 상품의 자체상품코드) | **0건** |
| `P000BGO` (Cafe24 코드 앞자리) | **0건** (부분일치 안 됨) |
| `P000BGOU` (Cafe24 `product_code` 완전일치) | 1건 |

- `noSaleAll`의 "상품코드"는 Cafe24 `product_code`(`P000BGOU` 형태) **완전일치**만 매칭한다.
- 자체상품코드는 이 화면 어디에도 노출되지 않는다 — 상품 목록에도, '상품정보' 슬라이드
  레이어(`#r_slide_layer_body`)에도 없다.
- 자체상품코드는 Cafe24 쇼핑몰관리자 상품수정 화면의 `input#ma_product_code`에만 있다
  (`https://<MALL_ID>.cafe24.com/disp/admin/shop1/product/ProductRegister?&product_no=<prd_no>`,
  마켓플러스와 같은 세션으로 열린다).
- 상품명 검색(`product_name`)은 부분일치가 된다(`크레아틴` → 1건). 다만 동명 상품 위험.

**Task 8 함의:** sbCode → Cafe24 `product_code` 매핑이 **선행 조건**이다. 우리 DB가 Cafe24
등록 시 받은 `product_code`를 들고 있어야 하고, 없다면 그것부터 채워야 한다.

기본 기간 필터가 "상품 등록일 1년"(`date_type=365`)이라는 함정도 있다 — 1년 이전에 등록된
상품은 검색해도 안 나온다. 자동화는 기간을 넓혀야 한다.

### 3. 행 체크박스 셀렉터 — **확인함**

- 상품 단위: `input[name='prd_code[]'][value='<product_code>']`
- **실제 전송 단위**: `input[name="prd_entity_no[<product_code>][]"]` (마켓×상품 1개씩)
- 상품 체크박스를 켜면 JS가 그 상품의 마켓 체크박스를 전부 켠다. G마켓만 보내려면
  상품 체크박스를 건드리지 말고 `label.eRelationList[market_code='gmarket']` 안의 체크박스만
  켠 뒤 `change` 이벤트를 발생시켜야 한다.
- `#btnRegisterAll` 핸들러는 `prd_code[]`가 아니라 `prd_entity_no`가 몇 개 체크됐는지로
  판단한다(0개면 "전송 가능한 상품이 없습니다").

### 4. "일괄 보내기" 버튼 / G마켓·옥션 개별 선택 가능 여부 — **확인함: 개별 선택 가능**

`#btnRegisterAll` → 새 팝업 창 `/mp/product/front/registerall`.
목록에서 옥션 체크박스만 켜고 열었더니 팝업에 **옥션(shouldbe2480) 한 줄만** 떴다.
팝업 안에도 마켓별 ON/OFF 토글이 따로 있다. 마켓 개별 선택은 두 층에서 모두 가능.

### 5. 전송 후 마켓 상품번호가 화면에 즉시 노출되는가 — **확인함: 아니오(비동기 큐)**

전송은 큐에 들어간다. 근거:

- `상품관리이력`(`/mp/queue/productList`) 컬럼이 `전송요청일` / `전송완료일` / `작업내용·결과`다.
  실제 행 예: `67480 | shouldbe2480 | P0000PIT | D889112400 | ... | 상품수정 | [성공] 전송이 완료되었습니다. | 2026-08-11 23:20 | 2026-08-11 23:20`
- 대시보드에 `처리중` / `승인·진열대기` / `전송실패 457` 같은 큐 상태 지표가 있다.

마켓상품코드는 처리 완료 후 `마켓상품관리`에서 조회한다. **그 화면은 자체상품코드(sbCode)
검색을 지원한다**(`search_word_type=U`). 실측: sbCode `210125IHB089` 검색 → 2건
(옥션 `D889229232…`, G마켓 `3992012243…`). 마켓상품코드 형태는 옥션 `D`+숫자, G마켓 순수 숫자.

**함정:** manageList 기본 기간 조건이 "판매 시작일 1년"이라 그대로 검색하면 **총 0건**이 나온다.
`search_time_type=S`(최근 전송일)로 바꾸고 기간을 넓혀야 3,202건이 나온다. 이 기본값을 모르면
"전송된 상품이 하나도 없다"고 오판한다.

**Task 9 함의:** 마켓 상품번호 저장은 즉시 반영이 **불가능**하다. 전송 후 manageList를
sbCode로 폴링해 마켓상품코드를 회수하는 후속 단계가 필요하다.

### 6. 전송에 필요한 사전 설정이 상품마다 필요한가 — **확인함: 카테고리는 상품마다 필요**

registerall 팝업은 마켓계정별로 **템플릿 + 마켓 카테고리**를 요구한다.

- **템플릿: 상품마다 만들 필요 없음.** 계정 단위로 이미 등록돼 있고 대표 템플릿이 자동 선택된다.
  - 옥션: `[대표][2.0] 옥_식품(2)` / `[2.0] 옥_건기식(2)` / `[2.0] 옥_화장품(2)`
  - G마켓: `[대표][2.0] G_식품(2)` / `[2.0] G_건기식(2)` / `[2.0] G_화장품(2)`
  - 다만 상품 성격(식품/건기식/화장품)에 맞는 템플릿을 **고르는 판단**은 상품별로 필요하다.
    대표(식품)를 그대로 두면 건기식·화장품이 잘못된 배송·반품 조건으로 나간다.
- **마켓 카테고리: 상품마다 골라야 한다.** 팝업에 4단계 셀렉트가 전부 미선택으로 뜬다.
  상단 '표준카테고리' 키워드 검색으로 여러 마켓 카테고리를 한 번에 매핑하는 보조 기능이 있다
  (`#btnGetStandardCategory`) — 자동화하면 이쪽이 유리해 보이나 **동작은 미검증**.

---

## 추가 발견 (다음 태스크에 직접 영향)

### A. G마켓 계정은 지금 전송이 막혀 있다 — 계정 문제, 코드로 못 푼다

G마켓만 선택해 팝업을 열면 모달이 뜬다:

> **마켓 상품 수 조정 안내** — 마켓에 등록된 상품 수 조정이 필요해요.
> 마켓에 등록할 수 있는 최대 상품 수량이 초과되어 더이상 상품을 전송할 수 없어요.
> 판매자 센터에서 판매중인 상품 수를 조정한 후 다시 시도해 주세요.
> 실패사유: G마켓(<MALL_ID>) — 최대 개 까지만 등록할 수 있어요
> **다시 전송해 보기 전까진 해당 마켓으로는 상품이 전송되지 않습니다.**

이때 G마켓 행은 `class="disabled"`, 토글 OFF, 템플릿 셀렉트 `disabled`, 표시는 "상품등록 안함".
대시보드의 `전송실패 457`과 정합한다.

**옥션(shouldbe2480)은 정상** — 토글 ON, 템플릿 자동선택, 카테고리 선택 가능, 전송 버튼 활성.

→ 이 상태로는 상품 그리드의 G마켓 배지 등록이 **반드시 실패한다**. 판매자센터에서 상품 수를
정리하기 전까지 G마켓 경로는 실패로 처리하고 사용자에게 사유를 드러내야 한다.

### B. registerall 팝업에 reCAPTCHA가 로드된다 — **미확인 리스크**

팝업 head에 `recaptcha/api.js?render=explicit`가 있고 DOM에 `#grecaptcha_v2_dialog`(v2 폴백)가 있다.
**전송 시 실제로 캡차가 걸리는지는 전송을 해야만 알 수 있어 확인하지 못했다.** 걸린다면
자동 전송 자체가 성립하지 않으므로, Task 8은 이 가능성을 전제로 실패 경로를 설계해야 한다.

### C. 기타

- 현재 미판매(미전송) 상품은 **1건**뿐이다: `P000BGOU` / sbCode `251127IHB001` /
  "Peach Perfect 크레아틴, 핑크 레모네이드, 272g" / ₩56,600 / 등록일 2025-11-26.
  Task 8의 실전송 테스트 대상은 사실상 이것 하나다.
- 팝업 안내: "상품 일괄전송은 최대 50개씩 나누어 전송하시는 것을 권장드립니다."
- 마켓플러스는 로그인 후 "마켓 자동 로그인 크롬 확장 프로그램 설치" 팝업을 띄운다.
  전송 흐름을 막지는 않으나 클릭을 가릴 수 있어 자동화에서 닫아주는 편이 안전하다.

---

## 이 스파이크에서 하지 않은 것

- **실제 전송을 하지 않았다.** `#btnSubmit`은 누르지 않았다. 따라서 전송 성공 응답 형태,
  캡차 발생 여부, 전송 후 화면 전이는 전부 미확인이다.
- `send_to_market()`은 구현하지 않았다(Task 8 범위). 이번에 넣은 것은 `probe()`뿐이다.

## 실행 확인

```
POST localhost:8099/cafe24/mp/probe
{"ok":true,"url":"https://mp.cafe24.com/mp/product/front/noSaleAll",
 "title":"일괄보내기-Cafe24-마켓플러스","totalCount":"1","productCodes":["P000BGOU"],
 "marketAccounts":["auction|shouldbe2480","gmarket|<MALL_ID>"]}
```

자격증명을 비우고 부르면 `CredentialsMissing` → HTTP 503 `credentials_missing`(확인함).
