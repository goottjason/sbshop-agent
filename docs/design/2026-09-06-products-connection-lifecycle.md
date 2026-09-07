# 상품 연결 상태·해제·근거 보존 — 2026-09-06

**로컬 구현과 검증 완료, 운영 미배포.** [편집 저장](2026-09-06-products-edit-save.md) 이후 단계다. [Q21·Q22 및 금지 사례](2026-09-06-products-market-presence.md)의 합의에 따라 연결 해제를 실제 API·DB·화면에 연결했다. 운영 상품·외부마켓에 조회/쓰기하거나 제공된 금지 사례를 일괄 적용하지 않았다. 이후 [선택 상품 일괄 조회 작업](2026-09-06-products-inspection-jobs.md)을 구현했으며, 계정 귀속 미확인 시 404 연결 해제 보류를 단건 확인에도 추가했다.

## 현재 동작

- 상품 상세의 **마켓 연결·해제 이력**에서 외부 상품번호와 연결 상태를 확인한다. 지원하는 마켓은 현재 상태를 조회하고, 판매자센터에서 확인한 영구 판매금지는 계정·상품번호·근거를 검토하여 기록한다.
- 스마트스토어의 확정 삭제 또는 영구 판매금지 조회 결과는 해당 연결을 해제한다. 품절·일반 정지·조회 오류는 연결을 유지한다. 상품 존재를 확인했다는 이유로 정보 동기화 완료로 표시하지 않는다.
- 연결 해제 근거, 해제 상태, 해당 마켓의 미반영 작업 취소를 같은 트랜잭션에 저장한다. 하나라도 저장하지 못하면 모두 되돌린다. 마켓 상품번호·시스템상품·주문 연결을 물리 삭제하지 않는다.
- 그리드에 `해제`/`금지`를 구분해 표시하고 상세에서 시각·작업자·외부 상품번호·판정 코드·사유를 확인한다. 판매자 확인에는 입력한 판매 계정도 표시한다. 해제 배지로 기존 등록 버튼을 실행할 수 없다.
- 마지막 활성 연결까지 해제하면 Q15에 따라 상품 편집 잠금을 해제한다. Q21의 상품명 재조합·전후 검토를 그대로 적용한다. 다른 활성 연결이 남아 있으면 그 마켓의 필드 제한을 유지한다.

## 연결 모델

`sb_market_registration`에 `revision` 및 `connection_state`, `gmarket_connection_state`, `auction_connection_state`를 추가했다.

| 상태 | 의미 | 수정·등록 처리 |
| --- | --- | --- |
| `LINKED` | 현재 연결 기록 유지. 판매 정상·정보 일치 보증은 아님 | 기존 마켓 정책과 확인 상태에 따름 |
| `DETACHED_DELETED` | 확정 부재·삭제로 활성 연결 해제 | 자동 쓰기 중지. 과거 상품번호 보존. 선택 재등록은 후속 구현 |
| `DETACHED_PROHIBITED` | 확정 영구 판매금지로 활성 연결 해제 | 자동 쓰기·기존 강제 재등록 차단. 삭제 상태로 하향 변경하지 않음 |

기존 행은 모두 `LINKED`로 시작한다. 기존 `is_synced=false`, 오류 메시지, 과거 `DELETED_ON_MARKET` 표시만으로 새 연결 상태를 일괄 전환하지 않는다.

카페24 본상품·G마켓·옥션을 각각 관리한다. G마켓 해제만으로 옥션과 카페24를 해제하지 않는다. 다만 카페24 공통 수정 API가 해제된 하위 마켓에도 전파되는지 아직 검증되지 않아 **하위 마켓 하나라도 해제되면 카페24 공통 쓰기를 보류**한다. 해제되지 않은 형제 마켓의 연결과 변경 대기 기록은 유지한다. 마켓플러스의 개별 등록 안내는 요청한 마켓 및 카페24 본상품 상태를 확인한다.

## 조회 근거와 경쟁 요청

`sb_market_connection_event`에 연결/상품 ID, 마켓, 사용한 외부 상품번호, 작업자, 확인 출처, 적용 결과, 관측 상태·시각, 저장 시각, 검토한 연결 버전, 근거 JSON을 추가 전용으로 기록한다. JSON에는 계정 참조·조회 경로·결과 코드·상태·설명을 보관한다. 인증 비밀값이나 전체 API 원문은 저장하지 않는다.

외부 조회는 DB 잠금 없이 실행한다. 결과 적용 시 상품→연결 순서로 잠근 뒤 버전과 전체 식별자·대상 상품번호를 다시 확인한다. 조회 중 변경되었다면 `STALE` 근거만 남기고 새 연결에 판정을 적용하지 않는다. 오래된 동기화 결과의 저장은 JPA 버전 검사로 충돌 처리하며, 해제 후 읽은 객체의 `markSynced`/존재 확인도 연결을 복구하지 않는다. 기존 가져오기·식별자 보완·강제 재등록으로 해제된 식별자를 교체할 수 없다.

`PENDING_DISPATCH` 변경 대상 중 해당 연결·해당 마켓의 항목만 `CANCELLED_DETACHED`로 바꾼다. 이를 편집 이력 화면에 표시한다. 전송 작업자·실행 중 작업 회수는 아직 구현하지 않았다.

## 마켓별 판정 범위

스마트스토어는 [공식 원상품 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/read-origin-product-product)의 정확한 상품번호 경로를 호출한다.

- HTTP 404와 업무 코드 `NOT_FOUND`의 조합, 또는 정상 상품 응답의 `DELETE` → 삭제.
- 정상 상품 응답의 `PROHIBITION` → 영구 판매금지. [공식 상태 구조](https://apicenter.commerce.naver.com/docs/commerce-api/current/schemas/%EC%9B%90%EC%83%81%ED%92%88-%EC%A0%95%EB%B3%B4-%EA%B5%AC%EC%A1%B0%EC%B2%B4)를 사용하며 삭제와 별도 상태로 보존한다.
- `OUTOFSTOCK` → 품절 유지, `SUSPENSION`/`CLOSE` → 정지 유지. 나머지 알려진 존재 상태도 동기화 성공으로 취급하지 않는다.
- `GW.NOT_FOUND`, 인증/권한/429/서버 오류, 빈·잘못된 응답, 다른 상품 ID, 알 수 없는 상태 → 미확인. [공식 FAQ](https://github.com/commerce-api-naver/commerce-api/discussions/980)의 잘못된 경로 404와 상품 부재를 구별한다.

현재 인증 모델은 마켓당 하나의 SELF 연동 앱이다. 실제 판매자 로그인명 대신 사용한 앱 ID의 비밀값이 아닌 해시 참조를 보존하고, 조회 전후 앱 참조가 바뀌면 미확인으로 처리한다. 앱 ID 변경 시 이전 계정의 캐시된 토큰을 재사용하지 않는다. **상품별 과거 판매 계정 귀속과 다중 계정 라우팅은 아직 모델에 없다.** 과거 다른 계정에서 가져온 식별자가 섞였다면 그 귀속 검증을 선행해야 하며, 이 구현을 다중 계정 전체 자동 판정으로 확대하지 않는다.

G마켓·옥션 등 다른 마켓의 자동 판정은 새 `inspectListing` 계약의 기본값인 `UNKNOWN`이다. 검증되지 않은 범용 오류 문자열로 자동 해제하지 않는다. 제공된 이미지의 계정·SB코드는 조사 표본으로 보존했다. 실제 연결의 외부 상품번호를 대조한 후 판매자 확인 근거를 기록할 수 있지만, SB코드만으로 다른 계정·리스팅까지 해제하는 일괄 작업은 만들지 않았다.

## API

모든 경로는 `/api/v1/products/{productId}/connections` 아래다.

| 메서드·경로 | 동작 |
| --- | --- |
| `GET /` | 활성·과거 연결 및 해제 상태, 외부 상품번호, 조회 지원 여부 |
| `GET /history` | 최근 100개 확인·해제 근거 |
| `POST /{registrationId}/{market}/inspect` | 서버가 직접 조회한 결과를 기록하고 확정 상태 적용 |
| `POST /{registrationId}/{market}/prohibition` | 검토한 연결 버전·상품번호와 판매 계정·사유·영구 금지 확인을 받아 근거 저장 및 해제 |

확인 요청의 작업자는 인증 Principal에서 가져온다. 클라이언트가 보낸 `DELETED` 같은 판정 값을 API 조회 결과로 신뢰하지 않는다. 판매자 확인 요청은 `expectedRevision`, `externalId`, `sellerAccount`, `reason`, `confirmedPermanent=true`가 필요하다. 검토 후 연결이 바뀌면 409, 필수 확인 정보 누락은 400이다.

화면은 응답이 없으면 완료로 표시하지 않는다. 연결·근거를 다시 조회하거나 상태 확인을 재시도할 수 있다. 반복 확정은 `ALREADY_DETACHED`로 기존 상태를 유지하고 새 확인 근거를 남긴다. 단건 확인에는 동일 요청의 이벤트 중복을 제거하는 별도 요청 키가 아직 없다. 후속 일괄 조회 작업의 접수·재시도는 별도 요청 ID로 중복을 방지한다.

## 기존 쓰기 경로의 보호 범위

`MarketClientRouter`에서 현재 DB 상태를 새 읽기 트랜잭션으로 확인하여 등록, 가격/재고, 이미지/HTML, 바코드, 필드 변경, 고시 보정, 승인 요청, 즉시할인 제거, 삭제 호출을 차단한다. 상품 ID 또는 정확한 현재 마켓 식별자로 연결을 찾는다. 상품번호의 부분 문자열만 일치한 다른 연결을 차단하지 않는다. 관리 DB에 연결이 없는 기존 운영 도구의 호출은 이 보호로 제한하지 않는다.

이는 **외부 호출 직전 상태 검사**다. 이미 시작된 외부 요청을 취소하거나 DB와 외부마켓을 하나의 트랜잭션으로 묶지 않는다. 상태 검사와 실제 호출 사이의 짧은 경쟁 구간은 남아 있고, 분산 작업 임대·연결 세대 기반 전송/완료 확인은 후속 동기화 큐에서 구현해야 한다. DB의 해제 상태를 오래된 결과가 복구하는 문제와 이미 진행 중인 외부 요청은 구분한다.

## 배포와 남은 범위

[편집 DDL](../../backend/docs/ddl/2026-09-06-product-edit-review.sql) 다음에 [연결 DDL](../../backend/docs/ddl/2026-09-06-market-connection-lifecycle.sql)을 적용한다. PostgreSQL 실행 검증 및 운영 백업·DDL·배포는 이번에 수행하지 않았다. FK는 이력이 있는 상품·등록 행의 물리 삭제를 막는다.

운영 전환 시 기존 배치·쓰기 요청을 멈추고 진행 중 작업이 끝난 뒤 모든 API/worker를 새 버전으로 교체해야 한다. 구버전은 새 연결 상태를 읽지 않으므로 혼합 운영하면 보호가 성립하지 않는다. **구버전 이미지로 롤백할 때도 배치·마켓 쓰기를 먼저 중지하고, 해제 상태 보호를 유지할 수 있는 버전으로 복구해야 한다.** 이력 테이블·연결 상태·버전 열은 삭제하지 않는다.

선택 상품 일괄 조회 작업·429 재시도·DB 작업 복구는 후속 구현했다. 전체 상품 정기 접수, 마켓별 계정·판정 계약 확대, 변경 버전별 외부 쓰기 동기화 및 반영 확인, 선택 재등록은 남아 있다. 본 문서의 단건 조회 버튼은 사용자 실행이고, 후속 스케줄러는 접수한 조회 작업만 처리한다. 전체 화면 개편·콘텐츠 비교·마켓 조합 필터 등의 남은 범위는 [진행 기록](2026-09-06-products-implementation-progress.md)에 유지한다.

## 검증

- 백엔드 **458건 통과**: Core 273, API 58, Infrastructure 127. 실패·오류·건너뜀 0. 외부마켓은 모의 응답으로 검증했다.
- 실제 H2 트랜잭션에서 해제·근거·대기 취소의 원자성, 저장 실패 롤백, 조회 중 식별자 변경, 늦은 동기화 결과, 모든 연결 해제 후 편집 허용, 하위 마켓 분리를 검증했다. 기존 등록·배치·마켓 조회·가격/재고·필드 동기화 회귀 검사도 포함한다.
- MockMvc에서 요청자 귀속·연결 버전·필수 확인값·400/409·그리드 배지 구분을 검증했다. 스마트스토어 상태·오류 코드·조회 중 계정 변경 및 토큰 계정 변경도 포함한다.
- 프론트 TypeScript, 변경 파일 ESLint 오류 0, Vite 빌드 통과. 기존 큰 번들 경고는 남아 있다. 변경 Java 76개에 한정한 Spotless 적용·검사 통과.
- 실제 React 컴포넌트를 Chrome에서 로컬 가상 API로 검증했다. 최초 조회 실패의 완료 오표시 방지, 재시도, 삭제 해제, 계정·근거·확인 후 영구 금지 해제, 형제 마켓 유지, 금지 배지의 재등록 차단, 계정·근거 이력 표시를 확인했다. `data-browser-checks="passed"`. 자료는 `/private/tmp/sbshop-connection-browser/lifecycle-source-harness.tsx`, `lifecycle-result.html`, `lifecycle-preview.png`에 있다.

백엔드 재현 명령(`backend`에서 실행):

```sh
./gradlew :core:test --tests '*ProductEditServiceIntegrationTest' --tests '*MarketConnectionWriteGuardTest' --tests '*MarketRegistration*Test' --tests '*ProductMarketSync*Test' --tests '*ProductPublish*Test' --tests '*MarketPlusHandoffServiceTest' --tests '*ProductFieldSync*Test' --tests '*ProductBarcode*Test' --tests '*ProductManage*Test' --tests '*MarketCatalog*Test' --tests '*MarketPresenceCheckTest' --tests '*MarketFailureClassifierTest' --tests '*Batch*Test' :api:test --tests '*MarketConnectionControllerTest' --tests '*ProductController*Test' --tests '*ProductEditControllerTest' :infrastructure:test --tests '*Smartstore*Test' --tests '*CoupangBarcodeIdempotenceTest' --offline
```
