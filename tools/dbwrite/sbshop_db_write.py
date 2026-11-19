#!/usr/bin/env python3
"""sbshop 운영 DB 쓰기 도구 (D-364).

설계: docs/normalize/plans/D-364_운영DB-쓰기도구-설계.md
자유 SQL을 받지 않는다. 작업 파일(TOML)을 읽어 문장을 조립하고, 한 트랜잭션 안에서
백업 테이블 → 변경 → 건수 대조를 서버가 하게 한다. 표준 라이브러리만 쓴다(Python 3.11+).
"""
from __future__ import annotations

import dataclasses
import errno
import hashlib
import os
import re
import subprocess
import sys
import time
import tomllib

try:
    import pwd as _pwd
except ImportError:  # Windows 개발 PC
    _pwd = None
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

# --------------------------------------------------------------------------- 운영 대상 (K12 — 환경 변수·인자로 바꿀 수 없다)
TOOL_VERSION = "1.0.0"
DOCKER = "/usr/bin/docker"
CONTAINER = "projects-postgres-1"
DATABASE = "sbshop"
WRITE_ROLE = "sbshop"
CHECK_ROLE = "sbshop_ro"
PSQL_TIMEOUT = 300.0
DUMP_MAX_AGE = timedelta(minutes=30)
DUMP_MIN_BYTES = 10 * 1024 * 1024
DUMP_NAME_TZ = timezone(timedelta(hours=9))  # 덤프 이름의 시각은 서버 OS 시각(KST, F11) — 환경과 무관한 상수
DUMP_NAME_MTIME_MAX_GAP = timedelta(minutes=10)
PSQL_PATH = "/usr/sbin:/usr/bin:/sbin:/bin"
OPS_DIR_NAME = "ops-write"
LOG_NAME = "sbshop-db-write.log"
DUMP_DIR_PARTS = ("backups", "daily")
BACKUP_LOG_PARTS = ("ops-backup", "sbshop-db-backup.log")

# --------------------------------------------------------------------------- 고정값 (K6·K8)
LOCK_TIMEOUT = "5s"
STATEMENT_TIMEOUT = "120s"
BULK_TOTAL = 200
BULK_RATIO = 0.05
EST_EXACT_BELOW = 100000

# --------------------------------------------------------------------------- 작업 파일 규칙
MAX_STEPS = 20
MAX_TITLE = 120
MAX_IDENT_BYTES = 63
KINDS = ("update", "delete", "insert")
FORBIDDEN_WORDS = (
    "insert", "update", "delete", "merge", "truncate", "drop", "alter", "create",
    "grant", "revoke", "into", "copy", "call", "execute", "commit", "rollback",
)
DEPTH0_EXTRA = {
    "set": ("where", "from", "using", "returning"),
    "from": ("where", "returning"),
    "source": ("returning",),
    "where": (),
}
RESERVED_TABLE_PREFIXES = ("bak_", "_bak", "zz_", "pg_")

TOP_KEYS = {"defect", "title", "step"}
STEP_KEYS = {
    "update": ({"kind", "table", "where", "set", "expect"}, {"from", "backup"}),
    "delete": ({"kind", "table", "where", "expect"}, {"from", "backup"}),
    "insert": ({"kind", "table", "source", "expect"}, {"columns", "backup"}),
}
ALL_STEP_KEYS = set().union(*(req | opt for req, opt in STEP_KEYS.values()))

DEFECT_RE = re.compile(r"D-([0-9]{1,5})")
TABLE_RE = re.compile(r"[a-z][a-z0-9_]*")
BACKUP_RE = re.compile(r"[a-z0-9_]+")
COLUMNS_RE = re.compile(r"[a-z_][a-z0-9_]*([ \t]*,[ \t]*[a-z_][a-z0-9_]*)*")
WORD_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


class JobError(Exception):
    """작업 파일 정적 검사 실패(REFUSED)."""


@dataclasses.dataclass(frozen=True)
class Step:
    index: int
    kind: str
    table: str
    expect: int
    backup: str
    where: str | None = None
    set: str | None = None
    from_: str | None = None
    columns: str | None = None
    source: str | None = None


@dataclasses.dataclass(frozen=True)
class Job:
    defect: str
    defect_digits: str
    title: str
    steps: tuple
    sha256: str

    @property
    def h12(self) -> str:
        return self.sha256[:12]

    @property
    def tag(self) -> str:
        return f"$sbw_{self.h12}$"

    @property
    def total_expect(self) -> int:
        return sum(s.expect for s in self.steps)


# --------------------------------------------------------------------------- 조각 검사 (K9)
def _scan(text: str):
    """따옴표 밖의 문자를 훑어 (바깥 단어와 괄호 깊이 목록)을 돌려준다. 규칙 위반은 JobError."""
    words = []
    depth = 0
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "'" or c == '"':
            j = i + 1
            while True:
                if j >= n:
                    raise JobError("닫히지 않은 따옴표")
                if text[j] == c:
                    if j + 1 < n and text[j + 1] == c:
                        j += 2
                        continue
                    break
                j += 1
            if c == '"':
                # 큰따옴표 식별자: 줄바꿈·탭 금지, 63바이트 이내(서버의 "will be truncated" NOTICE에
                # 원문이 실려 표지 줄처럼 보이는 것을 막는다 — 검증 2차 N-L1)
                ident = text[i + 1:j].replace('""', '"')
                if "\n" in ident or "\t" in ident:
                    raise JobError("큰따옴표 식별자 안에 줄바꿈·탭을 쓸 수 없다")
                if len(ident.encode("utf-8")) > MAX_IDENT_BYTES:
                    raise JobError(f"큰따옴표 식별자가 {MAX_IDENT_BYTES}바이트를 넘는다")
            i = j + 1
            continue
        if c == ";":
            raise JobError("문자열 밖의 ';'")
        if c == "-" and text.startswith("--", i):
            raise JobError("문자열 밖의 주석 '--'")
        if c == "/" and text.startswith("/*", i):
            raise JobError("문자열 밖의 주석 '/*'")
        if c == "$":
            raise JobError("문자열 밖의 '$'")
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth < 0:
                raise JobError("괄호 짝이 맞지 않음(닫는 괄호가 먼저 나옴)")
        m = WORD_RE.match(text, i) if (c.isalpha() or c == "_") else None
        if m:
            words.append((m.group(0).lower(), depth))
            i = m.end()
            continue
        i += 1
    if depth != 0:
        raise JobError("괄호 짝이 맞지 않음(닫히지 않은 괄호)")
    return words


def check_fragment(field: str, text, tag: str | None = None) -> None:
    """where·set·from·source 조각 검사. 판단이 안 서면 거부한다."""
    try:
        if not isinstance(text, str):
            raise JobError("문자열이어야 한다")
        if not text.strip():
            raise JobError("빈 조각")
        if "\\" in text:
            raise JobError("역슬래시는 쓸 수 없다")
        for ch in text:
            if (ord(ch) < 0x20 and ch not in "\n\t") or ord(ch) == 0x7F:
                raise JobError(f"제어 문자 U+{ord(ch):04X}")
        if tag and tag in text:
            raise JobError(f"조립용 달러 인용 태그 {tag} 를 포함한다")
        words = _scan(text)
        for word, depth in words:
            if word in FORBIDDEN_WORDS:
                raise JobError(f"금지어 '{word}'")
            if depth == 0 and word in DEPTH0_EXTRA.get(field, ()):
                raise JobError(f"괄호 밖에서 '{word}' 를 쓸 수 없다")
        if field == "source":
            if not words or words[0][0] not in ("values", "select") or words[0][1] != 0 \
                    or not re.match(r"\s*[A-Za-z]", text):
                raise JobError("VALUES 또는 SELECT로 시작해야 한다")
    except JobError as e:
        raise JobError(f"{field}: {e}") from None


# --------------------------------------------------------------------------- 작업 파일 읽기
def _req_str(d, key, where):
    v = d.get(key)
    if not isinstance(v, str):
        raise JobError(f"{where}{key}: 문자열이 필요하다")
    return v


def parse_job(data: bytes) -> Job:
    sha = hashlib.sha256(data).hexdigest()
    try:
        doc = tomllib.loads(data.decode("utf-8"))
    except UnicodeDecodeError:
        raise JobError("작업 파일이 UTF-8이 아니다") from None
    except tomllib.TOMLDecodeError as e:
        raise JobError(f"TOML 읽기 실패: {e}") from None

    unknown = set(doc) - TOP_KEYS
    if unknown:
        raise JobError(f"모르는 키: {', '.join(sorted(unknown))}")

    defect = doc.get("defect")
    m = DEFECT_RE.fullmatch(defect) if isinstance(defect, str) else None
    if not m:
        raise JobError("defect: 'D-숫자(1~5자리)' 형식이어야 한다")
    digits = m.group(1)

    title = doc.get("title")
    if not isinstance(title, str) or not title.strip():
        raise JobError("title: 비어 있지 않은 문자열이 필요하다")
    if "\n" in title or "\r" in title or len(title) > MAX_TITLE:
        raise JobError(f"title: 한 줄, {MAX_TITLE}자 이내여야 한다")

    raw_steps = doc.get("step")
    if not isinstance(raw_steps, list) or not raw_steps or not all(isinstance(s, dict) for s in raw_steps):
        raise JobError("step: [[step]] 표가 1개 이상 필요하다")
    if len(raw_steps) > MAX_STEPS:
        raise JobError(f"step: 최대 {MAX_STEPS}개")

    tag = f"$sbw_{sha[:12]}$"
    steps = []
    seen_backups = set()
    for idx, raw in enumerate(raw_steps, 1):
        where = f"step {idx} "
        unknown = set(raw) - ALL_STEP_KEYS
        if unknown:
            raise JobError(f"{where}모르는 키: {', '.join(sorted(unknown))}")
        kind = raw.get("kind")
        if kind not in KINDS:
            raise JobError(f"{where}kind: update·delete·insert 중 하나여야 한다")
        required, optional = STEP_KEYS[kind]
        extra = set(raw) - required - optional
        if extra:
            raise JobError(f"{where}{kind}에 쓸 수 없는 키: {', '.join(sorted(extra))}")
        missing = required - set(raw)
        if missing:
            raise JobError(f"{where}빠진 키: {', '.join(sorted(missing))}")

        table = _req_str(raw, "table", where)
        if not TABLE_RE.fullmatch(table) or len(table.encode()) > MAX_IDENT_BYTES:
            raise JobError(f"{where}table: ^[a-z][a-z0-9_]*$, {MAX_IDENT_BYTES}바이트 이내여야 한다")
        if table.startswith(RESERVED_TABLE_PREFIXES):
            raise JobError(f"{where}table: {', '.join(RESERVED_TABLE_PREFIXES)} 로 시작하는 테이블은 대상이 아니다")

        expect = raw.get("expect")
        if type(expect) is not int or expect < 1:
            raise JobError(f"{where}expect: 1 이상의 정수가 필요하다")

        frags = {}
        for key in ("where", "set", "from", "source"):
            if key in raw:
                frags[key] = _req_str(raw, key, where)
                try:
                    check_fragment(key, frags[key], tag)
                except JobError as e:
                    raise JobError(f"{where}{e}") from None

        columns = None
        if "columns" in raw:
            columns = _req_str(raw, "columns", where)
            if not COLUMNS_RE.fullmatch(columns):
                raise JobError(f"{where}columns: 소문자 식별자 목록(a, b)만 쓸 수 있다")

        if "backup" in raw:
            suffix = raw["backup"]
            if not isinstance(suffix, str) or not BACKUP_RE.fullmatch(suffix):
                raise JobError(f"{where}backup: ^[a-z0-9_]+$ 이어야 한다")
        else:
            suffix = f"s{idx}_{table}"
        backup = f"bak_d{digits}_{suffix}"
        if len(backup.encode()) > MAX_IDENT_BYTES:
            raise JobError(f"{where}backup: 백업 테이블 이름 {backup} 이 {MAX_IDENT_BYTES}바이트를 넘는다")
        if backup in seen_backups:
            raise JobError(f"{where}backup: 백업 테이블 이름 {backup} 이 작업 안에서 겹친다")
        seen_backups.add(backup)

        steps.append(Step(index=idx, kind=kind, table=table, expect=expect, backup=backup,
                          where=frags.get("where"), set=frags.get("set"), from_=frags.get("from"),
                          columns=columns, source=frags.get("source")))
    return Job(defect=defect, defect_digits=digits, title=title, steps=tuple(steps), sha256=sha)


# --------------------------------------------------------------------------- SQL 조립 (§4-3)
def _step_sql(s: Step) -> list:
    n, t, b, e = s.index, s.table, s.backup, s.expect
    out = [
        f"IF to_regclass('public.{t}') IS NULL THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=table_missing'; END IF;",
        f"IF (SELECT c.relkind FROM pg_catalog.pg_class c WHERE c.oid = to_regclass('public.{t}')) <> 'r' THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=not_a_table'; END IF;",
        f"IF to_regclass('public.{b}') IS NOT NULL THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=backup_exists'; END IF;",
        # K14: 연쇄 쓰기가 일어날 수 있는 테이블은 거부한다
        f"IF EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhparent = to_regclass('public.{t}')) THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=has_children'; END IF;",
        f"IF EXISTS (SELECT 1 FROM pg_catalog.pg_trigger g WHERE g.tgrelid = to_regclass('public.{t}') AND NOT g.tgisinternal) THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=has_trigger'; END IF;",
        f"IF EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite w WHERE w.ev_class = to_regclass('public.{t}')) THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=has_rule'; END IF;",
    ]
    fk_col = {"delete": "confdeltype", "update": "confupdtype"}.get(s.kind)
    if fk_col:
        out.append(f"IF EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.contype = 'f' AND k.confrelid = to_regclass('public.{t}') AND k.{fk_col} NOT IN ('a', 'r')) THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=cascade_fk'; END IF;")
    out += [
        f"SELECT CASE WHEN c.reltuples >= {EST_EXACT_BELOW} THEN c.reltuples END INTO _sbw_est FROM pg_catalog.pg_class c WHERE c.oid = to_regclass('public.{t}');",
        f"IF _sbw_est IS NULL THEN SELECT count(*) INTO _sbw_est FROM public.{t}; END IF;",
    ]
    check_backup = f"IF _sbw_n <> {e} THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=backup|got=%|expect={e}', _sbw_n; END IF;"
    check_affected = f"IF _sbw_n <> {e} THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=affected|got=%|expect={e}', _sbw_n; END IF;"
    if s.kind in ("update", "delete"):
        # H1: 조건은 한 번만 평가한다. 대상 ctid 집합으로 백업과 변경을 한다.
        from_list = f", {s.from_}" if s.from_ else ""
        in_targets = f"{t}.ctid = ANY (_sbw_tids)"
        out += [
            f"SELECT array_agg({t}.ctid) INTO _sbw_tids FROM public.{t} AS {t}{from_list} WHERE ({s.where});",
            "_sbw_n := coalesce(cardinality(_sbw_tids), 0);",
            f"IF _sbw_n <> {e} THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=target|got=%|expect={e}', _sbw_n; END IF;",
            f"CREATE TABLE public.{b} AS SELECT {t}.* FROM public.{t} AS {t} WHERE {in_targets};",
            f"SELECT count(*) INTO _sbw_n FROM public.{b};",
            check_backup,
        ]
        if s.kind == "update" and s.from_:
            out.append(f"UPDATE public.{t} AS {t} SET {s.set} FROM {s.from_} WHERE ({s.where}) AND {in_targets};")
        elif s.kind == "update":
            out.append(f"UPDATE public.{t} AS {t} SET {s.set} WHERE {in_targets};")
        else:
            out.append(f"DELETE FROM public.{t} AS {t} WHERE {in_targets};")
        out += ["GET DIAGNOSTICS _sbw_n = ROW_COUNT;", check_affected]
    else:
        cols = f" ({s.columns})" if s.columns else ""
        out += [
            f"CREATE TABLE public.{b} (LIKE public.{t});",
            f"WITH _sbw_ins AS (INSERT INTO public.{t}{cols} {s.source} RETURNING *) INSERT INTO public.{b} SELECT * FROM _sbw_ins;",
            "GET DIAGNOSTICS _sbw_n = ROW_COUNT;",
            check_affected,
            f"SELECT count(*) INTO _sbw_n FROM public.{b};",
            check_backup,
        ]
    cond = f"{e} > {BULK_TOTAL} OR {e} > {BULK_RATIO} * _sbw_est"
    out += [
        f"IF {cond} THEN _sbw_bulk := true; END IF;",
        f"RAISE NOTICE 'SBW|STEP|step={n}|kind={s.kind}|table={t}|rows=%|backup={b}|est=%|bulk=%', _sbw_n, _sbw_est::bigint, ({cond})::int;",
    ]
    return out


def build_write_script(job: Job, dry_run: bool, bulk: bool) -> str:
    """§4-3의 스크립트. dry_run이면 ROLLBACK, 아니면 COMMIT."""
    bulk_ok = "true" if (dry_run or bulk) else "false"
    body = []
    for s in job.steps:
        body += _step_sql(s)
    body.append(f"IF _sbw_bulk AND NOT {bulk_ok} THEN RAISE EXCEPTION 'SBW|FAIL|phase=bulk'; END IF;")
    end = "ROLLBACK" if dry_run else "COMMIT"
    lines = [
        "\\set ON_ERROR_STOP on",
        "BEGIN ISOLATION LEVEL REPEATABLE READ;",
        f"SET LOCAL lock_timeout = '{LOCK_TIMEOUT}';",
        f"SET LOCAL statement_timeout = '{STATEMENT_TIMEOUT}';",
        "SET LOCAL search_path = public;",
        "SET LOCAL client_min_messages = notice;",
        f"DO {job.tag}",
        "#variable_conflict use_column",
        "DECLARE",
        "  _sbw_n bigint;",
        "  _sbw_est double precision;",
        "  _sbw_bulk boolean := false;",
        "  _sbw_tids tid[];",
        "BEGIN",
        *("  " + line for line in body),
        "END",
        f"{job.tag};",
        f"{end};",
        f"\\echo SBW|END|{end}",
    ]
    return "\n".join(lines) + "\n"


def build_check_script(job: Job) -> str:
    """쓰기 뒤 확인(읽기 전용 역할). 백업 테이블마다 존재와 행 수를 표지로 낸다."""
    out = []
    for s in job.steps:
        b = s.backup
        out.append(
            "DO $sbw_c$ DECLARE n bigint; BEGIN\n"
            f"  IF to_regclass('public.{b}') IS NULL THEN RAISE NOTICE 'SBW|CHECK|backup={b}|exists=0';\n"
            f"  ELSE EXECUTE 'SELECT count(*) FROM public.{b}' INTO n; RAISE NOTICE 'SBW|CHECK|backup={b}|exists=1|rows=%', n; END IF;\n"
            "END $sbw_c$;\n")
    return "".join(out)


# --------------------------------------------------------------------------- 실행 설정 (K12)
@dataclasses.dataclass(frozen=True)
class Config:
    psql_write: tuple
    psql_check: tuple
    log_file: Path
    jobs_dir: Path
    dump_dir: Path
    backup_log: Path
    now: Callable[[], datetime]
    dump_min_bytes: int = DUMP_MIN_BYTES
    dump_max_age: timedelta = DUMP_MAX_AGE
    psql_timeout: float = PSQL_TIMEOUT
    epoch: Callable[[], float] = time.time  # 덤프 나이(파일 수정 시각) 비교용
    psql_env: dict | None = None  # 하위 프로세스 환경. None이면 상속(시험 전용)


def _now() -> datetime:
    return datetime.now().astimezone()


def _psql_argv(role: str) -> tuple:
    # K12 ④: 계정에 저장된 docker 컨텍스트에 기대지 않는다
    return (DOCKER, "--context", "default", "exec", "-i", CONTAINER, "psql", "-X", "-U", role, "-d", DATABASE,
            "-v", "ON_ERROR_STOP=1", "-f", "-")


def _account_home() -> str:
    """K12 ①: 홈은 HOME이 아니라 계정 DB에서 얻는다. pwd가 없는 개발 PC에서만 Path.home()."""
    if _pwd is None:
        return str(Path.home())
    uid = os.getuid() if hasattr(os, "getuid") else 0
    return _pwd.getpwuid(uid).pw_dir


def default_config() -> Config:
    """운영 설정. 상수로만 만든다(환경 변수·인자를 읽지 않는다)."""
    home_str = _account_home()
    home = Path(home_str)
    ops = home / OPS_DIR_NAME
    return Config(
        psql_write=_psql_argv(WRITE_ROLE), psql_check=_psql_argv(CHECK_ROLE),
        log_file=ops / LOG_NAME, jobs_dir=ops / "jobs",
        dump_dir=home.joinpath(*DUMP_DIR_PARTS), backup_log=home.joinpath(*BACKUP_LOG_PARTS),
        now=_now, epoch=time.time,
        # K12 ②: 부모 환경(DOCKER_HOST·DOCKER_CONTEXT 등)을 물려주지 않는다
        psql_env={"PATH": PSQL_PATH, "HOME": home_str, "LANG": "C.UTF-8"})


# --------------------------------------------------------------------------- 선행 조건 (K7·K8)
DUMP_NAME_RE = re.compile(r"sbshop-(pre-)?([0-9]{8})-([0-9]{6})\.dump")


def find_fresh_dump(config: Config):
    """(통과한 덤프 이름 또는 None, 설명). 이름의 시각이 가장 늦은 덤프 하나만 본다."""
    try:
        names = [p.name for p in config.dump_dir.iterdir() if p.is_file()]
    except OSError:
        return None, f"덤프 폴더를 읽을 수 없다: {config.dump_dir}"
    candidates = []
    for name in names:
        m = DUMP_NAME_RE.fullmatch(name)
        if m:
            candidates.append((m.group(2) + m.group(3), name))  # 문자열 비교 — 시간대 해석 없음
    if not candidates:
        return None, "덤프 파일이 없다"
    stamp, name = max(candidates)
    try:
        mtime = (config.dump_dir / name).stat().st_mtime
    except OSError:
        return None, f"덤프 {name} 를 읽을 수 없다"
    age = timedelta(seconds=config.epoch() - mtime)  # K12 ③: 파일 수정 시각(epoch)
    if age < timedelta(0):
        return None, f"가장 늦은 덤프 {name} 의 수정 시각이 미래다"
    if age > config.dump_max_age:
        return None, f"가장 늦은 덤프 {name} 가 {int(age.total_seconds() // 60)}분 전 것이다(30분 이내 필요)"
    # N-L2: 이름의 시각을 KST 상수로 해석해 수정 시각과 맞는지 본다(오래된 덤프를 touch한 경우를 막는다)
    try:
        named = datetime.strptime(stamp, "%Y%m%d%H%M%S").replace(tzinfo=DUMP_NAME_TZ).timestamp()
    except ValueError:
        return None, f"덤프 {name} 이름의 시각을 읽을 수 없다"
    gap = mtime - named
    if not 0 <= gap <= DUMP_NAME_MTIME_MAX_GAP.total_seconds():
        return None, (f"덤프 {name} 의 수정 시각이 이름의 시각(KST)보다 {int(gap)}초 늦다"
                      f"(0~{int(DUMP_NAME_MTIME_MAX_GAP.total_seconds() // 60)}분이어야 한다)")
    path = config.dump_dir / name
    try:
        size = path.stat().st_size
    except OSError:
        return None, f"덤프 {name} 를 읽을 수 없다"
    if size < config.dump_min_bytes:
        return None, f"덤프 {name} 크기 {size}바이트가 최소 {config.dump_min_bytes}바이트보다 작다"
    if not (config.dump_dir / (name + ".sha256")).is_file():
        return None, f"덤프 {name} 의 .sha256 이 없다"
    ok_re = re.compile(r"(?:^|\s)OK kind=(?:pre|daily) file=" + re.escape(name) + r"(?:\s|$)")
    try:
        with open(config.backup_log, encoding="utf-8", errors="replace") as f:
            logged = any(ok_re.search(line) for line in f)
    except OSError:
        logged = False
    if not logged:
        return None, f"백업 로그에 덤프 {name} 의 OK 줄이 없다"
    return name, f"덤프 {name} ({size}바이트, {int(age.total_seconds())}초 전)"


def dryrun_recorded(log_file: Path, job_sha: str) -> bool:
    """로그에서 `DRYRUN OK`이고 job=이 전체 해시와 같은 줄을 찾는다."""
    try:
        with open(log_file, encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.split()
                if len(parts) >= 3 and parts[1] == "DRYRUN" and parts[2] == "OK" and f"job={job_sha}" in parts[3:]:
                    return True
    except OSError:
        return False
    return False


def apply_preconditions(job: Job, confirm: str, bulk: bool, config: Config):
    """(거부 사유 또는 None, 덤프 이름, 설명). 순서: 대량 표시 → --confirm → dry-run 기록 → 덤프."""
    if job.total_expect > BULK_TOTAL and not bulk:
        return "bulk-needs-flag", None, f"단계 expect 합 {job.total_expect} 이 {BULK_TOTAL} 을 넘는다 — --bulk 가 필요하다"
    if confirm != job.h12:
        return "confirm-mismatch", None, f"--confirm 값이 작업 해시 앞 12자({job.h12})와 다르다"
    if not dryrun_recorded(config.log_file, job.sha256):
        return "no-dryrun", None, "같은 작업 파일의 dry-run 성공 기록이 없다"
    dump, msg = find_fresh_dump(config)
    if dump is None:
        return "no-fresh-dump", None, msg
    return None, dump, msg


# --------------------------------------------------------------------------- psql 실행과 표지 판정
EXIT_OK, EXIT_FAIL, EXIT_REFUSED, EXIT_USAGE = 0, 1, 2, 64
# psql이 낸 줄만 표지로 인정한다(오류의 LINE 문맥에 섞인 SQL 원문은 표지가 아니다).
# 줄 전체가 맞아야 한다(줄 중간·부분 일치 불인정). 앞의 `psql:<파일>:<줄>: ` 은 psql이 붙인다.
_SERVER_MSG_RE = re.compile(r"(?:psql:[^:]*:[0-9]+: )?(NOTICE|ERROR):[ \t]+(.*)")
_END_RE = re.compile(r"SBW\|END\|(COMMIT|ROLLBACK)")
_TAG_RE = re.compile(r"COMMIT|ROLLBACK")
RECHECK_HINT = "최대 2분 뒤(서버 statement_timeout 120초) 백업 테이블과 대상 행을 다시 조회해 확인한다."
REASON_MAX = 120


@dataclasses.dataclass
class PsqlResult:
    rc: int | None
    out: str
    err: str
    timed_out: bool = False
    exec_error: str | None = None


def run_psql(argv, script: str, timeout: float, env: dict | None = None) -> PsqlResult:
    """env가 None이면 부모 환경을 상속한다(시험 전용). 운영 설정은 고정 환경을 준다(K12 ②)."""
    try:
        cp = subprocess.run(list(argv), input=script.encode("utf-8"), capture_output=True, timeout=timeout,
                            env=None if env is None else dict(env))
    except subprocess.TimeoutExpired as e:
        return PsqlResult(None, _dec(e.stdout), _dec(e.stderr), timed_out=True)
    except OSError as e:
        return PsqlResult(None, "", "", exec_error=f"{type(e).__name__}: {e}")
    return PsqlResult(cp.returncode, _dec(cp.stdout), _dec(cp.stderr))


def _dec(b) -> str:
    if b is None:
        return ""
    return b.decode("utf-8", errors="replace") if isinstance(b, bytes) else b


def _fields(rest: str) -> dict:
    out = {}
    for part in rest.split("|"):
        k, sep, v = part.partition("=")
        if sep:
            out[k] = v
    return out


@dataclasses.dataclass
class Markers:
    steps: dict  # step 번호 → 필드
    checks: dict  # 백업 이름 → 필드
    fail: str | None  # 'SBW|FAIL|' 뒤 원문
    end: str | None
    db_error: str | None  # 표지가 아닌 첫 오류 줄
    server_error: bool = False  # psql이 낸 서버 `ERROR:` 줄이 있었다(SBW|FAIL 포함)
    tag: str | None = None  # 표준 출력의 명령 태그 줄 COMMIT/ROLLBACK
    completed: bool = False  # 끝까지 실행됨(종료코드 0 + 명령 태그 + 끝 표지) — judge_write가 정한다


def _lines(text: str) -> list:
    """줄 단위로 나눈다. 줄 끝의 CR 하나만 떼고 다른 공백은 그대로 둔다(줄 전체 일치용)."""
    return [line[:-1] if line.endswith("\r") else line for line in text.split("\n")]


def parse_markers(res: PsqlResult) -> Markers:
    m = Markers({}, {}, None, None, None)
    for line in _lines(res.out):
        e = _END_RE.fullmatch(line)
        if e:
            m.end = e.group(1)
        if _TAG_RE.fullmatch(line):
            m.tag = line
    for line in _lines(res.err):
        s = _SERVER_MSG_RE.fullmatch(line)
        if not s:
            if m.db_error is None and re.search(r"\b(error|fatal)\b", line, re.I) and not line.startswith(("LINE ", "DETAIL:", "HINT:", "CONTEXT:")):
                m.db_error = line.strip()
            continue
        level, msg = s.groups()
        if level == "ERROR":
            m.server_error = True
        if level == "NOTICE" and msg.startswith("SBW|STEP|"):
            f = _fields(msg[len("SBW|STEP|"):])
            if f.get("step", "").isdigit():
                m.steps[int(f["step"])] = f
        elif level == "NOTICE" and msg.startswith("SBW|CHECK|"):
            f = _fields(msg[len("SBW|CHECK|"):])
            if "backup" in f:
                m.checks[f["backup"]] = f
        elif level == "ERROR" and msg.startswith("SBW|FAIL|"):
            if m.fail is None:
                m.fail = msg[len("SBW|FAIL|"):].strip()
        elif level == "ERROR" and m.db_error is None:
            m.db_error = msg.strip()
    return m


def _clean(text: str, limit: int = REASON_MAX) -> str:
    text = "".join(ch if ch.isprintable() else " " for ch in text)
    return " ".join(text.split())[:limit]


def judge_write(job: Job, res: PsqlResult, expect_end: str):
    """(OK 여부, 실패 사유, 표지). OK = 종료코드 0 + 명령 태그 + 끝 표지 + 모든 단계 표지의 rows가 expect.

    "끝까지 실행됨"(종료코드 0 + 명령 태그 + 끝 표지)이 오류처럼 보이는 줄보다 우선한다(설계 §4-4, 검증 2차 N-L1).
    ON_ERROR_STOP이라 서버 오류가 있었다면 종료코드 3으로 멈췄을 것이므로, 그때의 `ERROR:` 줄은 NOTICE 본문의 일부로 본다.
    """
    m = parse_markers(res)
    m.completed = (not res.exec_error and not res.timed_out and res.rc == 0
                   and m.tag == expect_end and m.end == expect_end)
    if not m.completed:
        if res.exec_error:
            return False, "exec-error: " + _clean(res.exec_error), m
        if res.timed_out:
            return False, "timeout", m
        if m.fail:
            return False, _clean(m.fail), m
        if m.db_error:
            return False, "db-error: " + _clean(m.db_error), m
        if res.rc != 0:
            return False, f"psql-exit={res.rc}", m
        if m.tag != expect_end:
            return False, "no-commit-tag", m
        return False, "no-end-marker" if m.end is None else f"end-marker={m.end}", m
    for s in job.steps:
        f = m.steps.get(s.index)
        if f is None:
            return False, f"no-step-marker|step={s.index}", m
        if f.get("rows") != str(s.expect):
            return False, f"step-marker-mismatch|step={s.index}|got={_clean(f.get('rows', ''), 20)}|expect={s.expect}", m
    return True, None, m


def read_check(job: Job, res: PsqlResult):
    """확인 스크립트 결과 → {백업 이름: (exists, rows)} 또는 None(실패했거나 표지를 다 읽지 못함)."""
    m = parse_markers(res)
    if res.exec_error or res.timed_out or res.rc != 0 or m.db_error:
        return None
    seen = {}
    for s in job.steps:
        f = m.checks.get(s.backup)
        if f is None or f.get("exists") not in ("0", "1"):
            return None
        seen[s.backup] = (f["exists"] == "1", f.get("rows"))
    return seen


def judge_check(job: Job, res: PsqlResult, write_res: PsqlResult, write_markers: Markers):
    """(백업 행 수가 모두 맞는가, committed=yes|no|unknown, 실패 사유). 판정 순서는 설계 §4-4의 1~5."""
    seen = read_check(job, res)
    rows_ok = seen is not None and all(e and r == str(s.expect) for s in job.steps for e, r in [seen[s.backup]])
    reason = None if rows_ok else ("check-failed" if seen is None else "check-mismatch")
    # 1. 서버 오류(SBW|FAIL 포함) → 이번 실행은 커밋되지 않았다. 끝까지 실행됐으면 건너뛴다(N-L1)
    if write_markers.server_error and not write_markers.completed:
        committed = "no"
    elif seen is None:  # 2. 확인 실패
        committed = "unknown"
    else:
        states = [seen[s.backup][0] for s in job.steps]
        if all(states):  # 3. 사전 확인에서 하나도 없었으므로 이번 실행이 만든 것
            committed = "yes"
        elif not any(states):  # 4. psql이 스스로 오류로 끝났을 때만 no
            self_failed = not write_res.timed_out and not write_res.exec_error and write_res.rc in (2, 3)
            committed = "no" if self_failed and write_markers.end != "COMMIT" else "unknown"
        else:  # 5.
            committed = "unknown"
    return rows_ok, committed, reason


# --------------------------------------------------------------------------- 기록
def tool_hash() -> str:
    try:
        return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()[:12]
    except OSError:
        return "unknown"


def _ensure_dir(path: Path) -> None:
    path.mkdir(mode=0o700, parents=True, exist_ok=True)


def append_log(config: Config, event: str, job_sha: str, defect: str, tail: str) -> None:
    ts = config.now().isoformat(timespec="seconds")
    line = f"{ts} {event} defect={defect} job={job_sha} tool={tool_hash()} {tail}\n"
    _ensure_dir(config.log_file.parent)
    fd = os.open(config.log_file, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    with os.fdopen(fd, "a", encoding="utf-8") as f:
        f.write(line)
        f.flush()
        os.fsync(f.fileno())


def archive_job(config: Config, job: Job, data: bytes, sql: str) -> Path:
    _ensure_dir(config.jobs_dir)
    base = f"{config.now().strftime('%Y%m%d-%H%M%S')}_{job.defect}_{job.h12}"
    n = 0
    while True:
        stem = base if n == 0 else f"{base}_{n}"
        toml_path = config.jobs_dir / f"{stem}.toml"
        if not toml_path.exists():
            break
        n += 1
    for path, content in ((toml_path, data), (config.jobs_dir / f"{stem}.sql", sql.encode("utf-8"))):
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(content)
    return toml_path


# --------------------------------------------------------------------------- 명령
USAGE = """\
사용법:
  python3 -I ~/ops-write/sbshop_db_write.py check   JOB      정적 검사 + 해시 + 조립된 SQL (DB에 닿지 않음)
  python3 -I ~/ops-write/sbshop_db_write.py dry-run JOB      같은 트랜잭션을 실행하고 ROLLBACK
  python3 -I ~/ops-write/sbshop_db_write.py apply   JOB --confirm HASH12 [--bulk]
  python3 -I ~/ops-write/sbshop_db_write.py --version
JOB 이 - 이면 표준 입력에서 읽는다. -I(격리 모드)가 없으면 실행하지 않는다.
종료코드: 0 OK, 1 FAIL, 2 REFUSED(쓰기 트랜잭션을 시작하지 않음), 64 사용법 오류
"""


class UsageError(Exception):
    pass


@dataclasses.dataclass
class Args:
    command: str
    job: str | None = None
    confirm: str | None = None
    bulk: bool = False


def parse_args(argv) -> Args:
    argv = list(argv)
    if argv == ["--version"]:
        return Args("version")
    if not argv or argv[0] not in ("check", "dry-run", "apply"):
        raise UsageError("명령은 check·dry-run·apply·--version 중 하나다")
    a = Args(argv[0])
    rest = argv[1:]
    i = 0
    while i < len(rest):
        tok = rest[i]
        if tok == "--confirm" and a.command == "apply":
            if a.confirm is not None or i + 1 >= len(rest):
                raise UsageError("--confirm 은 값과 함께 한 번만 쓴다")
            a.confirm = rest[i + 1]
            i += 2
            continue
        if tok == "--bulk" and a.command == "apply":
            if a.bulk:
                raise UsageError("--bulk 가 두 번 나왔다")
            a.bulk = True
        elif tok.startswith("--"):
            raise UsageError(f"{a.command} 에 쓸 수 없는 옵션: {tok}")
        elif a.job is None:
            a.job = tok
        else:
            raise UsageError(f"인자가 너무 많다: {tok}")
        i += 1
    if a.job is None:
        raise UsageError("JOB 이 필요하다")
    if a.command == "apply" and a.confirm is None:
        raise UsageError("apply 에는 --confirm HASH12 가 필요하다")
    return a


class Out:
    """화면 출력. 출력이 끊겨도(BrokenPipe) 작업은 계속한다 — 기록은 출력보다 먼저 남긴다."""

    def __init__(self, stream):
        self.stream = stream
        self.broken = False

    def __call__(self, text: str = "") -> None:
        if self.broken:
            return
        try:
            try:
                self.stream.write(text + "\n")
            except UnicodeEncodeError:
                # 인코딩할 수 없는 글자는 대체 문자로 쓰고 출력은 계속한다(L7)
                enc = getattr(self.stream, "encoding", None) or "ascii"
                self.stream.write((text + "\n").encode(enc, "replace").decode(enc))
            self.stream.flush()
        except BrokenPipeError:
            self.broken = True
        except ValueError:  # 닫힌 스트림 — 끊긴 파이프와 같게 조용히 멈춘다
            self.broken = True
        except OSError as e:
            if getattr(e, "errno", None) in (errno.EPIPE, errno.EINVAL):
                self.broken = True
            else:
                raise


def _read_job_bytes(path: str, stdin) -> bytes:
    if path == "-":
        src = getattr(stdin, "buffer", stdin)
        data = src.read()
        return data.encode("utf-8") if isinstance(data, str) else data
    return Path(path).read_bytes()


def _summary(job: Job, out: Out) -> None:
    out(f"결함 {job.defect}  작업 해시 {job.sha256}")
    out(f"확인값(--confirm) {job.h12}")
    for s in job.steps:
        out(f"  단계 {s.index}: {s.kind:<6} {s.table}  expect={s.expect}  백업={s.backup}")
    bulk = "예" if job.total_expect > BULK_TOTAL else "서버 판정(5% 기준)"
    out(f"expect 합 {job.total_expect} — 대량 여부: {bulk}")


def _step_table(job: Job, m: Markers, out: Out) -> None:
    out("단계  종류    테이블                         rows    est        bulk  백업 테이블")
    for s in job.steps:
        f = m.steps.get(s.index, {})
        out(f"{s.index:<5} {s.kind:<7} {s.table:<30} {f.get('rows', '-'):<7} {f.get('est', '-'):<10} "
            f"{f.get('bulk', '-'):<5} {s.backup}")


def cmd_check(job: Job, out: Out) -> int:
    _summary(job, out)
    out("")
    out("-- 조립된 SQL (apply, --bulk 없음) --")
    out(build_write_script(job, dry_run=False, bulk=False).rstrip("\n"))
    out("")
    out("-- 쓰기 뒤 확인 SQL (sbshop_ro) --")
    out(build_check_script(job).rstrip("\n"))
    return EXIT_OK


def cmd_dry_run(job: Job, config: Config, out: Out, err: Out) -> int:
    res = run_psql(config.psql_write, build_write_script(job, dry_run=True, bulk=False), config.psql_timeout, config.psql_env)
    ok, reason, m = judge_write(job, res, "ROLLBACK")
    rows = "+".join(m.steps.get(s.index, {}).get("rows", "?") for s in job.steps)
    if ok:
        _log_safely(config, "DRYRUN OK", job.sha256, job.defect, f"steps={len(job.steps)} rows={rows}", err)
    else:
        _log_safely(config, "DRYRUN FAIL", job.sha256, job.defect, f"reason={reason}", err)
    _summary(job, out)
    _step_table(job, m, out)
    any_bulk = any(m.steps.get(s.index, {}).get("bulk") == "1" for s in job.steps) or job.total_expect > BULK_TOTAL
    if ok:
        out(f"DRY-RUN OK — 롤백했다. 대량: {'예 (apply에 --bulk 필요)' if any_bulk else '아니오'}")
        out(f"다음: 승인 → pre 덤프 → apply JOB --confirm {job.h12}{' --bulk' if any_bulk else ''}")
        return EXIT_OK
    out(f"DRY-RUN FAIL — {reason}")
    _tail(res, out)
    return EXIT_FAIL


def _tail(res: PsqlResult, out: Out) -> None:
    lines = [l for l in res.err.splitlines() if not l.startswith("DETAIL:")][:10]
    for l in lines:
        out("  psql| " + l)


def _log_safely(config: Config, event: str, job_sha: str, defect: str, tail: str, err: Out) -> bool:
    """START 이외의 기록. 실패해도 결과 출력·종료코드를 가리지 않는다(경고만 낸다)."""
    try:
        append_log(config, event, job_sha, defect, tail)
        return True
    except OSError as e:
        err(f"경고: 기록 실패 — {config.log_file}: {e}")
        err(f"기록하려던 줄: {event} defect={defect} job={job_sha} {tail}")
        return False


def cmd_apply(job: Job, data: bytes, args: Args, config: Config, out: Out, err: Out) -> int:
    def refuse(reason, msg):
        _log_safely(config, "APPLY REFUSED", job.sha256, job.defect, f"reason={reason}", err)
        out(f"REFUSED({reason}) — {msg}")
        return EXIT_REFUSED

    reason, dump, msg = apply_preconditions(job, args.confirm, args.bulk, config)
    if reason:
        return refuse(reason, msg)
    # 사전 확인(K7 ④): 읽기 전용 역할로 이 작업의 백업 테이블이 하나도 없음을 확인한다.
    pre = read_check(job, run_psql(config.psql_check, build_check_script(job), config.psql_timeout, config.psql_env))
    if pre is None:
        return refuse("check-unavailable", "사전 확인(읽기 전용 접속)을 할 수 없다 — 쓰기 뒤 확인도 못 하므로 실행하지 않는다")
    existing = [s.backup for s in job.steps if pre[s.backup][0]]
    if existing:
        return refuse("backup-exists", "이미 있는 백업 테이블: " + ", ".join(existing))

    script = build_write_script(job, dry_run=False, bulk=args.bulk)
    try:
        archive_job(config, job, data, script)
        append_log(config, "APPLY START", job.sha256, job.defect, f"dump={dump} bulk={int(args.bulk)}")
    except OSError as e:
        out(f"REFUSED — 실행 기록을 남길 수 없어 실행하지 않았다: {e}")
        return EXIT_REFUSED
    res = run_psql(config.psql_write, script, config.psql_timeout, config.psql_env)
    ok, reason, m = judge_write(job, res, "COMMIT")
    cres = run_psql(config.psql_check, build_check_script(job), config.psql_timeout, config.psql_env)
    rows_ok, committed, check_reason = judge_check(job, cres, res, m)
    if ok and not rows_ok:
        ok, reason = False, check_reason
    if ok:
        rows = "+".join(m.steps[s.index]["rows"] for s in job.steps)
        backups = ",".join(s.backup for s in job.steps)
        _log_safely(config, "APPLY OK", job.sha256, job.defect,
                    f"steps={len(job.steps)} rows={rows} backups={backups} dump={dump}", err)
    else:
        _log_safely(config, "APPLY FAIL", job.sha256, job.defect, f"reason={reason} committed={committed}", err)
    _summary(job, out)
    _step_table(job, m, out)
    out(f"덤프 {dump}")
    if ok:
        out(f"APPLY OK — committed={committed}")
        out("백업 테이블: " + ", ".join(s.backup for s in job.steps))
        out("다음: 재조회로 반영을 확인하고 원장에 기록한다. 되돌리기는 README의 '되돌리는 방법'.")
        return EXIT_OK
    out(f"APPLY FAIL — {reason}")
    out(f"committed={committed}")
    if committed == "unknown":
        out("커밋 여부를 확정할 수 없다 — " + RECHECK_HINT)
    _tail(res, out)
    return EXIT_FAIL


def main(argv=None, config=None, stdin=None, stdout=None, stderr=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    config = config or default_config()
    stdin = sys.stdin if stdin is None else stdin
    out = Out(sys.stdout if stdout is None else stdout)
    err = Out(sys.stderr if stderr is None else stderr)
    try:
        args = parse_args(argv)
    except UsageError as e:
        err(f"사용법 오류: {e}")
        err(USAGE.rstrip("\n"))
        return EXIT_USAGE
    if args.command == "version":
        out(f"sbshop_db_write {TOOL_VERSION} {tool_hash()}")
        return EXIT_OK

    try:
        data = _read_job_bytes(args.job, stdin)
    except OSError as e:
        err(f"REFUSED — 작업 파일을 읽을 수 없다: {e}")
        if args.command == "apply":
            _log_safely(config, "APPLY REFUSED", "-", "-", "reason=invalid-job", err)
        return EXIT_REFUSED
    try:
        job = parse_job(data)
    except JobError as e:
        err(f"REFUSED(invalid-job) — {e}")
        if args.command == "apply":
            defect = "-"
            try:
                d = tomllib.loads(data.decode("utf-8")).get("defect")
                if isinstance(d, str) and DEFECT_RE.fullmatch(d):
                    defect = d
            except (UnicodeDecodeError, tomllib.TOMLDecodeError):
                pass
            _log_safely(config, "APPLY REFUSED", hashlib.sha256(data).hexdigest(), defect, "reason=invalid-job", err)
        return EXIT_REFUSED

    if args.command == "check":
        return cmd_check(job, out)
    if args.command == "dry-run":
        return cmd_dry_run(job, config, out, err)
    return cmd_apply(job, data, args, config, out, err)


if __name__ == "__main__":
    for _s in (sys.stdout, sys.stderr):
        try:
            _s.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass
    # K12 ⑤: PYTHONPATH·사용자 site·현재 폴더가 표준 라이브러리 대신 끼어들지 못하게 격리 모드를 강제한다.
    # main() 자체는 검사하지 않는다(시험이 main()을 직접 부른다).
    if not sys.flags.isolated:
        sys.stderr.write("사용법 오류: python3 -I 로 실행한다 — 예: python3 -I ~/ops-write/sbshop_db_write.py --version\n")
        sys.exit(EXIT_USAGE)
    sys.exit(main())
