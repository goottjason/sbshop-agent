# SP-A 설계 — 마켓 연동·인증 정상화 (Cafe24 토큰 생명주기 + 설정 페이지 정리)

- 작성일: 2026-07-11
- 상태: 설계 승인됨 (구현 계획 대기)
- 서브프로젝트: SP-A (완성도 정상화 로드맵의 1순위)

---

## 0. 상위 로드맵 맥락

전체 시스템(50→95) 정상화를 위해 6개 도메인을 병렬 감사한 결과, 코드 근거 기반 완성도는 다음과 같다.

| 도메인 | 완성도 | 서브프로젝트 |
|---|---|---|
| A. 마켓 연동·인증 | ~72% | **SP-A (본 문서)** |
| B. 가격/재고·품절 | ~45% | SP-B |
| C. 이미지 파이프라인 | ~72% | SP-C |
| D. 신규 상품 등록 | ~40% | SP-D |
| E. 주문 수집·동기화 | ~72% | SP-E |
| F. 관측성 | ~72% | SP-F |

권장 순서: **SP-A → SP-B → SP-E → SP-C → SP-D → SP-F** (운영 중 깨진 것 우선). 각 서브프로젝트는 독립 스펙→계획→구현 사이클로 진행한다. 본 문서는 SP-A만 다룬다.

---

## 1. 문제 정의 (근본 원인)

사용자 신고: "G마켓/옥션 동기화 스케줄러가 간헐적으로 Cafe24 `invalid_access_token` 오류. refresh_token/access_token 관리가 안 되고 중간에 유실되는 듯."

### 확정된 근본 원인 — 프로세스 간 refresh_token 회전 경쟁

`start.sh`가 **한 컨테이너 안에서 api.jar와 worker.jar를 별도 JVM 2개로 동시 실행**한다.

```bash
java -jar api.jar    --server.port=8080 &   # Cafe24AuthController, 주문 preview
java -jar worker.jar --server.port=8081 &   # OrderSyncScheduler (G마켓/옥션 동기화)
```

- 두 JVM은 각자 자기만의 `Cafe24TokenManager` 빈(인메모리 `accessToken`)을 갖고 하나의 PostgreSQL을 공유한다.
- Cafe24는 refresh 때마다 refresh_token을 **회전(새 값 발급)** 시키며, 현재 코드는 새 refresh_token만 DB에 저장한다 (`Cafe24TokenManager.java:148-150`).
- access_token은 **DB에 저장되지 않는다** — 컬럼(`MarketCredential.java:71-76`의 `access_token`, `token_expires_at`)은 존재하나 인메모리(`Cafe24TokenManager.java:142-145`)에만 둔다.
- 두 JVM 모두 startup(`@PostConstruct init()` → `refreshAccessToken()`, `:46`)과 만료 시(`getValidAccessToken()`, `:60-73`) 각자 refresh를 호출한다.
- `synchronized`(`:60`)는 **JVM 내부 한정** — 프로세스 간 직렬화 장치가 없다.

**실패 시나리오:** worker와 api가 근접 시점에 refresh하면 둘 다 DB에서 같은 refresh_token(RT2)을 읽는다 → worker가 RT2→RT3 교환(RT2는 Cafe24에서 무효화) → api의 RT2 교환은 실패 → api 토큰 null. 한쪽이 회전시키면 다른 쪽의 다음 refresh가 깨진다. 그래서 **간헐적**이다. 또한 매 배포/재시작마다 두 JVM이 불필요하게 2회 회전시켜 만료 사고 노출을 키운다.

→ 단일 인스턴스 버그가 아니라 **프로세스 간 토큰 회전 경쟁**이다.

---

## 2. 목표 & 성공 기준

- 배포·동시 실행·토큰 만료 상황에서 `invalid_access_token`이 재발하지 않는다.
- refresh_token은 **한 번에 한 프로세스만** 회전시킨다(동시 refresh 시 실제 HTTP refresh는 1회).
- 재시작 시 유효 토큰이 DB에 있으면 refresh를 호출하지 않는다(불필요한 회전 제거).
- 설정 페이지에서 죽은 ESM+(G마켓·옥션 단일 로그인) 섹션이 사라진다.
- refresh_token이 결국 만료되면 설정 페이지 배지에 `재인증 필요`가 명확히 표시된다.

---

## 3. 설계

### 3.1 `Cafe24TokenManager` 재설계 — DB 단일 진실원 + advisory lock

- 인메모리 `accessToken`/`tokenExpiresAt`를 진실원에서 제거. 매 호출 시 DB에서 읽어 만료 판정(단일 인덱스 행, 저비용).
- `getValidAccessToken()`:
  1. DB 읽기 → `access_token` 존재 && `token_expires_at > now + 5분` → 그대로 반환.
  2. 아니면 `refreshUnderLock()` 진입.
  3. 그 후에도 null → 기존 fail-fast throw 유지 (`Cafe24TokenManager.java:68-71`).
- `refreshUnderLock()` — `@Transactional`:
  1. **advisory lock 획득**: `pg_advisory_xact_lock(<CAFE24 상수 키>)` — 트랜잭션 종료 시 자동 해제.
  2. **재확인(double-check)**: 락 대기 중 다른 프로세스가 이미 갱신했으면 유효 토큰 반환, HTTP 호출하지 않음.
  3. 여전히 만료면 Cafe24 refresh 1회 호출 → 응답의 `{access_token, token_expires_at, refresh_token}` **3종 모두 DB 저장** (현재는 refresh_token만 저장).
- `@PostConstruct init()`: **startup 강제 refresh 폐지**(`:46`의 `refreshAccessToken()` 호출 제거). 토큰/설정 존재 여부만 로그. 실제 필요 시 첫 사용 시점에 lazy refresh.

### 3.2 `TokenRefreshLock` 포트 (신규)

- `core`에 포트 인터페이스, `infrastructure`에 Postgres 구현(`JdbcTemplate`으로 `SELECT pg_advisory_xact_lock(?)`).
- 목적: 락 로직을 추상화해 로컬에서 Docker 없이도 double-check·단일-refresh 로직을 가짜(fake) 락으로 단위 테스트 가능하게 함(하네스의 testcontainers/Docker-off 제약 회피). Postgres 구현체 자체는 CI 통합테스트로 검증.
- 인터페이스 개략:
  - `<T> T runExclusively(long key, Supplier<T> action)` — 락 획득 후 action 실행, tx 경계 내에서 자동 해제.

### 3.3 프론트 설정 정리 — `frontend/src/pages/Settings.tsx`

- GMARKET 탭 항목 제거 (`:84` 부근의 `{ id: 'GMARKET', label: 'G마켓·옥션 (ESM+ 단일 로그인)' }`).
- `activeTab === 'GMARKET'` 폼 블록 제거 (`:318-348` — 마스터 ID/비밀번호 입력 폼).
- 제거 전, 해당 GMARKET 자격증명을 읽는 다른 코드가 없음을 확인(감사 결과: 백엔드가 Cafe24 경유로 전환되어 미사용). 다른 마켓 저장 흐름에 영향 없음을 확인.
- Cafe24 탭 상태 배지가 `재인증 필요` 상태를 명확히 표기(기존 status 엔드포인트가 유효성·스코프 검증 → 텍스트/상태만 확정).

### 3.4 데이터 흐름 (동시 refresh)

```
worker: getValidAccessToken → 만료 → lock 획득 → HTTP refresh → {AT, expiry, RT2} 저장 → commit(lock 해제)
api   : getValidAccessToken → 만료 → lock 대기 …… 획득 → 재확인=유효 → HTTP 없이 반환
```

→ refresh_token은 한 번만 회전, 두 프로세스가 같은 최신 토큰을 공유.

---

## 4. 에러 처리

- refresh 실패(refresh_token 만료/무효): `getValidAccessToken()`의 fail-fast throw 유지 + ERROR 로그 + status 엔드포인트에서 `재인증 필요` 표면화.
- advisory lock이 외부 HTTP를 트랜잭션 안에서 잡는 창은 refresh가 드물고(~2h 주기) 짧아(~1s) 허용. statement timeout 가드로 무한 대기 방지.

---

## 5. 테스트 전략 (TDD Red→Green)

재현 테스트 없는 수정 금지(하네스 규율).

1. **동시 refresh 재현 테스트**: 만료된 DB 토큰 상태에서 N개 스레드가 동시에 `getValidAccessToken` 호출 → Cafe24 refresh HTTP는 **정확히 1회**, refresh_token 1회 회전, 모든 스레드가 동일 유효 토큰 수신. (가짜 `TokenRefreshLock` + `MockRestServiceServer`로 로컬 실행 가능.)
2. **startup no-refresh**: 유효 토큰이 DB에 있을 때 `@PostConstruct` 후 refresh HTTP 호출 0회.
3. **유효 토큰 재사용**: DB 토큰이 유효하면 HTTP 호출 0회로 반환.
4. **refresh 실패 fail-fast**: refresh_token 무효 시 `IllegalStateException` throw + 상태 표면화.
5. **Postgres advisory lock 통합 테스트**: 실제 `pg_advisory_xact_lock` 동작 검증 (Testcontainers, CI/서버에서 실행 — 로컬 Docker-off 시 스킵 가능).

프론트: `tsc -p tsconfig.app.json` 0, `npm run build` 0. GMARKET 탭 제거 후 다른 탭 렌더·저장 회귀 없음 확인.

---

## 6. 범위 밖 (명시)

- 적극 push/대시보드 배너 알림 → **SP-F** (본 SP-A는 설정 배지 + 로그의 최소 표면화까지).
- 주문 발주확인/취소 Selenium 청산, ESM+ 잔재 주입 제거 → **SP-E**.
- Cafe24 외 마켓(쿠팡/스마트스토어/11번가) 토큰 처리.
- Redis 분산 락(동일 Postgres 공유 환경에서 advisory lock으로 충분).
- DDL 변경 — `access_token`/`token_expires_at` 컬럼이 이미 존재하므로 **불필요**.

---

## 7. 영향 파일 (예상)

| 파일 | 변경 |
|---|---|
| `backend/infrastructure/.../cafe24/Cafe24TokenManager.java` | DB 진실원화, refreshUnderLock, startup refresh 제거, 3종 저장 |
| `backend/core/.../domain/market/...` (신규 `TokenRefreshLock` 포트) | 락 추상화 인터페이스 |
| `backend/infrastructure/.../<lock 구현>` (신규) | Postgres advisory lock 구현 (JdbcTemplate) |
| `backend/core/.../domain/market/MarketCredential.java` | (필요 시) access_token/expiry setter 활용 — 컬럼 이미 존재 |
| `frontend/src/pages/Settings.tsx` | GMARKET 탭·폼 제거, Cafe24 배지 `재인증 필요` |
| 신규 테스트 (infrastructure) | 동시 refresh·startup·재사용·fail-fast·advisory lock 통합 |

---

## 8. 검증/배포

- 코드 게이트: `:core:test`, `:infrastructure:test`, `:api:test`, 프론트 `tsc`/`build`.
- 라이브 확인(배포 후, 사용자 허가 하): 배포 직후 두 JVM 동시 기동 시 refresh 회전 1회만 발생하는지 로그 확인, G마켓/옥션 스케줄러 주기(10/30분) 동안 `invalid_access_token` 무재발 관찰.
- push/배포는 사용자 확인 후.
