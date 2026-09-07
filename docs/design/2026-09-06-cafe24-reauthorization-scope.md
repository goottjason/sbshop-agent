# 카페24 재인증 경로 및 가격 설정 권한 확인

2026-09-06 20:31 KST. **운영 재인증과 가격 설정 403 해결을 완료했다.** 기존 권한 12개를 유지하며 `mall.read_store`를 추가한 새 토큰을 운영 DB에 저장했다. 저장된 토큰으로 권한·가격 설정·상품·주문 조회가 모두 200임을 확인했다. 설정 화면과 백엔드의 코드 보완은 로컬 검증 완료 상태이며 운영 미배포다.

## 원인과 확인 과정

- 직전 운영 읽기 전용 조회에서 `GET /api/v2/admin/products/setting?shop_no=1`이 `403 insufficient_scope`를 반환했다. [마스킹한 응답 근거](evidence/2026-09-06-cafe24-settings-scope-error.json).
- 백엔드 인증 URL에는 앞선 단계에서 `mall.read_store`를 추가했지만, 실제 설정 화면은 별도로 작성한 오래된 scope 목록으로 URL을 만들고 있었다. 이 프론트 경로에는 store 및 category 권한이 없었다.
- 초기에는 라이브 Chrome에서 JavaScript 실행이 꺼져 있다는 오류가 있었으나 이후 재시도에서 정상 접근했다. 사용자가 로그인한 개발자센터 대시보드 → App 관리 → `younzara` 개발정보로 이동했다. JavaScript 허용에 대한 추가 사용자 조치는 필요 없다고 안내했다.
- 저장된 운영 계정의 공개 설정을 읽기 전용으로 확인했다: Mall ID `younzara`, Redirect URI `https://younzara.cafe24.com/`. 인증코드나 토큰은 이 문서에 저장하지 않았다.
- 실제 앱의 선택 권한에도 Store가 없었다. [변경 전 선택 권한](evidence/2026-09-06-cafe24-app-scopes-before.json)에 기록했다. 현재 개발정보 화면에서 Store를 추가했고 기본 선택이 읽기 전용 `mall.read_store`임을 확인했다. 나머지 선택 권한·Redirect URI·API 버전이 이전과 같은지 대조한 뒤 해당 폼의 저장 버튼을 눌렀다.
- 저장 후 브라우저 JavaScript 응답이 25초 내 반환되지 않았다. macOS UI 조작도 `osascript에 보조 접근이 허용되지 않습니다 (-25211)`로 거부됐다. 사용자에게 저장 후 팝업 문구 확인과 완료 알림 닫기를 요청했다. 팝업을 닫고 페이지를 다시 읽거나 새로 고침하기 전에는 저장 확정으로 취급하지 않는다.
- 사용자가 팝업을 닫았다고 알려준 후 개발정보를 다시 읽고, 이어 새로 고침 후 재조회했다. [저장 후 권한 근거](evidence/2026-09-06-cafe24-app-scopes-after.json): Store 읽기 권한 추가와 기존 권한·Redirect URI·API 버전 유지가 확인됐다. 저장 팝업 단계는 완료다.
- 운영 기존 토큰을 공식 `GET /api/v2/oauth/token/scopes`로 읽어 200과 기존 12개 권한을 확인했다. store만 없고 application/category/collection/order/product/shipping의 읽기·쓰기 권한은 모두 있다. [기존 토큰 권한 근거](evidence/2026-09-06-cafe24-token-scopes-before.json). 새 인증은 이 12개 + `mall.read_store`만 요청하면 된다.
- 기존 12개 + Store 읽기를 요청하는 OAuth 새 탭을 열었다. `eclogin.cafe24.com/Shop/`의 쇼핑몰관리자 로그인으로 이동했다. 개발자센터 로그인과 별도 세션이며 비밀번호 입력란이 비어 있었다. 확인된 Mall ID `younzara`만 입력하고 쇼핑몰 운영자 로그인을 요청했다. 비밀번호를 추측하거나 다른 서비스의 비밀번호를 재사용하지 않았다.
- 사용자가 인증 완료를 알린 후 이전 탭이 등록된 Redirect URI에 도달했고 반환 state가 일치함을 확인했다. 이전 로컬 인증 문맥이 10분을 넘었으므로 로그인된 Chrome에서 새 nonce로 인증 요청을 시작했다. 새 코드의 state를 검증하고 즉시 서버에서 토큰으로 교환했다. 사용자에게 다시 로그인하도록 요청할 필요는 없었다.

## 운영 완료 결과

[발급·저장 및 GET 검증 기록](evidence/2026-09-06-cafe24-reauthorization-result.json), [주문 및 실제 상품 재조회](evidence/2026-09-06-cafe24-post-reauth-readback.json).

| 검증 | 결과 |
| --- | --- |
| OAuth 토큰 교환 | 200. 반환 mall/client/shop 1 및 만료 시각 검증 후 저장 |
| 실제 반환 권한 | 기존 application/category/collection/order/product/shipping 읽기·쓰기 12개 + Store 읽기, 총 13개 |
| 저장 후 토큰 권한 재조회 | 200, 필요한 13개 권한 확인 |
| 상품 가격 설정 조회 | 200, `shop_no=1`, `calculate_price_based_on=S`, 응답 버전 2026-09-01 |
| 상품 목록 조회 | 200, 정상 products 배열 |
| 주문 목록 조회 | 200, 정상 orders 배열. 주문번호 필드만 요청하고 기록에는 주문번호도 저장하지 않음 |
| 기존 표본 상품 10186 / 14086 | 둘 다 200. `tax_calculation=M`, `market_sync=T`. 가격 96,800 / 179,100, 판매 상태 T / F |

새 Access Token의 발급 응답 만료 시각은 2026-09-06 22:31:56 KST다. 이후 갱신은 기존 DB 기반 refresh 경로가 담당하며 이번 작업에서 불필요한 강제 refresh는 실행하지 않았다. 토큰 저장은 기존 refresh와 같은 advisory lock을 사용했다.

운영 변경은 개발자센터의 Store 읽기 권한 추가와 해당 계정의 토큰 교환·저장이다. 외부 상품의 가격·재고·판매 상태를 수정하거나 새 상품을 등록하지 않았다. 현재 `M + S` 설정은 로컬 가격 조건 검사의 `price` 사용 분기에 해당한다. G마켓·옥션으로의 필드별 전달 및 현재 판매 상태 확인을 대신하지 않으므로 `market_sync=T`의 기존 검증 보류를 임의로 풀지 않았다.

인증에 사용한 두 Chrome 탭의 주소에서 code/state 쿼리를 제거했고, 서버 임시 실행 파일과 로컬 nonce 문맥 파일을 삭제했다. 코드·토큰 원문은 검증 기록에 포함하지 않았다.

## 변경한 동작

- `GET /api/admin/sync/cafe24/auth-url`: 서버에 저장된 계정으로 인증 URL을 만든다. 누락된 계정 설정은 입력 오류로 반환하고 Mall ID 형식을 검증한다. Client ID, Redirect URI, scope는 개별 쿼리 매개변수로 인코딩한다. 기존 요청 권한을 유지하며 `mall.read_store`를 포함한다.
- 프론트에서 따로 만들던 인증 URL을 제거했다. 저장하지 않은 입력값으로 다른 인증 주소를 열지 않으며 주소 재조회가 실패하면 과거 링크를 숨긴다. 개발자센터 앱의 허용 권한과 실제 토큰의 권한이 요청 scope와 같다고 가정하지 않는다.
- 기본 상품·주문 연결이 정상이어도 재인증 영역을 제공한다. 가격 설정 조회는 별도 상태로 표시한다.
- `GET /api/admin/sync/cafe24/price-settings-status`: 실제 GET으로 확인한다. 예상한 shop 1 상품 설정 응답일 때만 `READABLE`; `insufficient_scope`는 `MISSING_SCOPE`, 인증 오류는 `AUTH_REQUIRED`, 네트워크·429·불명확한 응답은 `UNAVAILABLE`이다. HTTP 성공 본문에 오류가 있어도 성공으로 표시하지 않는다. 원문 예외는 이 상태 응답에 노출하지 않는다.
- 토큰 발급 성공 문구는 발급·저장까지만 알린다. 이후 기본 연결과 가격 설정 권한을 각각 재조회한다. 재조회 중/실패 시 예전 정상 표시를 현재 결과로 사용하지 않는다.
- 재인증의 계정 재조회·토큰 교환·저장을 기존 토큰 refresh와 같은 advisory lock 범위에서 수행한다. 인증 코드와 Redirect URI의 폼 인코딩을 보완했다.
- 붙여넣은 URL에서 정확한 `code` 매개변수를 한 번만 디코딩한다. 전체 URL에 코드가 없거나 잘못된 퍼센트 인코딩이면 발급을 요청하지 않는다. 코드 자체를 붙여넣었을 때의 `+`, `%`는 보존한다.
- 부분 계정 설정이 있는 시작 상태에서 인증 URL 검증 실패가 앱 기동 실패로 번지지 않도록 초기 안내 로그를 정리했다.

앱 화면의 인증 URL은 기존 고정 `state` 및 수동 코드 입력 방식을 유지한다. 이번 변경으로 앱의 전체 OAuth 흐름 보안 검증까지 완료됐다고 간주하지 않는다. 이번 운영 재인증은 별도 일회성 실행 도구의 새 nonce/state 검증을 거쳤으며, 실제 새 토큰의 반환 scope·계정 일치와 가격 설정 조회 성공까지 확인했다.

## 검증

- 백엔드 56건 통과: 가격 설정/주소 21, 기존 상태 8, 코드 추출/발급 12, 토큰 관리 15. 읽기 전용 조회, 누락·잘못된 응답, 중첩 권한 오류, 일시 장애, 잘못된 URL, 한 번만 디코딩, lock 안의 조회·교환·저장, 발급 실패 시 기존 토큰 보존을 포함한다.
- TypeScript, 변경한 프론트 파일 ESLint, Vite 프로덕션 빌드, 변경 Java 파일에 한정한 Spotless, `git diff --check` 통과. 기존 큰 번들 경고는 남아 있다.
- 별도의 headless Chrome에서 실제 `Settings` 컴포넌트를 가상 API 응답으로 실행했다. 기본 연결 정상/가격 권한 부족의 동시 표시, 서버 저장 주소 사용, 실패 후 과거 정상 표시 제거, 주소 오류 후 링크 제거/재시도, 토큰 발급만으로 권한 성공 표시 금지를 포함한 상호작용 6가지를 통과했다. 외부 로그인 브라우저를 이용한 검증과 구분한다.
- 재현 자료: `/private/tmp/sbshop-cafe24-reauth-browser/{source-harness.tsx,build.mjs,result.html,preview.png}`. 백엔드 로그 `/private/tmp/sbshop-cafe24-reauth-tests.log`. 프로젝트 안의 임시 하네스 파일은 빌드 후 제거했다.
- 이번 운영 재인증에서는 OAuth 토큰 교환 POST 1회와 저장된 토큰의 GET 6회(권한·가격 설정·상품 목록·주문 목록·상품 상세 2개)를 실행했다. 모두 200이며 상품 쓰기 호출은 없다. 기존 로컬 코드가 바뀌지 않았으므로 이전 테스트 56건을 이번에 새로 실행한 것으로 중복 집계하지 않는다.

## 남은 운영 반영 조건

1. 권한 추가·재인증·403 해결 확인은 완료했다. 새 설정 화면의 두 엔드포인트는 백엔드·프론트를 함께 배포해야 한다.
2. 누적된 상품 관리 코드와 9개 DDL의 운영 전환 검토가 필요하다. 앱 권한 및 토큰 변경을 해당 코드 배포 완료로 간주하지 않는다.
3. 마켓플러스 필드별 전달/결과 확인, 정기 수집·실패 검색·재전송 및 아래 활성 플래그의 운영 의미 확인은 남아 있다.

재인증 실행 도구: 서버 Python의 기존 psycopg2로 DB 연결·대상 계정·Redirect URI를 읽기 전용으로 사전 확인했다(`2026-09-06T10:53:24Z`). `/tmp/sbshop-cafe24-token-exchange-20260906.py`에 임시 실행 도구를 0600으로 준비하고 사전 점검 후 실제 교환에 사용했다. 코드는 stdin으로만 받았으며 기존 refresh와 같은 advisory lock 안에서 교환·계정/shop/권한/만료 검증·토큰 저장을 수행했다. 애플리케이션의 HTTP 디버그 로그를 통하지 않으며 SQL 진단 로그에도 토큰을 남기지 않도록 세션 설정을 적용했다. 로컬 검토본은 `/private/tmp/sbshop-cafe24-token-exchange-remote.py`에 있고, 서버 임시 파일은 완료 후 삭제했다.

후속 준비 검증: 서버 사전 확인을 `2026-09-06T11:02:58Z`, `11:31:47Z`에 다시 통과했고, 가상 DB/HTTP 응답으로 실행 도구의 정상·권한 누락·다른 계정·shop 누락·만료·교환 403의 6가지 경우를 확인했다. 잘못된 경우 토큰 UPDATE가 없고 정상 결과에도 토큰 원문이 포함되지 않는다. 로컬 `/private/tmp/sbshop-cafe24-oauth-live.py`는 Chrome에서 새 nonce로 인증을 시작하고 돌아온 state를 검증한 뒤 코드를 메모리에서 SSH stdin으로 전달했다. 코드·토큰을 로컬 파일이나 argv에 넣지 않았다. 완료 후 `/private/tmp/sbshop-cafe24-oauth-context.json`도 삭제했으므로 향후 재인증은 새 문맥으로 시작해야 한다.

추가 배포 점검 사항: 운영 카페24 자격증명의 `is_active=false`가 관측됐다. 기존 카페24 토큰 경로는 이 플래그를 사용하지 않지만, 새 마켓플러스 이력 서비스는 이를 검사하므로 그대로 배포하면 가져오기와 현재 연결 요약이 차단될 수 있다. 플래그가 설정된 이유와 운영 의미를 확인하기 전 운영 DB 값을 바꾸거나 조건을 제거하지 않았다.

## 공식 근거

- [상품 설정 조회](https://apidocs.cafe24.com/docs/admin/get-products-setting): 판매가 설정 조회 및 `READ_STORE` scope.
- [Cafe24 인증 가이드](https://developers.cafe24.com/docs/api/): 브라우저 인증코드 취득, 코드의 1분 유효기간, 토큰 교환·scope 및 오류 코드.
- [OAuth 2.0 인증](https://developers.cafe24.com/docs-new/docs/guide/oauth2-authentication): 발급 응답의 계정 및 scope, state 확인 절차.
- [인증/보안 개발가이드](https://developer.cafe24.com/docs/guide/authentication_security_guide.html): 실제 토큰 scope 조회 `GET /api/v2/oauth/token/scopes`.
