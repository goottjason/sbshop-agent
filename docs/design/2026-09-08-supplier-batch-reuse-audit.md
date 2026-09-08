# 소싱 배치의 수집·DB·마켓 재사용 점검

2026-09-08. 승인된 배치 실행 조건을 상품별 추가 승인 없이 적용하는 내부 접점을 검토했다. 이 작업에서는 운영 수집·마켓 쓰기를 실행하지 않았다. 아래 가격 규칙은 기존 코드를 재사용한 것으로, 새 외부 API 계약을 가정하지 않는다.

## 구현한 편집 접점

- `ProductEditPlanner.planBatchSourceObservation`: 소싱 관측 필드(`costPrice`, `exchangeRate`, `stockStatus`, 관측된 경우의 `stock`)와 배치 정책(`marginRate`, `couponRate`, `minMarginPrice`)을 하나의 검토 계획으로 만든다. 상품명·묶음수량·판매용 설정 수량을 변경하는 경로는 열지 않았다. 일반 `planSourceObservation`의 허용 필드는 그대로다.
- 가격 모드의 DB 기준 판매가는 기존 `BatchPriceStockService`의 쿠팡 수수료 기준 계산을 따른다. 마켓별 전송가는 각각의 수수료로 계산한다. 기존 100원 단위 처리와 최소마진 보호를 유지한다. 정책/원가가 같아도 오래된 기준 판매가는 다시 계산한다.
- `ProductEditService.commitReviewedBatchSource`: 호출자의 트랜잭션에 참여한다. 상품 revision, 연결 fingerprint, 계산 결과, 만료, 수집 시각을 다시 확인하고 정책·원가·환율·재고·이력·미반영 대상을 원자적으로 저장한다. 수집 URL/vendor·수집 스냅샷/검토의 작업자 귀속·배송 정책의 검사는 호출하는 소싱 서비스가 담당한다.
- 값이 같더라도 재고 관측을 승인한 배치는 수집 성공 시각과 기존 수집 오류 해소를 이력과 함께 한 번 기록한다. 같은 검토 ID의 DB 재시도는 저장한 history ID를 반환한다. DB 실패 후 수집 자체를 반복하지 않는다.
- 새 target은 `BATCH_MANAGED`, `batchManaged=true`로 저장한다. 기존 자동 dispatcher의 `PENDING_DISPATCH` 검색에서 제외하고, 미반영 개수에는 포함한다. 가격 작업의 STALE 결과가 자동 dispatch 상태로 돌려놓지 못하도록 소유권을 유지한다. DDL에는 `batch_managed BOOLEAN NOT NULL DEFAULT FALSE`가 필요하다.

검증: 기존 `ProductEditServiceIntegrationTest` 42건(배치 집중 회귀 7건 포함)이 통과했다. root가 실행한 공유 회귀 전체 96건은 실패 0건이다. 로그: `/private/tmp/sbshop-batch-shared-regression.log`. 새 회귀는 정책·관측의 단일 revision 저장, 일반 dispatcher 제외, target 저장 실패의 전체 rollback과 동일 계획 재시도, 일반 소싱 API 범위 보존, 값이 같은 관측의 시각/이력, 버전·연결·가격 정책·만료 충돌, 동일 정책의 기준가 재계산, dispatch 이후 배치 소유권을 다룬다.

후속 연결 검토에서 배치 재고 검토 ID가 일반 소싱 commit API로 유입되면 일반 자동 전송 대상이 될 수 있는 경계를 보완했다. `ProductSupplierBatchSource`의 검토 payload에 `SUPPLIER_BATCH_SOURCE` 종류를 명시하고 양쪽 commit 경로가 서로의 검토를 거절한다. 스냅샷과 계획의 상품 ID도 일치해야 한다. `ProductSourceSnapshot.applied()`는 이미 최초 값이 없을 때만 기록하므로 적용 시각의 구현은 바꾸지 않았다. 새 소싱 통합 회귀 5건은 이 경로 분리, 필드 선택과 부분 수집, 최초 적용 시각/만료 후 같은 이력 재사용, DB 실패 후 수집 재사용, 작업자·URL·소싱처·배송 정책·만료 재검사를 검증한다.

후속 실행 결과: `ProductSourceServiceIntegrationTest` 26건과 `ProductEditServiceIntegrationTest` 42건, 총 68건 실패/스킵 0건. 로그: `/private/tmp/sbshop-batch-source-bridge-tests.log`.

마켓 연결 통합은 별도 `ProductSupplierBatchMarketIntegrationTest` 7건으로 검증했다. 실제 소싱·DB·가격·수량 영속 큐를 사용하고 외부 마켓 관측/쓰기만 모의했다. 가격 실패 후 가격만 재시도해도 수집 1회/DB 이력 1건/성공 수량의 task·읽기·쓰기 횟수는 그대로였다. 자식 commit 응답 유실 시 동일 review/task를 유지했고, 자식 조회 장애 중 pause는 외부 요청을 막았다. 과거 실패는 후속 수동 동기화로 확정된 `SUPERSEDED_BY_CURRENT`를 덮지 않았다. 가격·수량의 미커밋 검토 만료는 장기 pause 후 다시 준비하고 이미 커밋된 검토는 같은 참조를 보존했다. 7건 모두 실패/스킵 0건이며 로그는 `/private/tmp/sbshop-batch-market-integration.log`다. 이 결과는 로컬 통합 검증이며 운영 배치 실행 결과가 아니다.

## 오케스트레이터 연결 시 보존할 조건

| 단계 | 재사용 경로와 필요한 조건 |
|---|---|
| 실행 승인 | 대상 상품 ID, 소싱처, 모드, 선택 마켓, 정책을 영속화한다. 선택 마켓의 승인 당시 계정 reference도 고정하고 후속 검토의 계정과 비교한다. |
| 수집 | 기존 소싱 큐는 요청당 1~50개, 전체 미완료 300개 제한이 있다. 전체 대상은 서버가 묶음/대기열을 관리한다. 수집 실패를 재고 0이나 품절로 변환하지 않는다. |
| 수집 재사용 | 성공 결과는 스냅샷에 보존한다. 유효기간 24시간, 당시 상품 revision/URL/vendor/연결/배송 정책 확인이 필요하다. DB 장애는 같은 성공 스냅샷/검토를 재시도한다. 실제 자료 변경·만료는 재수집 필요 사유로 분리한다. |
| 가격/재고 부분 수집 | `priceAvailable`과 `stockAvailable`은 독립적이다. 환율 조회 실패인데 재고가 확인된 결과를 전부 성공 또는 전부 품절로 처리하지 않는다. 두 번 DB 저장하면 revision이 달라지므로 부분 저장/추후 재수집 전략을 명시해야 한다. |
| DB 저장 | 배치 정책과 새 원가를 따로 저장하면 revision이 바뀌어 기존 스냅샷이 stale이 된다. 하나의 batch plan/commit을 사용한다. 명시 수량이 없으면 기존 원재고를 유지하고 판매용 설정 수량도 유지한다. |
| 마켓 접수 | `BATCH_MANAGED` target과 batch stage를 연결한다. 마켓 review ID를 stage에 먼저 영속화한 다음 commit한다. 그래야 재시도와 pause가 기존 자식 큐를 추적한다. 정상 자동 dispatcher와 배치의 이중 접수를 허용하지 않는다. |
| 부분 재시도 | 가격과 수량은 별도 task/review ID 및 단계다. 가격 확인 완료 후 수량만 실패하면 가격 성공을 재사용한다. DB 성공은 마켓 성공이 아니며, 실제 재조회로 일치한 필드만 확인 완료다. |
| 미선택 마켓 | DB 변경 후 미반영 기록을 남기되 이번 배치에서는 접수하지 않는다. 미선택/소싱만 실행을 마켓 성공으로 집계하지 않는다. 이후 수동 동일 revision의 실제 재조회 성공은 해당 필드의 target을 해소할 수 있어야 한다. |
| pause | 부모 반복문만 멈추면 이미 접수한 source/market 자식 큐가 계속 움직인다. 신규 수집/DB 저장/마켓 claim과 실제 쓰기 직전까지 배치 상태를 검사한다. 진행 중 요청의 결과는 보존하고 재개 시 기존 ID로 읽기부터 확인한다. 공유 429 gate를 배치 pause 용도로 전체 정지시키지 않는다. |

## 계산 근거와 제한

`ProductSourceObservationClient`는 관측 통화와 환율을 보존하고, 환율을 소수 2자리로 정규화한 뒤 원화 상품가격을 계산한다. `ProductSourceWorker`는 수집 당시 무게·묶음수량·국제배송 정책으로 개당 landed cost를 만들며, 불완전한 배송 정책/환율은 가격 미확보로 남긴다. 관측 원가에 FX를 다시 곱하지 않는다.

`MarginCalculator`의 coupon은 **소싱 구매 쿠폰**이다. 현행 계산은 국제배송분을 포함한 개당 매입원가에 쿠폰율을 적용하고, 묶음수량을 곱한 뒤 국내배송 정책을 반영한다. 소비자용 마켓 할인 설정 API와는 별개다. 기존 최소마진은 `판매가 - 쿠폰 적용 총 매입가 - 국내배송비` 기준이며, 채널 수수료를 차감한 순이익 보장으로 표시하면 안 된다.

고정된 10%/20%/1,500원 정책 이외에도 마켓 수수료와 소싱 국내배송 정책이 실제 계산에 참여한다. 편집 commit과 마켓 작업의 재검사에서 계산값이 달라지면 충돌/STALE로 남겨야 한다. 이전 원가·저장 판매가로 조용히 대체해 성공시키지 않는다.

현재 검증된 큐 범위: 가격은 스마트스토어·쿠팡·카페24 본상품이며, 11번가 가격은 미지원이다. 수량은 각 마켓의 검증된 단일 상품/품목 조건에 한정하며 11번가의 양수 재고 103(판매 중) 계약을 넘어 0개·판매중지/재개를 추정하지 않는다. 카페24 본상품 성공을 G마켓/옥션 성공으로 복사하지 않는다. 영구금지·출처 부재·불명 재고·계정/상품 신원 불일치는 해당 단계의 명시적 보류다.
