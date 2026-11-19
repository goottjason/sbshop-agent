# sbshop 운영 DB 쓰기 도구 (`sbshop_db_write.py`)

운영 DB의 **데이터 정정(UPDATE·DELETE·INSERT)은 이 도구로만 한다.** 사람이 지키던 절차 — 한 트랜잭션, 백업 테이블, 예상 건수 대조, 쓰기 전 덤프 — 를 도구가 강제한다. DDL은 대상이 아니다.

설계: [`docs/normalize/plans/D-364_운영DB-쓰기도구-설계.md`](../../docs/normalize/plans/D-364_운영DB-쓰기도구-설계.md) (결함 D-364)

| 파일 | 내용 |
|------|------|
| `sbshop_db_write.py` | 도구. 파일 하나, 표준 라이브러리만(Python 3.11+) |
| `test_sbshop_db_write.py` | 단위 시험(DB 없음, 가짜 psql) |
| `test_integration_pg.py` | 실 DB 통합 시험(환경 변수가 없으면 건너뜀) |

## 무엇을 막는가

- **자유 SQL을 받지 않는다.** 작업 파일(TOML)에 조각(`where`·`set`·`from`·`source`)만 쓰고 문장은 도구가 조립한다. update·delete는 WHERE를 빠뜨릴 수 없고 DROP·TRUNCATE는 표현할 수 없다. 무조건 조건(`where = "true"`)은 표현되지만 건수 대조(`expect`)와 대량 표시(`--bulk`)가 잡는다.
- **백업한 행 = 바꾼 행.** update·delete는 조건을 단계마다 **한 번만** 평가해 대상 행의 식별자(ctid) 배열을 만들고(`phase=target`에서 건수 대조), 백업과 변경은 그 배열로 한다. `LIMIT`(ORDER BY 없음)·`random()` 같은 비결정 조건이어도 백업과 변경이 갈라지지 않는다. from 있는 update만 SET에 쓸 조인 값 때문에 조건을 다시 쓰되 대상은 배열 안으로 묶인다(다른 행을 고르면 `phase=affected`로 전체 롤백).
- 한 트랜잭션(`REPEATABLE READ`) 안에서 단계마다 **백업 테이블을 먼저** 만들고, 백업 행 수와 영향 행 수가 모두 `expect`와 같을 때만 다음으로 간다. 하나라도 다르면 전체 롤백. 건수 대조는 서버 안(`DO` 블록)에서 한다.
- `lock_timeout=5s`, `statement_timeout=120s` 고정.
- **연쇄 쓰기가 일어날 수 있는 테이블은 거부한다**: 사용자 트리거(`has_trigger`)·규칙(`has_rule`)·상속 자식(`has_children`)이 있는 테이블, delete는 ON DELETE가, update는 ON UPDATE가 NO ACTION/RESTRICT가 아닌 FK로 참조되는 테이블(`cascade_fk`). 다른 테이블의 행이 따라 바뀌면 백업에도 건수 대조에도 안 잡히기 때문이다.
- 백업 테이블 이름은 `bak_d{결함번호}_{접미}`. 이미 있으면 실행하지 않는다 — 같은 작업을 두 번 apply해도 두 번째는 실패한다.
- `apply`는 ① 같은 파일의 dry-run 성공 기록 ② `--confirm <해시 앞 12자>` ③ 30분 이내 전체 덤프 ④ **사전 확인** — 읽기 전용 역할(`sbshop_ro`)로 이 작업의 백업 테이블이 하나도 없음을 확인(확인 자체가 안 되면 거부)이 모두 있어야 돈다. 건너뛰는 옵션은 없다. ④ 덕분에 "실행 뒤 백업 테이블이 있다 = 이번 실행이 커밋됐다"가 모호하지 않고, 쓰기 뒤 확인 경로가 살아 있는지를 쓰기 전에 본다.
- 대량(단계 `expect` 합 200 초과, 또는 어느 단계가 그 테이블 행의 5% 초과)은 `apply --bulk`가 있어야 한다.
- 접속 대상(컨테이너 `projects-postgres-1`, DB `sbshop`, 쓰기 역할 `sbshop`, 확인 역할 `sbshop_ro`)과 경로는 코드 상수다. 환경 변수·인자로 바꿀 수 없다. 홈 폴더는 `HOME`이 아니라 계정 DB(`pwd`)에서 얻고, psql(docker)에는 부모 환경을 물려주지 않고 고정 환경(`PATH=/usr/sbin:/usr/bin:/sbin:/bin`, `HOME`, `LANG=C.UTF-8`)만 준다 — `DOCKER_HOST`·`DOCKER_CONTEXT`가 docker에 닿지 않는다. docker는 `--context default`로 부른다(계정에 저장된 컨텍스트에 기대지 않는다).
- **`python3 -I`(격리 모드)로만 실행된다.** 아니면 사용법 오류(64)로 끝난다 — `PYTHONPATH`·사용자 site·현재 폴더의 파일이 표준 라이브러리 대신 끼어들지 못하게 한다.

조각 검사는 실수 방지 장치이지 보안 경계가 아니다. 부작용 있는 함수 호출(`setval` 등)은 가려내지 않는다.

## 작업 파일

```toml
defect = "D-999"                 # 필수. 백업 테이블 접두 bak_d999_
title  = "주문 3건 상태 정정"      # 필수, 한 줄 120자 이내. 개인정보를 쓰지 않는다. SQL·로그에 들어가지 않는다

[[step]]
kind   = "update"                # update | delete | insert
table  = "sb_order"              # public의 일반 테이블. bak_·_bak·zz_·pg_ 로 시작하면 거부
where  = "id IN (101, 102, 103)" # update·delete 필수. 괄호로 감싸 조립된다
set    = "status = 'CANCELED'"   # update 필수
expect = 3                       # 필수, 1 이상의 정수
backup = "order_status"          # 선택. 기본값 s{단계번호}_{table} → bak_d999_order_status

[[step]]
kind   = "delete"
table  = "sb_order_item"
from   = "sb_order o"            # 선택. 대상을 고를 때만 조인한다(delete에 USING은 없다). update는 SET에서도 쓴다
where  = "sb_order_item.order_id = o.id AND o.id = 101"
expect = 2

[[step]]
kind    = "insert"
table   = "sb_example"
columns = "a, b"                 # 선택. 식별자 목록만
source  = "VALUES (1, 2)"        # 필수. VALUES 또는 SELECT로 시작. ON CONFLICT DO NOTHING 가능(DO UPDATE는 불가)
expect  = 1
```

- 모르는 키(오타 `expct`), 빠진 키, 종류에 맞지 않는 키, 타입 불일치는 전부 거부한다. 단계는 1~20개.
- 대상 테이블은 조립 SQL에서 `public.T AS T`로 별칭이 붙는다. 조각에서 `T.col`로 가리킬 수 있다.
- 조각의 문자열(`'…'`, `''` 이스케이프)과 큰따옴표 식별자 바깥에는 `;`, `--`, `/*`, `$`를 쓸 수 없고, 역슬래시·제어 문자는 어디에도 쓸 수 없다. 금지어(`insert update delete merge truncate drop alter create grant revoke into copy call execute commit rollback`)도 문자열 밖에서는 거부된다. 문자열 안의 `;`·`--`·한글은 그대로 저장된다. 큰따옴표 식별자 안에는 줄바꿈·탭을 쓸 수 없고 63바이트를 넘을 수 없다(서버의 "identifier will be truncated" 알림에 원문이 실려 표지 줄처럼 보이는 것을 막는다).
- 작업의 정체는 **파일 바이트의 SHA-256**이다. 한 글자라도 고치면 다른 작업이 되어 dry-run부터 다시 한다.

## 명령

```
python3 -I ~/ops-write/sbshop_db_write.py check   JOB      # 정적 검사 + 해시 + 조립된 SQL. DB에 닿지 않는다
python3 -I ~/ops-write/sbshop_db_write.py dry-run JOB      # 같은 트랜잭션을 실행하고 ROLLBACK
python3 -I ~/ops-write/sbshop_db_write.py apply   JOB --confirm HASH12 [--bulk]
python3 -I ~/ops-write/sbshop_db_write.py --version        # 도구 버전 + 자기 파일 SHA-256 앞 12자
```

`JOB`이 `-`이면 표준 입력에서 읽는다.

| 종료코드 | 뜻 |
|---------|----|
| 0 | OK |
| 1 | FAIL — 실행했으나 롤백됐거나 결과를 확신할 수 없음 |
| 2 | REFUSED — 정적 검사·선행 조건에서 거부. 쓰기 트랜잭션을 시작하지 않았다(사전 확인의 읽기 접속은 있을 수 있다) |
| 64 | 사용법 오류 |

**"끝까지 실행됨"이 오류처럼 보이는 줄보다 우선한다.** psql 종료코드 0 + 명령 태그 + 끝 표지가 모두 있으면 스크립트는 끝까지 돈 것이다(`ON_ERROR_STOP`이라 서버 오류가 있었다면 종료코드 3으로 멈췄다). 이때 표준 오류의 `ERROR:`처럼 보이는 줄은 알림 본문의 일부로 보고 실패 사유·`committed` 판정에 쓰지 않는다(단계 표지·확인 검사는 그대로 한다).

apply가 OK가 아니어도 확인 스크립트(`sbshop_ro`)로 백업 테이블을 조회해 `committed=yes|no|unknown`을 낸다. 판정 순서(끝까지 실행됐으면 1을 건너뛴다):

1. 쓰기 스크립트에서 서버가 `ERROR`를 냈다(`SBW|FAIL` 표지든 다른 서버 오류든) → `no` (스크립트의 모든 문장이 트랜잭션 안이거나 COMMIT 자신이라 서버 오류가 하나라도 있으면 커밋되지 않았다)
2. 확인 스크립트가 실패했거나 읽을 수 없다(종료코드 ≠ 0 포함) → `unknown`
3. 백업 테이블이 전부 있다 → `yes` (사전 확인에서 하나도 없었다)
4. 전부 없다 → psql이 스스로 오류로 끝났을 때(시간 초과·실행 오류가 아니고 종료코드 2 또는 3)만 `no`. 단 `SBW|END|COMMIT`을 봤다면 모순이므로 `unknown`. 시간 초과·클라이언트 강제 종료·그 밖의 종료코드는 `unknown` — `docker exec` 클라이언트가 죽어도 컨테이너 안 psql은 남은 스크립트를 읽고 나중에 커밋할 수 있다
5. 일부만 있다 → `unknown`

OK의 조건은 psql 종료코드 0, 표준 출력의 명령 태그 줄 `COMMIT`(dry-run은 `ROLLBACK`)과 `SBW|END|…` 표지, 모든 단계의 `SBW|STEP` 표지(`rows`=`expect`), apply는 추가로 확인 스크립트의 백업 행 수다. 표지는 **줄 전체가 맞아야** 인정한다. 태그가 없으면 `reason=no-commit-tag`.

`unknown`이면 **최대 2분 뒤(서버 statement_timeout)** 백업 테이블과 대상 행을 직접 조회해 확인한다. 로그에 `APPLY START`만 있고 결과 줄이 없으면(Ctrl-C 등) `unknown`으로 취급한다. 실행 뒤 로그 기록이 실패해도 결과(OK/FAIL, `committed`)와 "기록 실패" 경고는 화면에 나오고 종료코드는 실행 결과대로다.

거부 경로에서 로그 기록이 실패해도 경고를 내고 종료코드 2를 유지한다. 거부 사유(`APPLY REFUSED reason=`): `invalid-job`, `bulk-needs-flag`, `confirm-mismatch`, `no-dryrun`, `no-fresh-dump`, `check-unavailable`(사전 확인 접속 실패), `backup-exists`(이 작업의 백업 테이블이 이미 있음 — 같은 작업의 두 번째 apply). 이 순서로 검사하며, 사전 확인 전 단계에서 거부되면 어떤 psql도 부르지 않고 사전 확인에서 거부되면 쓰기용 psql은 부르지 않는다.

## 절차

1. **작업 파일 작성** — 서버에서 임시 위치에 쓴다(저장소 폴더는 배포가 초기화한다). 개인정보를 `title`·주석에 쓰지 않는다.
2. **`check`** — 조립된 SQL과 해시 앞 12자를 확인한다. 이 출력으로 **승인**을 받는다(승인 대상 = 그 해시의 파일).
3. **간섭 확인** — 도는 소싱처 배치가 있는지, 대상 행을 다시 쓰는 스케줄러·배치가 있는지 본다. **도구는 다른 쓰기 경로를 멈추지 않는다.** 실행 중 겹치면 직렬화 오류·잠금 시간 초과로 전체 롤백되지만, 커밋 뒤에 다른 경로가 덮어쓰는 것은 막지 못한다. 필요하면 해당 배치를 멈추거나 끝난 뒤에 한다.
4. **`dry-run`** — 단계별 `rows`·`est`·`bulk`를 본다. 대량이면 파일럿(작은 범위) → 본 실행으로 승인 절차를 나눈다.
5. **`pre` 덤프** — `~/ops-backup/sbshop-db-backup.sh pre`. 완성 파일, 사이드카 `<덤프이름>.sha256`, 백업 로그의 `OK kind=pre file=<덤프이름>` 줄(이름만, 경로 아님)이 생긴 뒤 **덤프 파일의 수정 시각 기준 30분 안에** apply한다(이름의 시각이 아니다 — 수정 시각은 덤프가 끝난 때라 이름보다 1분쯤 늦다). 도구는 이름의 시각을 KST로 읽어 수정 시각이 그보다 0~10분 늦은지도 본다 — 오래된 덤프를 `touch`해서는 통과하지 못한다.
6. **`apply JOB --confirm <해시12> [--bulk]`** — 덤프 이름·백업 테이블·`committed=`를 확인한다.
7. **재조회** — 바뀐 행을 직접 조회해 의도대로인지 본다(쓰기 응답과 반영 확인을 분리).
8. **원장 기록** — 결함 번호, 작업 해시, 백업 테이블 이름, 덤프 파일명, 재조회 결과.

## 되돌리는 방법

백업 테이블이 방금 바꾼 행을 돌려준다. 되돌리기도 이 도구의 작업으로 한다(다른 `defect` 또는 `backup` 접미를 써서 백업 이름이 겹치지 않게).

| 원래 작업 | 되돌리는 작업 |
|----------|-------------|
| update | `kind="update"`, `from="bak_d999_s1_sb_order AS b"`, `where="sb_order.id = b.id"`, `set="status = b.status"`, `expect`=백업 행 수 |
| delete | `kind="insert"`, `source="SELECT * FROM bak_d999_s1_sb_order"`, `expect`=백업 행 수 |
| insert | `kind="delete"`, `from="bak_d999_s1_sb_example AS b"`, `where="sb_example.id = b.id"`, `expect`=백업 행 수 |

- 여러 단계 작업은 **역순으로** 되돌린다. 같은 테이블을 한 작업에서 두 번 바꿨다면 뒤 단계의 백업은 중간 값이다.
- IDENTITY ALWAYS·GENERATED 열이 있는 테이블은 `SELECT *` 되돌리기 insert가 안 된다(`OVERRIDING`을 쓸 수 없다 — source 첫 단어 규칙). 지금 운영에는 그런 열이 없다.

## 한계

- 덤프와 쓰기 사이(최대 30분)에 들어온 다른 변경은 덤프 복원으로 돌아오지 않는다. 마켓에 이미 나간 값은 DB를 되돌려도 돌아오지 않는다.
- 작은 테이블에서는 한 행도 5%를 넘어 대량이 된다. **빈 테이블에 insert는 항상 대량**이다(행 수 0 → `--bulk` 필요).
- 예약어 이름의 테이블(`order`, `user` 등)은 `AS 별칭` 조립에서 문법 오류로 실패(롤백)하므로 이 도구로 다룰 수 없다.
- 트리거·규칙·상속 자식·연쇄 FK가 있는 테이블은 거부한다(위 "무엇을 막는가"). 지금 운영 스키마에는 해당 테이블이 없다 — 생기면 도구를 고친다.
- DB 수준 이벤트 트리거(DDL에 반응 — 도구의 `CREATE TABLE`에도 걸린다)는 가드가 보지 않는다. 지금은 0개다.
- 조각 검사는 부작용 있는 함수 호출을 가려내지 않는다. `set_config('lock_timeout', …)`처럼 제한 시간을 바꾸거나 `pg_terminate_backend`·`pg_cancel_backend`·`setval`을 부르는 조각도 통과한다. 쓰기 역할이 앱과 같은 `sbshop`이라 앱 접속에도 닿는다 — 승인 단계에서 사람이 본다.
- dry-run 성공 기록에는 유효 기간이 없다(도구 버전에도 묶이지 않는다). apply가 건수를 다시 대조하므로 오래된 dry-run으로 틀린 쓰기가 나가지는 않는다.
- dry-run도 대상 행을 잠깐 잠그고, insert 단계는 시퀀스 번호를 소모한다(시퀀스는 롤백되지 않는다).

백업 테이블은 도구가 지우지 않는다. 정리는 원장에 기록한 뒤 별도 승인으로 한다.

## 기록

- `~/ops-write/sbshop-db-write.log` (600) — 한 줄씩 덧붙인다. `DRYRUN OK|FAIL`, `APPLY REFUSED|START|OK|FAIL`. `START`는 실행 전에 남는다. `title`·조각·DB 오류의 `DETAIL`은 쓰지 않는다.
- `~/ops-write/jobs/` (700) — apply한 작업 파일(`.toml`)과 조립된 SQL(`.sql`)을 `시각_결함_해시12` 이름으로 보관한다.

```
<ISO 시각> DRYRUN OK defect=D-999 job=<해시64> tool=<12> steps=2 rows=3+5
<ISO 시각> APPLY START defect=D-999 job=<해시64> tool=<12> dump=<덤프 파일명> bulk=0
<ISO 시각> APPLY OK defect=D-999 job=<해시64> tool=<12> steps=2 rows=3+5 backups=bak_d999_a,bak_d999_b dump=<덤프 파일명>
<ISO 시각> APPLY FAIL defect=D-999 job=<해시64> tool=<12> reason=step=1|phase=affected|got=5|expect=3 committed=no
```

## 설치·버전 대조

설치는 운영 쓰기이므로 승인 후 한다. 서버에 아무것도 설치하지 않는다(Python 3.12 표준 라이브러리만 쓴다).

```bash
mkdir -m 700 -p ~/ops-write/jobs
install -m 700 tools/dbwrite/sbshop_db_write.py ~/ops-write/sbshop_db_write.py   # 저장소 사본에서 복사
python3 -I ~/ops-write/sbshop_db_write.py --version   # → sbshop_db_write <버전> <해시12>
sha256sum ~/ops-write/sbshop_db_write.py               # 앞 12자가 위와 같아야 한다
```

저장소 쪽에서도 `sha256sum tools/dbwrite/sbshop_db_write.py`(또는 `python -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()[:12])" tools/dbwrite/sbshop_db_write.py`)로 같은 값을 확인한다. 되돌리기는 `~/ops-write/` 삭제. 이 폴더는 `.gitattributes`(`* text eol=lf`)로 줄 끝을 LF로 고정한다 — `core.autocrlf=true`인 PC에서 CRLF로 체크아웃되면 해시가 서버 설치본과 달라진다.

## 시험

```bash
python -m unittest discover -s tools/dbwrite -p 'test_*.py'
```

- 단위 시험은 DB 없이 돈다. 실행 흐름은 가짜 psql(표준 입력을 파일에 남기고 정해 준 출력·종료코드를 내는 스크립트)로 시험한다. 시험은 `main(argv, config=…)`에 설정 객체를 주입한다.
- 실 DB 통합 시험(설계서 §7의 18항목)은 환경 변수가 있을 때만 돈다. **운영 DB가 아닌 시험 전용 DB**(이름이 `_test`로 끝나야 한다 — 아니면 전부 중단)에 대고 돌린다.
  - `SBSHOP_DBWRITE_TEST_PSQL` — 쓰기 역할 psql argv의 JSON 배열. 끝이 `-f -`. 예: `["psql","-X","-U","sbshop","-d","sbshop_dbwrite_test","-v","ON_ERROR_STOP=1","-f","-"]`
  - `SBSHOP_DBWRITE_TEST_PSQL_RO` — 읽기 전용 역할 argv(없으면 쓰기용과 같게). 쓰기 역할이 만든 테이블에 읽기 역할의 SELECT가 기본 권한으로 붙어 있어야 한다(운영과 같게).
  - 시험이 만드는 것은 `sbw_t_` 테이블·뷰·함수(트리거·규칙·상속·FK 시험용)와 `bak_d9…` 백업 테이블뿐이고 시험마다 지운다.
- CI(`tests.yml`)는 이 폴더를 돌리지 않는다. 고칠 때 손으로 돌린다.
