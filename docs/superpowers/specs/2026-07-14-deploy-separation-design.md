# 배포 라인 분리 설계 (can-agent · sbshop-agent 독립화)

날짜: 2026-07-14 · 상태: 승인됨(구조), 마이그레이션 대기

## 배경 / 문제
현재 서버 `~/projects/`는 **하나의 compose + 하나의 .env**로 두 독립 프로젝트(can-agent, sbshop-agent)와 공유 인프라(postgres, nginx)를 함께 지휘하는 "한 지붕 두 가족" 구조다. 게다가 두 프로젝트 모두 **GitHub Actions + 웹훅(:9000)** 두 배포 파이프라인이 겹쳐 있어, push 시 동시 실행되어 컨테이너 재생성 충돌(`removal ... already in progress`)로 배포 실패·실패메일이 발생했다.

## 목표
- 프로젝트별로 **`.env` · `docker-compose.yml` · 배포(GitHub Actions)를 완전 분리**.
- `git push` 하면 해당 프로젝트의 **GitHub Actions 하나만** 동작해 그 프로젝트 컨테이너만 교체.
- postgres·nginx는 **공유 인프라로 유지**(A안), 데이터 손실 0.
- 웹훅·중복 파이프라인 제거.

## 목표 구조
```
서버 ~/projects/
├── infra/                         # 공유 인프라 (서버 직접 관리, 어느 repo에도 안 들어감)
│   ├── docker-compose.yml         #   postgres + nginx
│   ├── nginx/default.conf         #   can-agent·sbshop 라우팅
│   └── .env.infra                 #   DB 비번 등 인프라 공용
├── can-agent/        (git repo)
│   ├── docker-compose.yml         #   can-agent 컨테이너만
│   ├── .env                       #   can-agent 전용 env
│   └── .github/workflows/deploy.yml
└── sbshop-agent/     (git repo)
    ├── docker-compose.yml         #   sbshop-api + sbshop-frontend + sbshop-selenium
    ├── .env                       #   sbshop 전용 env
    └── .github/workflows/deploy.yml
```
- 공유 Docker 네트워크 `shared-net`(external)에 세 compose의 컨테이너가 모두 붙는다.
- nginx는 컨테이너를 서비스명(`can-agent`, `sbshop-api`, `sbshop-frontend`)으로 DNS 해석해 라우팅.
- postgres 볼륨은 기존 `projects_pgdata`를 `external`로 재사용 → 데이터 그대로.

## 배포 흐름 (목표)
```
개발자 push (sbshop-agent, main)
      │
      ▼
GitHub Actions (sbshop-agent/.github/workflows/deploy.yml)
      │ SSH
      ▼
서버: cd ~/projects/sbshop-agent && git pull && docker compose up -d --build   (자기 compose만)
      │
      ▼
sbshop-api·frontend·selenium 만 재생성 → nginx reload → 헬스체크
(can-agent·postgres·nginx는 건드리지 않음)
```
can-agent도 동일한 자기 라인. **프로젝트당 파이프라인 1개(Actions), 웹훅 없음.**

## .env 분리 규칙
- 현재 `~/projects/.env`(공유)를 3개로 분할:
  - **인프라**: `~/projects/infra/.env.infra` — DB 접속·비번 등 postgres/nginx 공용.
  - **can-agent**: `~/projects/can-agent/.env` — DART/KOREA_INVESTMENT/TELEGRAM/TOSS 등.
  - **sbshop-agent**: `~/projects/sbshop-agent/.env` — COUPANG/ELEVENST/SMARTSTORE/EMAIL/R2 등.
- 로컬 복제(사용자 요청):
  - `.env.infra` → 로컬 `can-agent/`·`sbshop-agent/` **양쪽 폴더**에 각각 복사.
  - can-agent `.env` → 로컬 `can-agent/.env`(있으면 서버 것으로 덮어씀).
  - sbshop `.env` → 로컬 `sbshop-agent/.env`(덮어씀).
- 모든 `.env*`는 gitignore 대상(커밋 금지).

## 마이그레이션 순서 (안전·롤백 포함)
0. **백업**: `pg_dump`(canagent+sbshop 전체) + 현재 compose/.env/nginx 전부 `.bak` 보존.
1. **중복 파이프라인 제거**: GitHub 웹훅 2개(sbshop 652321601, can-agent 646298737) 삭제 → 이후 push는 Actions만. (무중단)
2. `docker network create shared-net`.
3. 서버에 `infra/`·per-project compose·분할 .env 작성(아직 미기동).
4. **컷오버(짧은 다운타임 ~2-3분)**: 기존 `projects` 스택 `down`(볼륨 유지) → `infra` up → 각 프로젝트 up. postgres는 `projects_pgdata` 재사용.
5. nginx 라우팅 확인·reload.
6. 두 repo의 `.github/workflows/deploy.yml`을 "자기 compose만 배포"로 수정.
7. 검증: 두 앱 200 + DB 데이터 정상 + 각 프로젝트 push→자기 것만 배포.
8. 로컬 .env 복제.
9. 정리: 웹훅 서비스(deploy.py)·구 `~/projects/docker-compose.yml`은 `.bak`로 보관 후 비활성.

## 롤백
문제 시: 새 스택 down → 백업해둔 기존 `~/projects/docker-compose.yml`+`.env`로 `up -d`. postgres 볼륨은 동일하므로 데이터 영향 없음. pg_dump 백업은 최후 수단.

## 리스크
- **데이터**: pgdata 볼륨 external 재사용 + 사전 pg_dump로 이중 안전.
- **다운타임**: 컷오버 시 ~2-3분(양 앱 동시). 사전 이미지 빌드로 단축.
- **nginx 교차 네트워크**: 컨테이너가 shared-net에 없으면 라우팅 실패 → 컷오버 직후 즉시 검증.
