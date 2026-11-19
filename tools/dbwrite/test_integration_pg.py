"""sbshop_db_write 실 DB 통합 시험 (설계서 §7의 18항목).

환경 변수가 없으면 전부 건너뛴다.
  SBSHOP_DBWRITE_TEST_PSQL     쓰기용 psql argv의 JSON 배열. 끝이 `-f -` 여야 한다(스크립트를 표준 입력으로 넘긴다).
                               예: ["psql", "-X", "-h", "127.0.0.1", "-U", "sbshop", "-d", "sbshop_dbwrite_test",
                                    "-v", "ON_ERROR_STOP=1", "-f", "-"]
  SBSHOP_DBWRITE_TEST_PSQL_RO  읽기 전용 역할 argv(없으면 쓰기용과 같게).

안전장치: 시작할 때 두 접속의 current_database()가 `_test`로 끝나지 않으면 전부 중단한다.
시험이 만드는 것은 `sbw_t_` 접두 테이블·뷰와 `bak_d9…` 백업 테이블뿐이고, 시험마다 지운다.
실행: python -m unittest discover -s tools/dbwrite -p 'test_*.py'
"""
import io
import json
import os
import subprocess
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import sbshop_db_write as w

_RAW = os.environ.get("SBSHOP_DBWRITE_TEST_PSQL")
_RAW_RO = os.environ.get("SBSHOP_DBWRITE_TEST_PSQL_RO") or _RAW


def _argv(raw):
    v = json.loads(raw)
    if not isinstance(v, list) or not v or not all(isinstance(x, str) for x in v):
        raise ValueError("argv는 문자열의 JSON 배열이어야 한다")
    return tuple(v)


PSQL = _argv(_RAW) if _RAW else None
PSQL_RO = _argv(_RAW_RO) if _RAW_RO else None

QUIET = "\\set ON_ERROR_STOP on\n\\set QUIET on\n\\pset format unaligned\n\\pset tuples_only on\n"


def run_sql(argv, script, timeout=60, check=True):
    cp = subprocess.run(list(argv), input=(QUIET + script).encode("utf-8"), capture_output=True, timeout=timeout)
    out = cp.stdout.decode("utf-8", "replace")
    if check and cp.returncode != 0:
        raise AssertionError(f"psql 실패 rc={cp.returncode}\n{cp.stderr.decode('utf-8', 'replace')}")
    return out.strip()


CLEANUP = """
DO $sbw_cleanup$ DECLARE r record; BEGIN
  FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'public' AND c.relkind = 'v' AND c.relname LIKE 'sbw\\_t\\_%' LOOP
    EXECUTE format('DROP VIEW public.%I', r.relname);
  END LOOP;
  FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'public' AND c.relkind = 'r' AND (c.relname LIKE 'sbw\\_t\\_%' OR c.relname ~ '^bak_d9[0-9]+_') LOOP
    -- CASCADE가 상속 자식·FK 제약·트리거·규칙을 함께 지운다. 자식이 먼저 사라졌을 수 있어 IF EXISTS
    EXECUTE format('DROP TABLE IF EXISTS public.%I CASCADE', r.relname);
  END LOOP;
  FOR r IN SELECT p.oid::regprocedure AS sig FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
           WHERE n.nspname = 'public' AND p.proname LIKE 'sbw\\_t\\_%' LOOP
    EXECUTE format('DROP FUNCTION IF EXISTS %s CASCADE', r.sig);
  END LOOP;
END $sbw_cleanup$;
"""

SEED = """
CREATE TABLE public.sbw_t_a (id int PRIMARY KEY, v text NOT NULL);
INSERT INTO public.sbw_t_a SELECT g, 'v' || g FROM generate_series(1, 100) g;
CREATE TABLE public.sbw_t_b (a_id int NOT NULL, w text NOT NULL);
INSERT INTO public.sbw_t_b VALUES (1, 'x'), (1, 'y'), (2, 'z');
CREATE TABLE public.sbw_t_small (id int PRIMARY KEY, v text NOT NULL);
INSERT INTO public.sbw_t_small SELECT g, 's' || g FROM generate_series(1, 10) g;
CREATE VIEW public.sbw_t_v AS SELECT * FROM public.sbw_t_a;
ANALYZE public.sbw_t_a;
"""


def job(defect, *steps, title="통합 시험"):
    text = f'defect = "{defect}"\ntitle = "{title}"\n'
    for s in steps:
        text += "\n[[step]]\n" + "".join(f"{k} = {json.dumps(v, ensure_ascii=False)}\n" for k, v in s.items())
    return text


@unittest.skipUnless(PSQL, "SBSHOP_DBWRITE_TEST_PSQL 이 없어 실 DB 통합 시험을 건너뛴다")
class IntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for name, argv in (("쓰기", PSQL), ("읽기", PSQL_RO)):
            db = run_sql(argv, "SELECT current_database();")
            if not db.endswith("_test"):
                raise AssertionError(f"{name} 접속의 DB '{db}' 가 _test 로 끝나지 않는다 — 전부 중단한다")

    def setUp(self):
        run_sql(PSQL, CLEANUP + SEED)
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        dump_dir = root / "backups" / "daily"
        dump_dir.mkdir(parents=True)
        backup_log = root / "ops-backup" / "sbshop-db-backup.log"
        backup_log.parent.mkdir(parents=True)
        self.config = w.Config(
            psql_write=PSQL, psql_check=PSQL_RO, log_file=root / "ops-write" / "sbshop-db-write.log",
            jobs_dir=root / "ops-write" / "jobs", dump_dir=dump_dir, backup_log=backup_log,
            now=lambda: datetime.now().astimezone(), dump_min_bytes=1)
        # 도구는 덤프 이름의 시각을 KST 상수로 읽고 수정 시각과 0~10분인지 본다(N-L2) — 어느 시간대에서 돌려도 맞게 KST로 짓는다
        name = f"sbshop-pre-{datetime.now(timezone(timedelta(hours=9))).strftime('%Y%m%d-%H%M%S')}.dump"
        (dump_dir / name).write_bytes(b"dump")
        (dump_dir / (name + ".sha256")).write_text("0  " + name + "\n")
        backup_log.write_text(f"t OK kind=pre file={name} size=4 sec=1\n", encoding="utf-8")
        self.root = root
        self.n = 0

    def tearDown(self):
        run_sql(PSQL, CLEANUP)
        self.tmp.cleanup()

    # ----------------------------------------------------------------- 도우미
    def q(self, sql):
        return run_sql(PSQL, sql)

    def exists(self, rel):
        return self.q(f"SELECT to_regclass('public.{rel}') IS NOT NULL;") == "t"

    def cli(self, text, command, *extra):
        self.n += 1
        path = self.root / f"job{self.n}.toml"
        path.write_text(text, encoding="utf-8")
        out, err = io.StringIO(), io.StringIO()
        argv = [command, str(path)]
        if command == "apply":
            argv += ["--confirm", w.parse_job(path.read_bytes()).h12]
        rc = w.main(argv + list(extra), config=self.config, stdin=io.BytesIO(b""), stdout=out, stderr=err)
        return rc, out.getvalue() + err.getvalue()

    def forge_dryrun(self, text):
        sha = w.parse_job(text.encode("utf-8")).sha256
        self.config.log_file.parent.mkdir(parents=True, exist_ok=True)
        with open(self.config.log_file, "a", encoding="utf-8") as f:
            f.write(f"t DRYRUN OK defect=D-900 job={sha} tool=0 steps=1 rows=1\n")

    def last_log(self):
        return self.config.log_file.read_text(encoding="utf-8").splitlines()[-1]

    def dry_then_apply(self, text, *extra):
        rc, out = self.cli(text, "dry-run")
        self.assertEqual(rc, 0, out)
        return self.cli(text, "apply", *extra)

    def values(self, ids):
        return self.q(f"SELECT string_agg(v, ',' ORDER BY id) FROM public.sbw_t_a WHERE id IN ({ids});")

    # ----------------------------------------------------------------- §7
    def test_01_update_ok_backs_up_old_values(self):
        t = job("D-901", {"kind": "update", "table": "sbw_t_a", "where": "id IN (1, 2, 3)", "set": "v = 'new'", "expect": 3})
        rc, out = self.dry_then_apply(t)
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.values("1,2,3"), "new,new,new")
        self.assertEqual(self.q("SELECT string_agg(v, ',' ORDER BY id) FROM public.bak_d901_s1_sbw_t_a;"), "v1,v2,v3")
        self.assertIn(" APPLY OK ", self.last_log())

    def test_02_dry_run_changes_nothing(self):
        t = job("D-902", {"kind": "update", "table": "sbw_t_a", "where": "id IN (1, 2, 3)", "set": "v = 'new'", "expect": 3})
        rc, out = self.cli(t, "dry-run")
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.values("1,2,3"), "v1,v2,v3")
        self.assertFalse(self.exists("bak_d902_s1_sbw_t_a"))

    def test_03_expect_mismatch_changes_nothing(self):
        for exp in (2, 4):
            with self.subTest(expect=exp):
                t = job("D-903", {"kind": "update", "table": "sbw_t_a", "where": "id IN (1, 2, 3)", "set": "v = 'new'", "expect": exp})
                rc, out = self.cli(t, "dry-run")
                self.assertEqual(rc, 1, out)
                self.assertIn(f"reason=step=1|phase=target|got=3|expect={exp}", self.last_log())
                self.forge_dryrun(t)
                rc, out = self.cli(t, "apply")
                self.assertEqual(rc, 1, out)
                self.assertTrue(self.last_log().endswith(f"reason=step=1|phase=target|got=3|expect={exp} committed=no"))
                self.assertEqual(self.values("1,2,3"), "v1,v2,v3")
                self.assertFalse(self.exists("bak_d903_s1_sbw_t_a"))

    def test_04_later_step_failure_rolls_back_earlier_steps(self):
        t = job("D-904",
                {"kind": "update", "table": "sbw_t_a", "where": "id = 1", "set": "v = 'new'", "expect": 1},
                {"kind": "delete", "table": "sbw_t_b", "where": "a_id = 1", "expect": 1})
        self.forge_dryrun(t)
        rc, out = self.cli(t, "apply")
        self.assertEqual(rc, 1, out)
        self.assertIn("reason=step=2|phase=target|got=2|expect=1", self.last_log())
        self.assertTrue(self.last_log().endswith(" committed=no"))
        self.assertEqual(self.values("1"), "v1")
        self.assertFalse(self.exists("bak_d904_s1_sbw_t_a"))
        self.assertFalse(self.exists("bak_d904_s2_sbw_t_b"))

    def test_05_delete_then_restore_with_insert_from_backup(self):
        before = self.q("SELECT string_agg(id || ':' || v, ',' ORDER BY id) FROM public.sbw_t_a;")
        rc, out = self.dry_then_apply(job("D-905", {"kind": "delete", "table": "sbw_t_a", "where": "id BETWEEN 1 AND 3", "expect": 3}))
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.q("SELECT count(*) FROM public.sbw_t_a;"), "97")
        rc, out = self.dry_then_apply(job("D-915", {"kind": "insert", "table": "sbw_t_a", "source": "SELECT * FROM bak_d905_s1_sbw_t_a", "expect": 3}))
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.q("SELECT string_agg(id || ':' || v, ',' ORDER BY id) FROM public.sbw_t_a;"), before)

    def test_06_update_then_restore_with_update_from_backup(self):
        rc, out = self.dry_then_apply(job("D-906", {"kind": "update", "table": "sbw_t_a", "where": "id IN (1, 2, 3)", "set": "v = 'new'", "expect": 3}))
        self.assertEqual(rc, 0, out)
        restore = job("D-916", {"kind": "update", "table": "sbw_t_a", "from": "bak_d906_s1_sbw_t_a AS b",
                                "where": "sbw_t_a.id = b.id", "set": "v = b.v", "expect": 3})
        rc, out = self.dry_then_apply(restore)
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.values("1,2,3"), "v1,v2,v3")

    def test_07_insert_ok_and_unique_violation_rolls_back(self):
        rc, out = self.dry_then_apply(job("D-907", {"kind": "insert", "table": "sbw_t_a", "columns": "id, v",
                                                    "source": "VALUES (1001, 'a'), (1002, 'b')", "expect": 2}))
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.q("SELECT string_agg(id::text, ',' ORDER BY id) FROM public.bak_d907_s1_sbw_t_a;"), "1001,1002")
        dup = job("D-917", {"kind": "insert", "table": "sbw_t_a", "columns": "id, v", "source": "VALUES (2001, 'a'), (1, 'b')", "expect": 2})
        self.forge_dryrun(dup)
        rc, out = self.cli(dup, "apply")
        self.assertEqual(rc, 1, out)
        self.assertIn("reason=db-error: duplicate key value violates unique constraint", self.last_log())
        self.assertTrue(self.last_log().endswith(" committed=no"))
        self.assertEqual(self.q("SELECT count(*) FROM public.sbw_t_a WHERE id = 2001;"), "0")
        self.assertFalse(self.exists("bak_d917_s1_sbw_t_a"))

    def test_08_from_join_that_multiplies_rows_is_rejected(self):
        t = job("D-908", {"kind": "update", "table": "sbw_t_a", "from": "sbw_t_b AS b", "where": "sbw_t_a.id = b.a_id AND sbw_t_a.id = 1",
                          "set": "v = b.w", "expect": 1})
        rc, out = self.cli(t, "dry-run")
        self.assertEqual(rc, 1, out)
        self.assertIn("reason=step=1|phase=target|got=2|expect=1", self.last_log())
        self.assertEqual(self.values("1"), "v1")

    def test_09_second_apply_is_refused_by_precheck(self):
        t = job("D-909", {"kind": "update", "table": "sbw_t_a", "where": "id = 1", "set": "v = v || '+'", "expect": 1})
        rc, out = self.dry_then_apply(t)
        self.assertEqual(rc, 0, out)
        rc, out = self.cli(t, "apply")
        self.assertEqual(rc, 2, out)
        self.assertTrue(self.last_log().endswith(" reason=backup-exists"), self.last_log())
        self.assertIn("bak_d909_s1_sbw_t_a", out)
        self.assertEqual(self.values("1"), "v1+")
        rc, out = self.cli(t, "dry-run")
        self.assertEqual(rc, 1, out)
        self.assertIn("reason=step=1|phase=backup_exists", self.last_log())
        self.assertEqual(self.values("1"), "v1+")

    def test_10_bulk_needs_flag(self):
        t = job("D-910", {"kind": "update", "table": "sbw_t_small", "where": "id = 1", "set": "v = 'new'", "expect": 1})
        rc, out = self.cli(t, "dry-run")
        self.assertEqual(rc, 0, out)
        self.assertRegex(out, r"(?m)^1\s+update\s+sbw_t_small\s+1\s+10\s+1\s")
        rc, out = self.cli(t, "apply")
        self.assertEqual(rc, 1, out)
        self.assertIn("reason=phase=bulk", self.last_log())
        self.assertEqual(self.q("SELECT v FROM public.sbw_t_small WHERE id = 1;"), "s1")
        self.assertFalse(self.exists("bak_d910_s1_sbw_t_small"))
        rc, out = self.cli(t, "apply", "--bulk")
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.q("SELECT v FROM public.sbw_t_small WHERE id = 1;"), "new")

    def test_11_missing_table_view_and_syntax_error_leave_no_trace(self):
        cases = [
            ("D-911", {"kind": "delete", "table": "sbw_t_none", "where": "id = 1", "expect": 1}, "phase=table_missing"),
            ("D-921", {"kind": "delete", "table": "sbw_t_v", "where": "id = 1", "expect": 1}, "phase=not_a_table"),
            ("D-931", {"kind": "update", "table": "sbw_t_a", "where": "id = = 1", "set": "v = 'x'", "expect": 1}, "db-error: syntax error"),
        ]
        for defect, step, needle in cases:
            with self.subTest(defect=defect):
                rc, out = self.cli(job(defect, step), "dry-run")
                self.assertEqual(rc, 1, out)
                self.assertIn(needle, self.last_log())
                digits = defect[2:]
                self.assertEqual(self.q(f"SELECT count(*) FROM pg_class WHERE relname LIKE 'bak\\_d{digits}\\_%';"), "0")
        self.assertEqual(self.values("1"), "v1")

    def test_12_lock_timeout(self):
        holder = subprocess.Popen(list(PSQL), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            holder.stdin.write(b"BEGIN;\nSELECT id FROM public.sbw_t_a WHERE id = 1 FOR UPDATE;\nSELECT pg_sleep(30);\nCOMMIT;\n")
            holder.stdin.close()
            deadline = time.time() + 10
            while time.time() < deadline:
                n = self.q("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND query LIKE 'SELECT pg_sleep(30)%' AND state = 'active';")
                if n != "0":
                    break
                time.sleep(0.2)
            else:
                self.fail("잠금을 쥔 접속이 준비되지 않았다")
            t = job("D-912", {"kind": "update", "table": "sbw_t_a", "where": "id = 1", "set": "v = 'new'", "expect": 1})
            started = time.time()
            rc, out = self.cli(t, "dry-run")
            elapsed = time.time() - started
            self.assertEqual(rc, 1, out)
            self.assertIn("lock timeout", self.last_log())
            self.assertLess(elapsed, 20)
            self.assertGreaterEqual(elapsed, 4)
        finally:
            holder.kill()
            holder.wait(timeout=10)
            run_sql(PSQL, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND query LIKE 'SELECT pg_sleep(30)%';", check=False)

    def test_13_strings_with_semicolon_comment_and_korean_are_stored_verbatim(self):
        value = "취소; -- 고객 요청 /* 메모 */ $5 '인용'"
        literal = value.replace("'", "''")
        rc, out = self.dry_then_apply(job("D-913", {"kind": "update", "table": "sbw_t_a", "where": "id = 1", "set": f"v = '{literal}'", "expect": 1}))
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.values("1"), value)

    def test_14_check_script_reads_backup_with_read_only_role(self):
        t = job("D-914", {"kind": "update", "table": "sbw_t_a", "where": "id IN (1, 2)", "set": "v = 'new'", "expect": 2})
        rc, out = self.dry_then_apply(t)
        self.assertEqual(rc, 0, out)
        res = w.run_psql(PSQL_RO, w.build_check_script(w.parse_job(t.encode("utf-8"))), 60)
        self.assertEqual(res.rc, 0, res.err)
        self.assertIn("SBW|CHECK|backup=bak_d914_s1_sbw_t_a|exists=1|rows=2", res.err)
        if _RAW_RO != _RAW:
            self.assertEqual(run_sql(PSQL_RO, "SELECT has_table_privilege('public.sbw_t_a', 'UPDATE');"), "f",
                             "SBSHOP_DBWRITE_TEST_PSQL_RO 는 쓰기 권한이 없는 역할이어야 한다")

    # ----------------------------------------------------------------- §7 15~18 (검증 1차 반영)
    def ids(self, sql):
        out = self.q(sql)
        return set(int(x) for x in out.split()) if out else set()

    def test_15_nondeterministic_where_backs_up_exactly_the_changed_rows(self):
        cond = "id IN (SELECT id FROM sbw_t_a ORDER BY random() LIMIT 5)"
        rc, out = self.dry_then_apply(job("D-9151", {"kind": "update", "table": "sbw_t_a", "where": cond, "set": "v = v || '*'", "expect": 5}))
        self.assertEqual(rc, 0, out)
        changed = self.ids("SELECT id FROM public.sbw_t_a WHERE v LIKE '%*' ORDER BY id;")
        backed = self.ids("SELECT id FROM public.bak_d9151_s1_sbw_t_a ORDER BY id;")
        self.assertEqual(len(changed), 5)
        self.assertEqual(changed, backed)
        rc, out = self.dry_then_apply(job("D-9152", {"kind": "delete", "table": "sbw_t_a", "where": cond, "expect": 5}))
        self.assertEqual(rc, 0, out)
        gone = set(range(1, 101)) - self.ids("SELECT id FROM public.sbw_t_a ORDER BY id;")
        self.assertEqual(len(gone), 5)
        self.assertEqual(gone, self.ids("SELECT id FROM public.bak_d9152_s1_sbw_t_a ORDER BY id;"))

    def test_16_update_with_from_and_nondeterministic_where_never_changes_unbacked_rows(self):
        t = job("D-916", {"kind": "update", "table": "sbw_t_a", "from": "(SELECT 1 AS one) AS x",
                          "where": "x.one = 1 AND sbw_t_a.id IN (SELECT id FROM sbw_t_a ORDER BY random() LIMIT 3)",
                          "set": "v = v || '#'", "expect": 3})
        self.forge_dryrun(t)
        rc, out = self.cli(t, "apply")
        changed = self.ids("SELECT id FROM public.sbw_t_a WHERE v LIKE '%#' ORDER BY id;")
        if rc == 0:
            self.assertEqual(changed, self.ids("SELECT id FROM public.bak_d916_s1_sbw_t_a ORDER BY id;"))
            self.assertEqual(len(changed), 3)
        else:
            self.assertEqual(rc, 1, out)
            self.assertIn("phase=affected", self.last_log())
            self.assertEqual(changed, set())
            self.assertFalse(self.exists("bak_d916_s1_sbw_t_a"))

    def test_17_cascading_tables_are_rejected(self):
        self.q("""
CREATE TABLE public.sbw_t_p (id int PRIMARY KEY);
CREATE TABLE public.sbw_t_c (p_id int REFERENCES public.sbw_t_p (id) ON DELETE CASCADE);
INSERT INTO public.sbw_t_p VALUES (1), (2);
INSERT INTO public.sbw_t_c VALUES (1);
CREATE TABLE public.sbw_t_p2 (id int PRIMARY KEY, v text);
CREATE TABLE public.sbw_t_c2 (p_id int REFERENCES public.sbw_t_p2 (id) ON UPDATE CASCADE);
INSERT INTO public.sbw_t_p2 VALUES (1, 'a');
CREATE TABLE public.sbw_t_p3 (id int PRIMARY KEY, v text);
CREATE TABLE public.sbw_t_c3 (p_id int REFERENCES public.sbw_t_p3 (id));
INSERT INTO public.sbw_t_p3 SELECT g, 'p' FROM generate_series(1, 40) g;
CREATE TABLE public.sbw_t_trg (id int PRIMARY KEY, v text);
INSERT INTO public.sbw_t_trg VALUES (1, 'a');
CREATE FUNCTION public.sbw_t_trg_fn() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END';
CREATE TRIGGER sbw_t_trg_t BEFORE UPDATE ON public.sbw_t_trg FOR EACH ROW EXECUTE FUNCTION public.sbw_t_trg_fn();
CREATE TABLE public.sbw_t_rul (id int PRIMARY KEY, v text);
INSERT INTO public.sbw_t_rul VALUES (1, 'a');
CREATE RULE sbw_t_rul_r AS ON UPDATE TO public.sbw_t_rul DO ALSO NOTIFY sbw_t_rul;
CREATE TABLE public.sbw_t_par (id int, v text);
CREATE TABLE public.sbw_t_kid () INHERITS (public.sbw_t_par);
INSERT INTO public.sbw_t_par VALUES (1, 'a');
""")
        upd = lambda table: {"kind": "update", "table": table, "where": "id = 1", "set": "v = 'x'", "expect": 1}
        dele = lambda table: {"kind": "delete", "table": table, "where": "id = 1", "expect": 1}
        cases = [
            ("D-9171", dele("sbw_t_p"), "phase=cascade_fk"),
            ("D-9172", upd("sbw_t_p2"), "phase=cascade_fk"),
            ("D-9173", upd("sbw_t_trg"), "phase=has_trigger"),
            ("D-9174", upd("sbw_t_rul"), "phase=has_rule"),
            ("D-9175", upd("sbw_t_par"), "phase=has_children"),
        ]
        for defect, step, needle in cases:
            with self.subTest(defect=defect):
                rc, out = self.cli(job(defect, step), "dry-run")
                self.assertEqual(rc, 1, out)
                self.assertIn(needle, self.last_log())
                self.assertEqual(self.q(f"SELECT count(*) FROM pg_class WHERE relname LIKE 'bak\\_d{defect[2:]}\\_%';"), "0")
        # 이 테이블의 delete는 ON DELETE CASCADE가 아니어서(ON UPDATE CASCADE) 통과해야 한다
        rc, out = self.cli(job("D-9176", dele("sbw_t_p2")), "dry-run")
        self.assertEqual(rc, 0, out)
        # NO ACTION FK로만 참조되는 테이블은 통과한다 (40행 중 1행 = 2.5% — 대량 아님)
        rc, out = self.cli(job("D-9177", dele("sbw_t_p3")), "dry-run")
        self.assertEqual(rc, 0, out)
        rc, out = self.cli(job("D-9178", upd("sbw_t_p3")), "dry-run")
        self.assertEqual(rc, 0, out)

    def test_18_ctid_array_works_in_create_update_delete(self):
        for defect, step in (
            ("D-9181", {"kind": "update", "table": "sbw_t_a", "where": "id IN (1, 2)", "set": "v = 'x'", "expect": 2}),
            ("D-9182", {"kind": "update", "table": "sbw_t_a", "from": "sbw_t_b AS b", "where": "sbw_t_a.id = b.a_id AND b.w = 'z'", "set": "v = b.w", "expect": 1}),
            ("D-9183", {"kind": "delete", "table": "sbw_t_a", "where": "id IN (3, 4)", "expect": 2}),
            ("D-9184", {"kind": "delete", "table": "sbw_t_a", "from": "sbw_t_b AS b", "where": "sbw_t_a.id = b.a_id AND b.w = 'z'", "expect": 1}),
        ):
            with self.subTest(defect=defect):
                rc, out = self.cli(job(defect, step), "dry-run")
                self.assertEqual(rc, 0, out)


if __name__ == "__main__":
    unittest.main()
