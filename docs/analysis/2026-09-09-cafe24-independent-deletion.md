# 카페24 선행 삭제 허용

사용자 지시: G마켓·옥션은 사용자가 별도 일괄 삭제할 예정이므로 연동 때문에 카페24 삭제를 막지 않는다.

- 변경: ProductManageUseCase의 자식 마켓 미삭제 시 카페24 DELETE를 건너뛰던 조건 제거. G마켓·옥션은 manual로 남기고 상품번호와 상태를 보존한다.
- 카페24 DELETE 이후 동일 계정의 상품 부재 확인은 유지한다. 모든 마켓 삭제 확인 전 SB 소프트 삭제를 보류하는 조건은 유지한다.
- 수동 처리 항목을 활동 로그에 포함하고 WARNING으로 기록한다. 프론트 안내도 카페24 선행 삭제 가능으로 변경했다.
- 검증: 삭제 관련 테스트 27건 통과, 프론트 TypeScript·변경 컴포넌트 ESLint 통과. 전체 core Spotless에는 기존 파일의 포맷 위반이 있어 변경한 Java 두 파일만 개별 포맷했다.
- 배포: d0e174f911a1977a5092a2dee0b16a192b487b47, GitHub Actions 34345586878 성공. 배포 전에 기존 API 로그를 하드링크로 보존했다.

운영 재시도: SB 200901WA005 / productId 41 / 카페24 7867.

첫 요청은 JSON 응답을 받지 못했다. 카페24 GET으로 상품이 여전히 존재함을 확인한 뒤 재시도했다. 두 번째 API 응답은 HTTP 409, deleted=[SMART_STORE,CAFE24], failed={}, manual=[GMARKET,AUCTION], disposed=false였다. 409는 카페24 삭제 실패가 아니라 하위 마켓 수동 처리 미완료를 뜻한다.

삭제 이후 카페24 단건 GET은 product={}, 정확한 product_no 필터 목록 GET은 products=[]로 별도 확인했다. G마켓 3490138764, 옥션 D888922206은 변경하지 않고 연결 정보에 보존했다. SB 상품도 보존 상태다.

근거: /home/ubuntu/backups/sbshop-cafe24-delete-20260909/ 의 before.json, delete-intent*.json, after-first-request-read.json, delete-result.json, after.json, independent-absence-check.json.
