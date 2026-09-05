# 상품 관리 개편 구현분 운영 배포

사용자 배포 지시로 2026-09-06 02:51 KST 배포 완료. 애플리케이션 버전은 `02142157ac4f4196983027cc0b8b24590f24c215`다.

- 운영 화면: https://168.107.31.154/sbshop-agent/products
- [GitHub Actions 배포 및 헬스체크 성공](https://github.com/goottjason/sbshop-agent/actions/runs/33982134000)
- 배포 전 코드: `04ac0d06513678d2197dc4a87dab87b3648ae6fe`. 서버 작업 디렉터리는 변경 사항이 없는 상태였다.
- 기존 프로젝트별 배포 워크플로로 프론트·API 이미지를 함께 교체했다. 기동 후 두 컨테이너 모두 실행 중이고 재시작 횟수는 0이었다.

## 반영 범위와 남은 작업

복수 SB코드 붙여넣기·브랜드 필터·검색 화면, 촘촘한 목록, 수치 일괄 변경 미리보기, 판매가 100원 반올림·최소마진 보정, 마켓별 가격 계산 근거, 신규 초안 무게 g→kg 변환과 저장 정밀도를 반영했다.

전체 개편은 진행 중이다. 일괄 미리보기의 실제 저장, 새 활성 연결/삭제 이력 모델, 필드 편집 잠금, 콘텐츠 비교 적용, 판매용 재고 300 전환, 조치 필요 우선 정렬 및 새로운 동기화 작업 흐름 등은 [구현 현황](2026-09-06-products-implementation-progress.md)의 미구현 범위에 남아 있다. 미리보기는 저장·마켓 전송 성공을 의미하지 않는다.

## DB 변경과 복구 자료

서버 `/home/ubuntu/backups/sbshop-products-20260906/`에 다음을 보존했다. 비밀번호와 API 키는 이 문서에 포함하지 않는다.

- 전체 sbshop DB 백업 `sbshop-before.dump` (약 8.7 MiB), SHA-256 파일, `pg_restore --list`로 확인한 내용 목록.
- 이전 커밋과 API·프론트 이미지 ID, 확장 DDL 사본 및 전후 무게 집계.
- Docker 이미지 태그 `sbshop-api:before-products-20260906`, `sbshop-frontend:before-products-20260906`.

`public.sb_product.weight`를 `numeric(10,2)`에서 `numeric(13,5)`로 확장했다. 스키마 검사, 잠금 대기 5초, SQL 실행 60초 제한 및 트랜잭션을 적용했다. 기존 값의 단위 변환은 하지 않았다.

| 항목 | 변경 전 | 변경 후 |
| --- | ---: | ---: |
| 전체 상품 행 수 | 3,195 | 3,195 |
| 무게 값이 있는 행 수 | 3,193 | 3,193 |
| 무게 합계 | 1,604.91 | 1,604.91000 |
| 최솟값 / 최댓값 | 0.00 / 6.00 | 0.00000 / 6.00000 |

새 앱 기동 후에도 정밀도가 13/5임을 확인했다. 운영 상품에 검증용 값을 저장하지 않았으며, 소수 5자리 저장·재조회는 앞선 H2 회귀 검사 범위다.

복구가 필요하면 위 보존 이미지로 API·프론트를 함께 재기동하고 nginx를 reload한다. 이전 API는 무게 열을 10/2로 선언하므로 **복구 시 `DDL_AUTO=none`을 지정**하여 확장된 열이 축소되지 않게 한다. 다음은 서버에서 사용할 복구용 Compose 추가 설정이며 이번 배포에서는 실행하지 않았다.

```yaml
services:
  sbshop-api:
    image: sbshop-api:before-products-20260906
    environment:
      DDL_AUTO: none
  sbshop-frontend:
    image: sbshop-frontend:before-products-20260906
```

이 설정을 백업 폴더의 `rollback.yml`로 저장한 뒤 프로젝트 폴더에서 실행한다.

```sh
docker compose -f docker-compose.yml -f /home/ubuntu/backups/sbshop-products-20260906/rollback.yml up -d --no-build --no-deps sbshop-api sbshop-frontend
docker exec projects-nginx-1 nginx -s reload
```

일반적인 코드 복구에는 DB 전체 복원을 실행하지 않는다. 백업 이후 운영 데이터를 덮어쓸 수 있으므로 DB 복원이 필요하면 해당 시점의 변경 내역을 별도로 검토한다.

## 운영 검증

2026-09-06 02:54 KST 실제 Chrome에서 로그인해 운영 API와 화면을 함께 검증했다.

- 조회 대상 상품 2,854개, 브랜드 선택지 356개. 전체 DB 행 수와 조회 대상 상품 수는 삭제 상태 필터 때문에 다르다.
- 쉼표·줄바꿈·중복·소문자를 포함한 SB코드 검색이 정확히 2개 상품을 반환했다. 소싱처·브랜드·재고 상태 조합 조회도 확인했다.
- 수치 필드 API의 무게 정밀도 5, 읽기 전용 판매가 미리보기 `12,345 → 12,300` 확인.
- 샘플 상품의 쿠팡·스마트스토어·11번가·카페24 가격 계산 모두 `CALCULATED`. 최종가 100원 단위와 최소마진 하한 이상을 확인했다. 실제 마켓에 게시된 가격을 조회한 검사는 아니다.
- 미리보기 전후 상품 상세 API 응답이 동일했다.
- 화면에서 SB코드 붙여넣기, 체크박스 선택, 일괄 변경 미리보기, 상세의 마켓별 가격 계산 근거를 조작하고 스크린샷을 확인했다.
- 브라우저 실행 오류와 예상하지 않은 쓰기 요청 0건. 점검 과정에서 상품 저장·크롤·마켓 쓰기 호출은 실행하지 않았다. 기존 운영 스케줄러의 기능은 유지된다.
- 인증 없는 상품 API 요청은 HTTP 401이었다.

검증 자료는 로컬 임시 폴더에 보존했다. 인증정보는 스크립트·결과 파일에 저장하지 않았고 브라우저 세션에서도 제거했다.

- `/private/tmp/sbshop-products-live-check-20260906.mjs`
- `/private/tmp/sbshop-products-live-check-20260906.json`
- `/private/tmp/sbshop-products-live-grid-20260906.png`
- `/private/tmp/sbshop-products-live-numeric-20260906.png`
- `/private/tmp/sbshop-products-live-price-20260906.png`
