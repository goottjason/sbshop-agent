# 11번가 검토형 판매용 수량 반영

2026-09-08 인증된 API Center에서 확보한 [재고 조회 원문](../external-api/elevenst/live-2026-09-08/stock-read.json)과 [수량 변경 원문](../external-api/elevenst/live-2026-09-08/stock-update.json)에 근거한 제한적 지원이다. 일반 상품 전체 수정이나 옵션 전체 교체를 사용하지 않는다.

| 단계 | 확인한 계약 | 구현 |
| --- | --- | --- |
| 상품·상태 확인 | 상품 상세 GET의 `prdNo`, `sellerPrdCd`, `selStatCd` | 현재 계정과 정확한 상품/SB를 대조한다. 판매중 `103`만 쓰기 후보로 삼는다. |
| 재고 읽기 | `GET /rest/prodmarketservice/prodmarket/stck/{prdNo}` | 루트 상품번호·SB, 단일 `ProductStock`의 상품번호·재고번호를 대조한다. `stckQty`를 읽고 `selQty`나 상품 전체 수량으로 대체하지 않는다. |
| 수량 쓰기 | `PUT /rest/prodservices/stockqty/{prdStckNo}`, v1.2 | `ProductStock`의 필수 `prdNo`, `prdStckNo`, `stckQty`, `optWght`만 전송한다. 무게는 직전 조회값을 그대로 보존한다. |
| 접수 응답 | `ClientMessage.resultCode=200` | `productNo`는 상품번호가 아닌 **재고번호**이므로 현재 재고번호와 대조한다. 응답만으로 완료 처리하지 않는다. |
| 완료 | 같은 상품·계정·재고번호 GET | 재조회 수량과 검토 목표가 일치해야 `CONFIRMED_QUANTITY`다. 다른 필드가 일치한다는 의미는 없다. |

현재 지원은 정확히 재고항목 1개, 판매중 `103`, 재고 사용 `01`, 현재 재고 양수인 상품의 **1~999,999개** 판매용 설정 수량이다. 빈 옵션명으로 무옵션을 추정하지 않으며, 여러 재고항목 중 첫 항목이나 합계를 선택하지 않는다. 추가구성상품은 제외한다. 기존 카페24 `variant_code`는 11번가 재고번호로 사용하지 않는다.

0개 저장·전송 및 품절 해제·판매 재개는 이번 계약에 설명이 없어 보류한다. 활성 11번가 연결 상품의 일반 DB 편집은 소수 버림 후 수량이 0이 되어도 `EXCLUDED`이므로 미지원 수량을 먼저 저장하지 않는다. 소싱처의 명시 품절 관측은 사실 그대로 보존하고, 해당 마켓 수량 목표 0은 `SKIPPED` 및 사유로 남긴다. `stopdisplay`/`restartdisplay`는 호출하지 않는다.

업무 오류 `400`, `404`, `500`은 `ELEVENST_BUSINESS_*`로 보존한다. 특히 HTTP 200 안의 업무 오류 500을 HTTP 서버 오류로 오인해 반복 전송하지 않는다. 한 번 재조회한 뒤에도 목표와 다르면 `BLOCKED`로 남아 새 검토가 필요하다. `-1000`은 문서의 서버 점검 코드로 구분한다. HTTP 429의 `Retry-After`와 기존 계정 공용 호출 제한, 응답 유실 시 읽기 우선, 전송 직전 revision/연결/계정 재검증을 유지한다.

스마트스토어는 서버에 이미 구현된 원상품·단일 옵션 수량 지원이 화면 목록에서 빠져 있었다. 수량 모달에 스마트스토어를 복원했고 11번가의 제한 범위를 별도로 표시한다.

운영에서는 상품 328, 284, 3000의 상품 상세와 신규 재고 GET을 읽었으며 모두 `104 / 0 / 02`로 확인됐다. 이어 사용자가 제시한 판매중 상품 1229(`220114OC012`, 11번가 `4088296117`)에서 `103 / 997 / 01`을 확인했다. 정확한 재고번호는 `16243384893`, 현재 `optWght`는 0이다. 상세 응답의 `prdStckQty`는 0, 누적 판매수량 `selQty`는 2였지만 재고 전용 API의 `stckQty`는 997이므로 서로 대신 사용하지 않는다. [실제 응답 fixture](../../backend/infrastructure/src/test/resources/elevenst/stock-selling-2026-09-08.json)의 XML을 EUC-KR로 인코딩한 SHA-256은 수집한 원응답과 일치한다.

판매중 양수 상품과 품절 상품의 **운영 조회 계약은 확인됐다**. DB의 과거 동기화 표시나 소싱처 재고 있음으로 마켓 판매 상태를 대신 판단하지 않는다. 실제 상품 DB 수량 저장이나 외부마켓 PUT은 이번 조사에서 실행하지 않았으므로 운영 전송 성공 검증은 남아 있다.

검증은 전용 어댑터 fixture와 기존 영속 수량 엔진·편집 통합 테스트로 수행한다. namespace 혼합, 정확 SB/상품/재고번호, 누락 수량·무게, 다중 항목, 품절 보류, 필수 4필드 전송, 업무 실패/429/guard abort, 재조회 전 성공 금지, 수량 변경 이력·전용 target, 연결 상품의 0개 저장 차단을 포함한다. 판매중 실응답은 `997` 반환·`writable=true`와 외부 쓰기 0을 검사한다.

검사 결과: 새 어댑터 31건(실제 판매중 XML 회귀 포함) + 영속 수량 엔진 37건 + 기존 DB 편집 35건, 총 **103건 통과**. 프론트 `npx tsc -b`, `npm run build` 및 기존 상품 검색·수량 작업 브라우저 fixture **8개 시나리오**도 통과했다. 실행 로그는 `/private/tmp/sbshop-elevenst-reviewed-stock-tests.log`, `/private/tmp/sbshop-elevenst-selling-stock-proof-tests.log`, `/private/tmp/sbshop-elevenst-stock-ui-{typecheck,build,browser}.log`에 보존했다.

후속: 사용자 승인 후 [11번가 300개 실제 반영·독립 재조회](2026-09-08-elevenst-approved-stock-verification.md)를 완료했다. 위 미실행 설명은 승인 전 조사·배포 시점의 기록이다.
