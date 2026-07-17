# 소싱 이메일 계정 · 시크릿 정리 (이메일 펫칭 설정 대상)

> **작성:** 2026-07-14 · **출처:** `order_list.xlsx` 구매정보 채우기 작업에서 추출한 소싱 이메일 리스트(session `8fd9b546` scratchpad `parsed.csv`·`fill_results.csv`)를 현재 `.env` 시크릿과 대조.
>
> **목적** — iHerb 발송/확인 메일 IMAP 펫칭이 **모든 소싱 계정**에서 동작하도록, 시크릿(앱 비밀번호)이 **없는 계정을 식별**하고 생성 작업 목록을 남긴다. 관련 파이프라인: [[iherb-email-tracking-pipeline]], 흐름 분석 `docs/api-analysis/email-fetch/fetch.md`.
>
> **시크릿 값 취급** — 이 문서에는 **마스킹(앞 4자)** 만 기록한다. 실제 앱 비밀번호는 `.env` 파일에만 유지한다.

## 1. 요약

| 항목 | 값 |
|------|-----|
| 소싱에 쓰인 실제 이메일 계정 | **23개** (파싱 쓰레기값 `달콤이@?`·`반품@?상품으로` 2건 제외) |
| 제공자 분포 | Gmail **14** / 비-Gmail **9** (hanmail 4·naver 3·nate 1·skku 1·apple 1) |
| **시크릿 보유(펫칭 가능)** | **3개** — `369butterfly369` · `shouldbe.shopping` · `gootkimjw8712` (전부 Gmail) |
| **시크릿 없음(생성 필요)** | **20개** (Gmail 11 + 비-Gmail 9) |

## 2. 결정사항 (2026-07-17)

> 아키텍처 실측: 펫칭 코드(`EmailFetcherService`)는 이미 **계정별 host/port/protocol을 지원**한다(`getHost()/getPort()/getProtocol()`). "Gmail 하드코딩"은 코드가 아니라 **`application.yml`이 슬롯을 3개로 고정 + 각 슬롯 host를 `imap.gmail.com`으로 고정**한 데서 온 제약이었다.

| # | 사안 | 결정 |
|---|------|------|
| ① | 유사 계정군(younzara@ 4제공자, kim* 계정군)의 성격 | **모두 별개 사서함** — 23개 각각 실제 펫칭 대상 계정 |
| ② | 비-Gmail(hanmail/nate/naver/skku/apple) IMAP 지원 | **코드는 Gmail만 지원** — 비-Gmail은 iHerb 발송메일을 **대표 Gmail로 IMAP 포워딩** 후 수집(운영 우회, 코드 변경 최소) |
| ③ | 계정 수 확장·`.env` 정리 | 3슬롯 고정 해제를 위해 **`EMAIL_ACCOUNTS`(단일 env, `user:pass` 목록)** 도입 + `.env` 4종 통일 |

**구현 결과(커밋):**
- `EmailAccountProperties.parseCompactAccounts()` — `EMAIL_ACCOUNTS` 컴팩트 목록을 Gmail 계정으로 파싱(쉼표·줄바꿈 구분, 공백 앱비번 보존, 빈/오류 항목 스킵). yml 슬롯과 **병합**(하위호환). 단위테스트 6케이스.
- `api/application.yml` 슬롯 3개로 통일(worker와 일치) + 두 yml에 `EMAIL_ACCOUNTS` 안내.
- `.env.example`에 `EMAIL_ACCOUNTS` 형식·Gmail전제·포워딩 안내.

**남은 수동 작업(사용자):**
- Gmail 11개 앱 비밀번호 생성(2단계 인증 → 앱 비밀번호) → 서버 `~/projects/.env`의 `EMAIL_ACCOUNTS`에 추가.
- 비-Gmail 9개 → 대표 Gmail로 IMAP/자동 포워딩 설정.

## 3. 계정 인벤토리

### 3-a. Gmail (펫칭 가능 대상)

| 이메일 | 소싱건수 | 시크릿 | 마스킹 | 슬롯 |
|--------|:---:|:---:|:---|:---:|
| `369butterfly369@gmail.com` | 27 | ✅ | `wmdj****` | `.env` _3 |
| `shouldbe.shopping@gmail.com` | 22 | ✅ | `kcxk****` | `.env` _2 |
| `inegg@gmail.com` | 22 | ❌ | — | — |
| `mariahcarey0815@gmail.com` | 19 | ❌ | — | — |
| `kimjw8712@gmail.com` | 18 | ❌ | — | — |
| `spreadyourwings33@gmail.com` | 18 | ❌ | — | — |
| `younzara@gmail.com` | 17 | ❌ | — | — |
| `tomkim8712@gmail.com` | 16 | ❌ | — | — |
| `kimsubi.0007@gmail.com` | 10 | ❌ | — | — |
| `gootkimjw8712@gmail.com` | 10 | ✅ | `fcbg****` | `.env` _1 |
| `goottjason@gmail.com` | 9 | ❌ | — | — |
| `kimjongwon0907@gmail.com` | 8 | ❌ | — | — |
| `kimshou31@gmail.com` | 7 | ❌ | — | — |
| `kimshou825@gmail.com` | 6 | ❌ | — | — |

### 3-b. 비-Gmail (IMAP 설정 선행 필요)

| 이메일 | 소싱건수 | 제공자 | 시크릿 |
|--------|:---:|:---|:---:|
| `dnglglzpzp@hanmail.net` | 21 | hanmail | ❌ |
| `younzara@nate.com` | 20 | nate | ❌ |
| `tonyworld@hanmail.net` | 19 | hanmail | ❌ |
| `oasis_0907@hanmail.net` | 17 | hanmail | ❌ |
| `jongwon@skku.edu` | 12 | skku | ❌ |
| `younzara@naver.com` | 12 | naver | ❌ |
| `younzara@apple.com` | 11 | apple | ❌ |
| `palme86@naver.com` | 10 | naver | ❌ |
| `ordinary_things@naver.com` | 6 | naver | ❌ |

## 4. `.env` 파일 간 불일치 (시정 대상)

| 슬롯 | 루트 `.env` (운영, 3슬롯) | `backend/.env`·`api/.env`·`worker/.env` (2슬롯) |
|:---:|---|---|
| _1 | `gootkimjw8712@gmail.com` `fcbg****` | `shouldbe.shopping@gmail.com` `kcxk****` |
| _2 | `shouldbe.shopping@gmail.com` `kcxk****` | `369butterfly369@gmail.com` `wmdj****` |
| _3 | `369butterfly369@gmail.com` `wmdj****` | (없음) |

- 루트 `.env`에 죽은 레거시 키 `EMAIL_ADDRESS=`/`EMAIL_PASSWORD=`(빈값) 잔존 → 제거 대상.
- backend 하위 `.env` 3종은 슬롯 3 확장(커밋 `7da766f`) 미반영 + 슬롯 번호가 루트와 어긋남 → 루트 기준으로 통일 필요.

## 5. 작업 체크리스트

- [ ] **대표 계정 확정** — §2-2 다중 계정군에서 실제 로그인/펫칭할 계정만 선별
- [ ] **Gmail 11개 앱 비밀번호 생성** (2단계 인증 → 앱 비밀번호) 후 슬롯 `_4`~ 로 `.env` 추가
- [ ] **비-Gmail IMAP 지원 결정** — 코드에 제공자별 IMAP 호스트 설정 추가할지 / 해당 계정을 Gmail 포워딩으로 우회할지
- [ ] **`.env` 파일 통일** — 루트 기준 슬롯 번호 정렬, backend 하위 3종 동기화, 레거시 `EMAIL_ADDRESS`/`EMAIL_PASSWORD` 제거
- [ ] 계정 추가 후 `docker exec ... curl localhost:8081/internal/email/fetch` 로 펫칭 실동작 검증
