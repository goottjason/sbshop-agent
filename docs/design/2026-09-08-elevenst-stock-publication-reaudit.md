2026-09-08 재조사 결과, **11번가 수량 전용 변경과 신규 등록을 개방할 추가 원문은 프로젝트·Downloads에서 찾지 못했다.** 기존 상품수정·옵션수정 명세에 수량 필드는 존재한다. 다만 부분 수정 보장과 0개/판매 재개 조건이 부족해 현재 검토형 판매용 수량 큐의 쓰기 계약으로 대체할 수 없다.

이 감사는 파일 읽기와 PDF/XLSX 추출만 수행했다. 외부 API·브라우저·운영 DB를 호출하지 않았으며 코드·배포·기존 문서는 변경하지 않았다. `/`, `/Users`, `/Users/jasonair`, `/Users/jasonair/Projects`, 프로젝트 루트, Downloads 및 두 조사 트리 안에서 적용되는 `AGENTS.md`는 발견되지 않았다. 다른 프로젝트의 AGENTS는 적용하지 않았다.

조사 범위와 사본 비교:

- 저장소 `docs/external-api/elevenst` PDF 11개, Downloads의 11번가 관련 PDF 10개, 2023 상품정보제공고시 XLSX를 읽었다. macOS 분해형 한글 파일명은 NFC 정규화해 비교했다.
- Downloads 상품 관련 PDF **9개 모두** 저장소 사본과 SHA-256이 같다. 나머지 Downloads PDF는 발송처리 문서이며 수량 변경/신규 등록 명세가 아니다. 파일 수정 시각만으로 최신 API라고 판단하지 않았다.
- Downloads 하위 `기타파일/purchase-agent`와 `purchase-agent.zip`도 확인했다. 압축에는 기존 애플리케이션 소스·HTML 템플릿이 있으며 신규 11번가 API 원문은 발견되지 않았다. 레거시 소스는 공식 명세로 사용하지 않았다.
- 예전 `11번가_상품수정.pdf`는 33쪽이며 한글 글꼴 추출이 깨진다. 핵심 의미는 한글이 정상 추출되는 14쪽 `11번가-상품수정.pdf`와 대조했다. `신규상품조회.pdf`는 총 **3쪽**이고 주요 필드가 1~2쪽에 있다.

| 원문/위치 | 확보한 실제 계약과 조건 | 이번 범위에서 부족한 점 |
|---|---|---|
| [상품수정 PDF](../external-api/elevenst/11번가-상품수정.pdf) p1 | `PUT /rest/prodservices/product/[prdNo]`, XML 본문. 기존 데이터를 수정 XML로 교체하며 등록처럼 전체 XML을 보내라고 설명 | 수량 태그만 보내도 다른 값이 보존된다는 근거가 없음 |
| 같은 문서 p8~9 | `prdSelQty` 표기는 String/X지만 설명은 재고 입력 필수. 옵션 상품은 입력값 대신 옵션 수량 합계 반영. **0 입력 불가**, 판매중지 별도 처리 안내 | 사용자 요구의 명시 품절 0개와 판매정지/금지 자동 재개 금지 정책에 맞는 수량 전용 경로가 필요 |
| [옵션 수정 PDF](<../external-api/elevenst/11번가 상품 옵션 수정.pdf>) p1~3 | `POST /rest/prodservices/updateProductOption/[prdNo]`, `Product`. 멀티옵션 `optionAllQty`, 단일 `ProductOption.colCount`, 선택형 멀티 `ProductOptionExt/ProductOption.colOptCount` | 옵션 전체 구조·가격·상태의 보존과 재고번호 대응을 확인해야 하므로 수량만 넣는 요청을 추정하지 않음 |
| 같은 문서 p1~3 | `optionAllQty`는 멀티옵션 일괄 수량, 단일 옵션에서는 생략. `colCount=0`은 `useYn=N`일 때만 허용. `colOptCount`는 `optMixYn=N` 조건. `colSellerStockCd`, `optionMappingKey`, 옵션 가격·값 필드가 함께 필요. `dlvClf=03` 또는 전세계배송의 `optWght`는 g 단위 필수 | 개별 옵션 식별자와 읽기 응답 대응, 멀티/단일 경계, 품절 옵션을 다시 활성화하는 동작에 대한 검증 부족 |
| [상품재고무게변경 PDF](<../external-api/elevenst/11번가 상품재고무게변경처리_PUT방식.pdf>) p1 | `PUT /rest/prodservices/stockwght/[prdStckNo]`; 필수 `ProductStock.prdNo`, `prdStckNo`, **optWght**. 결과 ClientMessage에는 옵션 무게 변경 설명과 resultCode | **옵션 무게** API다. `stock`이라는 경로·파일명만으로 수량 변경으로 해석할 수 없음 |
| [신규상품조회 PDF](../external-api/elevenst/신규상품조회.pdf) p1~2, [상품조회 PDF](../external-api/elevenst/11번가-상품조회.pdf) | `GET /rest/prodmarketservice/prodmarket/[prdNo]`, `prdNo`, `sellerPrdCd`, `htmlDetail`, `selStatCd`. `optionAllQty`는 조합형 옵션 일괄 수량이며 무옵션이면 무시하라고 명시 | 문서 제목의 ‘신규’는 신규상품 **조회**를 뜻한다. 상품 생성 요청 명세가 아님. `optionAllQty`를 모든 상품의 실제 판매 재고로 사용하면 안 됨 |
| 기존 인증 GET 증거 `/private/tmp/sbshop-final-contracts/current-market-read.json`의 elevenst 항목 | 실제 응답의 태그 목록에 **prdStckQty**가 존재하고 상품번호/SB 확인 결과가 보존됨. 앞선 상세 검토 [문서](2026-09-08-elevenst-reviewed-detail.md)도 이 읽기 경로를 사용 | 읽기 태그 존재는 같은 태그로 쓰기가 가능하다는 근거가 아님. 과거 전체 계정 귀속을 증명하지도 않음 |
| [2023 고시 목록](/Users/jasonair/Downloads/11번가_2023상품정보제공고시_전체목록.xlsx) Sheet1 rows 209~232 | `891031` 가공식품 11항목, `891032` 건강기능식품 13항목의 코드/명칭. 컬럼은 고시 유형 코드·유형 명·항목 코드·항목명·입력 방식 | 2023 파일이 현재 모든 카테고리의 필수 조건을 완전히 대변한다는 근거는 없음. 상품별 실제 문구·원산지·인증 값을 만들어낼 수 없음 |

신규 등록에 관해 확인된 것은 **기존 구현의 존재**이며, 검토형 등록의 원문 계약 확보와 구별한다. [ElevenstMarketClient](../../backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/adapter/ElevenstMarketClient.java)의 `publish`는 `POST /rest/prodservices/product`를 호출한다. 그러나 전체 요청의 최신 필수 조건·계정별 배송 주소/템플릿·성공 및 심사 응답·응답 불명 시 중복 탐색 규칙을 증명하는 신규 등록 원문이 없다. 현재 빌더는 DB 소싱 재고 또는 999를 `prdSelQty`로 넣고 여러 주소·배송·원산지 값을 기본값으로 사용하며 `sellerPrdCd`를 생성 XML에 넣지 않는다. 이 구현이나 목(mock) 테스트를 판매용 수량 기본 300, 정확한 SB 식별, 안전한 재등록 계약의 근거로 재사용해서는 안 된다. 사용자가 정정한 `makerNm`도 근거 필드로 사용하지 않는다.

현재 수량 서비스의 지원 목록은 쿠팡·카페24·스마트스토어이며 11번가는 제외되어 있다. 검토형 등록 서비스도 같은 세 직접 마켓만 지원한다. 기존 `syncPriceAndStock`의 11번가 구현은 quantity 인자를 전송하지 않고 판매중지/재개를 수행하므로 이를 수량 동기화 성공 경로로 연결하면 안 된다. 이 감사에서는 잠금이나 지원 목록을 변경하지 않았다.

다음 작업과 필요한 자료는 아래와 같이 한정한다.

| 구분 | 구체적인 다음 단위 |
|---|---|
| 현재 읽기 근거로 준비 가능 | 기존 단건 GET의 `prdStckQty`를 정수로 엄격하게 읽고, 계정·prdNo·sellerPrdCd·selStatCd를 함께 검증하는 읽기 DTO/계약 회귀 준비. 이 작업만으로 쓰기 지원이나 동기화 성공을 개방하지 않음 |
| 수량 쓰기 전 필요한 원문 | 공식 개발 가이드 **공통 API → 상품 → 재고처리** 안의 ‘재고정보 조회’와 ‘수량 변경’ 페이지 전체. 정확한 메서드/URL, 상품 및 옵션 식별자, 요청·응답 XML, 0 허용 및 상태 전환, 수량 상한/오류·429 기준 포함 |
| 신규 등록 전 필요한 원문 | **공통 API → 상품 → 상품관리 → 상품등록 / 신규상품등록** 페이지 전체. 카테고리별 필수 입력·상품 고시·원산지/인증·배송/반품 주소 조회·이미지 조건, 신규 상품번호/승인 상태 응답, 판매자상품코드 조회 및 중복 탐색 계약 포함 |
| 자료 확보 뒤 첫 구현 단위 | 옵션 없는 단일 상품부터 `salesQuantity` 목표값과 실제 수량을 별도 GET으로 대조. 검토 승인·수정 revision·기존 429 gate·쓰기 의도·응답 불명 시 재조회/중복 방지 패턴을 적용하고, 삭제/금지 상품의 재등록은 기존 정책을 유지 |

Q27은 ‘11번가 재고 자료 전체 없음’이 아니라 위 **수량 전용 쓰기·조회 및 상태 전환 상세 계약 부족**이다. Q26의 과거 계정 귀속과 Q28의 가격 인상 시 협의 혜택 정책은 이번 로컬 문서 재조사로 해결되지 않았다. 이미 완료한 인증 GET·상세 HTML 준비를 다시 미완료로 돌리지 않았다.

아래 해시는 이번에 직접 읽은 저장소 PDF의 식별 근거다. 원문 추출 임시 자료는 `/private/tmp/sbshop-elevenst-stock-publication-reaudit/manifest.json`과 같은 폴더의 페이지별 TXT에 보관했다.

| 저장소 PDF | 쪽수 | SHA-256 |
|---|---:|---|
| 11번가 상품 추가구성상품 수정.pdf | 2 | `176b9dd7b855d32bf5ddba83559eca116adc32e2b4e8d7af3449243bf2cc9629` |
| 11번가 상품재고무게변경처리_PUT방식.pdf | 2 | `59add333d931451d590e3a937a6d2fba28fcea20269c73e819be5c11084170f4` |
| 11번가 상품 추가 구성상품 조회.pdf | 2 | `efb578d4db8b524f12dd39780a379e460feeb482cf6622f4f801d0f0af83619d` |
| 신규상품조회.pdf | 3 | `7058f4eef4ddb71841b0b468c5c6ba56a2c946d8f456564a211b9fa498746fb0` |
| 11번가 상품 옵션 수정.pdf | 3 | `64296519af4daf899459e8122c215654b22bc4db7c492fc6d7e0eb45305e8f10` |
| 11번가-상품수정.pdf | 14 | `410e10281192f975e8500e800bbe03a95240f91f594d2a4e91afbb67e0a761e7` |
| 다중상품조회.pdf | 2 | `5fe1ea2d4e6658b8050e782849458fd85efee5f1db291b31cd2f22ce935f7367` |
| 11번가 무게 포함 신규상품조회.pdf | 1 | `f251c06c0b50ec132f73477d8193ded72f455d9c73121c8a908b758b20774da8` |
| 11번가-상품조회.pdf | 2 | `f0b7d81d9b65d6c223e166e27270db0330a19f63a64757973728365eb96a6f4f` |
| 11번가_상품수정.pdf | 33 | `8d45511bd76f757dc6344dcae865f4229dc81c68c8708053ee8515a5d31068e2` |
| 11번가_상세설명수정.pdf | 2 | `c0828ef54c2f3e9bd698eb16f48af7c5bcb11cb668f3b57703a8f392efa6cdc0` |

고시 XLSX SHA-256: `62998d57cd121694195f8fc9ad6bdb9eb888597386835c95eb773133c5a0d4df`. API 키·토큰·계정 비밀값은 조사 보고서에 포함하지 않았다.

후속 확인: 이 감사 작성 뒤 같은 대화에서 사용자가 쿠팡·11번가·카페24의 기존 상품은 각 현재 단일 계정에 속한다고 명시했다. 따라서 위 Q26 미확보 기록은 작성 당시 상태이며, 계정 귀속 사용자 확인은 해결됐다. 실제 자동 해제는 현재 계정 참조를 고정한 상태에서 정확한 상품 부재 응답을 확인하는 구현·검증과 별개이며, 일반 404/권한 오류를 상품 삭제로 해석하지 않는다.
