"""sbshop_db_write 단위 시험 — DB 없이 돈다.

실행: python -m unittest discover -s tools/dbwrite -p 'test_*.py'
"""
import dataclasses
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import types
import unittest
from unittest import mock
from datetime import datetime, timedelta, timezone
from pathlib import Path

import sbshop_db_write as w

GOOD_UPDATE = """\
defect = "D-999"
title  = "주문 3건 상태 정정"

[[step]]
kind   = "update"
table  = "sb_order"
where  = "id IN (101, 102, 103)"
set    = "status = 'CANCELED'"
expect = 3
"""

GOOD_MULTI = """\
defect = "D-999"
title  = "여러 단계"

[[step]]
kind   = "update"
table  = "sb_order"
where  = "id = 1"
set    = "status = 'X'"
from   = "sb_market m"
expect = 1
backup = "order_status"

[[step]]
kind   = "delete"
table  = "sb_order_item"
where  = "order_id = 1"
expect = 2

[[step]]
kind    = "insert"
table   = "sb_example"
columns = "a, b"
source  = "VALUES (1, 2)"
expect  = 1
"""


def job_text(steps, defect='"D-999"', title='"정정"', extra=""):
    head = f"defect = {defect}\ntitle = {title}\n{extra}\n"
    return head + "".join("\n[[step]]\n" + s for s in steps)


def upd(**kw):
    base = {"kind": '"update"', "table": '"sb_order"', "where": '"id = 1"', "set": '"status = 1"', "expect": "1"}
    base.update(kw)
    return "".join(f"{k} = {v}\n" for k, v in base.items() if v is not None)


def parse(text):
    return w.parse_job(text.encode("utf-8"))


# --------------------------------------------------------------------------- 1단계: 작업 파일·조각 검사
class ParseJobAcceptTest(unittest.TestCase):
    def test_update_job_is_parsed(self):
        job = parse(GOOD_UPDATE)
        self.assertEqual(job.defect, "D-999")
        self.assertEqual(job.defect_digits, "999")
        self.assertEqual(len(job.steps), 1)
        s = job.steps[0]
        self.assertEqual((s.index, s.kind, s.table, s.expect), (1, "update", "sb_order", 3))
        self.assertEqual(s.where, "id IN (101, 102, 103)")
        self.assertEqual(s.set, "status = 'CANCELED'")
        self.assertEqual(s.backup, "bak_d999_s1_sb_order")

    def test_job_identity_is_sha256_of_file_bytes(self):
        data = GOOD_UPDATE.encode("utf-8")
        job = w.parse_job(data)
        self.assertEqual(job.sha256, hashlib.sha256(data).hexdigest())
        self.assertEqual(job.h12, job.sha256[:12])
        self.assertEqual(job.tag, f"$sbw_{job.sha256[:12]}$")

    def test_multi_step_job_with_custom_backup_and_insert(self):
        job = parse(GOOD_MULTI)
        self.assertEqual([s.kind for s in job.steps], ["update", "delete", "insert"])
        self.assertEqual(job.steps[0].backup, "bak_d999_order_status")
        self.assertEqual(job.steps[0].from_, "sb_market m")
        self.assertEqual(job.steps[1].backup, "bak_d999_s2_sb_order_item")
        self.assertEqual(job.steps[2].columns, "a, b")
        self.assertEqual(job.steps[2].source, "VALUES (1, 2)")

    def test_defect_digits_used_as_written(self):
        job = parse(job_text([upd()], defect='"D-0012"'))
        self.assertEqual(job.steps[0].backup, "bak_d0012_s1_sb_order")

    def test_twenty_steps_is_allowed(self):
        job = parse(job_text([upd(backup=f'"b{i}"') for i in range(20)]))
        self.assertEqual(len(job.steps), 20)


class ParseJobRejectTest(unittest.TestCase):
    def assertRejected(self, text, needle=None):
        data = text if isinstance(text, bytes) else text.encode("utf-8")
        with self.assertRaises(w.JobError) as cm:
            w.parse_job(data)
        if needle:
            self.assertIn(needle, str(cm.exception))

    def test_invalid_toml(self):
        self.assertRejected("defect = \n")

    def test_not_utf8(self):
        self.assertRejected(b'defect = "D-1"\ntitle = "\xff"\n')

    def test_unknown_top_level_key(self):
        self.assertRejected(job_text([upd()], extra='owner = "x"'), "owner")

    def test_unknown_step_key_typo(self):
        self.assertRejected(job_text([upd(expect=None, expct="1")]), "expct")

    def test_missing_defect(self):
        self.assertRejected('title = "x"\n[[step]]\n' + upd(), "defect")

    def test_defect_format(self):
        for bad in ['"D999"', '"D-"', '"D-123456"', '"d-1"', '"D-1 "', "999", '"D-٩٩٩"', '"D-９９"']:
            with self.subTest(bad=bad):
                self.assertRejected(job_text([upd()], defect=bad), "defect")

    def test_missing_title(self):
        self.assertRejected('defect = "D-1"\n[[step]]\n' + upd(), "title")

    def test_title_must_be_one_line(self):
        self.assertRejected(job_text([upd()], title='"a\\nb"'), "title")

    def test_title_length_limit(self):
        parse(job_text([upd()], title='"' + "가" * 120 + '"'))
        self.assertRejected(job_text([upd()], title='"' + "가" * 121 + '"'), "title")

    def test_title_empty_or_wrong_type(self):
        self.assertRejected(job_text([upd()], title='"  "'), "title")
        self.assertRejected(job_text([upd()], title="3"), "title")

    def test_no_steps(self):
        self.assertRejected('defect = "D-1"\ntitle = "x"\n', "step")

    def test_too_many_steps(self):
        self.assertRejected(job_text([upd(backup=f'"b{i}"') for i in range(21)]), "step")

    def test_step_must_be_array_of_tables(self):
        self.assertRejected('defect = "D-1"\ntitle = "x"\nstep = 1\n', "step")

    def test_unknown_kind(self):
        self.assertRejected(job_text([upd(kind='"merge"')]), "kind")
        self.assertRejected(job_text([upd(kind='"UPDATE"')]), "kind")

    def test_missing_kind(self):
        self.assertRejected(job_text([upd(kind=None)]), "kind")

    def test_table_pattern(self):
        for bad in ['"Sb_order"', '"1abc"', '"public.sb_order"', '"sb order"', '"sb-order"', '""', "1"]:
            with self.subTest(bad=bad):
                self.assertRejected(job_text([upd(table=bad)]), "table")

    def test_table_length(self):
        parse(job_text([upd(table='"' + "a" * 63 + '"', backup='"x"')]))
        self.assertRejected(job_text([upd(table='"' + "a" * 64 + '"', backup='"x"')]), "table")

    def test_table_reserved_prefixes(self):
        for bad in ['"bak_d1_x"', '"zz_old"', '"pg_class"']:
            with self.subTest(bad=bad):
                self.assertRejected(job_text([upd(table=bad)]), "table")

    def test_update_requires_where_and_set(self):
        self.assertRejected(job_text([upd(where=None)]), "where")
        self.assertRejected(job_text([upd(set=None)]), "set")

    def test_delete_rejects_set_and_requires_where(self):
        self.assertRejected(job_text([upd(kind='"delete"')]), "set")
        self.assertRejected(job_text([upd(kind='"delete"', set=None, where=None)]), "where")

    def test_insert_rejects_where_and_requires_source(self):
        ins = dict(kind='"insert"', set=None, where=None)
        self.assertRejected(job_text([upd(**dict(ins, source='"VALUES (1)"', where='"id = 1"'))]), "where")
        self.assertRejected(job_text([upd(**ins)]), "source")
        self.assertRejected(job_text([upd(**ins, source='"VALUES (1)"', **{"from": '"x"'})]), "from")

    def test_update_rejects_insert_keys(self):
        self.assertRejected(job_text([upd(columns='"a"')]), "columns")
        self.assertRejected(job_text([upd(source='"VALUES (1)"')]), "source")

    def test_expect_rules(self):
        for bad in [None, "0", "-1", "true", '"3"', "3.0"]:
            with self.subTest(bad=bad):
                self.assertRejected(job_text([upd(expect=bad)]), "expect")

    def test_fragment_must_be_string(self):
        self.assertRejected(job_text([upd(where="1")]), "where")

    def test_backup_suffix_pattern(self):
        for bad in ['"Order"', '"a-b"', '""', '"a b"', "1"]:
            with self.subTest(bad=bad):
                self.assertRejected(job_text([upd(backup=bad)]), "backup")

    def test_backup_name_length(self):
        # bak_d999_ (9바이트) + 54 = 63 → 통과, 55 → 거부
        parse(job_text([upd(backup='"' + "a" * 54 + '"')]))
        self.assertRejected(job_text([upd(backup='"' + "a" * 55 + '"')]), "backup")

    def test_default_backup_name_too_long_is_rejected(self):
        self.assertRejected(job_text([upd(table='"' + "a" * 60 + '"')]), "backup")

    def test_backup_names_unique_within_job(self):
        self.assertRejected(job_text([upd(backup='"x"'), upd(backup='"x"')]), "backup")
        # 기본 이름은 단계 번호가 들어가 겹치지 않는다
        parse(job_text([upd(), upd()]))

    def test_columns_pattern(self):
        ins = dict(kind='"insert"', set=None, where=None, source='"VALUES (1, 2)"')
        parse(job_text([upd(**ins, columns='"a,b"')]))
        for bad in ['"a b"', '"a, "', '"(a, b)"', '"A"', '"a; drop"', '""', r'"a,\u00a0b"', r'"a,\nb"', r'"a\u2028,b"', r'"a,\u001cb"', r'"a\u0085,b"']:
            with self.subTest(bad=bad):
                self.assertRejected(job_text([upd(**ins, columns=bad)]), "columns")

    def test_source_must_start_with_values_or_select(self):
        ins = dict(kind='"insert"', set=None, where=None)
        parse(job_text([upd(**ins, source='"select 1"')]))
        parse(job_text([upd(**ins, source='"  VALUES (1) ON CONFLICT DO NOTHING"')]))
        for bad in ['"(SELECT 1)"', '"TABLE x"', '"WITH a AS (SELECT 1) SELECT * FROM a"', '"DEFAULT VALUES"']:
            with self.subTest(bad=bad):
                self.assertRejected(job_text([upd(**ins, source=bad)]), "source")

    def test_fragment_rule_applies_through_parse(self):
        self.assertRejected(job_text([upd(where='"id = 1; DROP TABLE x"')]), "where")


class FragmentTest(unittest.TestCase):
    def assertFragRejected(self, field, text, tag=None):
        with self.assertRaises(w.JobError, msg=f"{field}: {text!r} 가 통과했다"):
            w.check_fragment(field, text, tag)

    def assertFragOk(self, field, text, tag=None):
        w.check_fragment(field, text, tag)

    def test_semicolon_outside_string(self):
        self.assertFragRejected("where", "id = 1;")

    def test_line_comment(self):
        self.assertFragRejected("where", "id = 1 -- x")

    def test_block_comment(self):
        self.assertFragRejected("where", "id = 1 /* x */")

    def test_dollar_outside_string(self):
        self.assertFragRejected("where", "id = $1")
        self.assertFragRejected("set", "a = $$x$$")

    def test_unclosed_quotes(self):
        self.assertFragRejected("where", "name = 'abc")
        self.assertFragRejected("where", 'id = 1 AND "col = 2')
        self.assertFragRejected("where", "name = 'it''s")

    def test_paren_balance(self):
        self.assertFragRejected("where", "id IN (1, 2")
        self.assertFragRejected("where", "id = 1) OR (1 = 1")
        self.assertFragRejected("where", "(id = 1))")

    def test_empty_fragment(self):
        self.assertFragRejected("where", "")
        self.assertFragRejected("where", "  \n\t ")

    def test_backslash_anywhere(self):
        self.assertFragRejected("where", "a = 1 \\g")
        self.assertFragRejected("set", "a = 'x\\y'")
        self.assertFragRejected("where", '"a\\b" = 1')

    def test_control_characters_anywhere(self):
        self.assertFragRejected("where", "a = 'x\x00'")
        self.assertFragRejected("where", "a = 1\x07")
        self.assertFragRejected("where", "a = 1\r")
        self.assertFragOk("where", "a = 1\n\tAND b = 2")

    def test_forbidden_words_each(self):
        for word in w.FORBIDDEN_WORDS:
            with self.subTest(word=word):
                self.assertFragRejected("where", f"id IN (SELECT id FROM t WHERE {word.upper()} > 0)")

    def test_forbidden_word_list_matches_design(self):
        self.assertEqual(set(w.FORBIDDEN_WORDS), set(
            "insert update delete merge truncate drop alter create grant revoke into copy call execute commit rollback".split()))

    def test_on_conflict_do_update_is_rejected(self):
        self.assertFragRejected("source", "VALUES (1) ON CONFLICT (id) DO UPDATE SET a = 1")

    def test_set_depth0_extra_words(self):
        for word in ["where", "from", "using", "returning"]:
            with self.subTest(word=word):
                self.assertFragRejected("set", f"a = 1 {word} b")

    def test_set_subquery_may_use_where_and_from(self):
        self.assertFragOk("set", "a = (SELECT x FROM other o WHERE o.id = sb_order.id)")

    def test_from_depth0_extra_words(self):
        self.assertFragRejected("from", "sb_market m WHERE m.id = 1")
        self.assertFragRejected("from", "sb_market m RETURNING *")
        self.assertFragOk("from", "(SELECT id FROM sb_market WHERE x = 1) m")

    def test_source_depth0_returning(self):
        self.assertFragRejected("source", "VALUES (1) RETURNING *")
        self.assertFragOk("source", "SELECT a FROM t WHERE a IN (SELECT a FROM u)")

    def test_where_allows_where_word_in_subquery(self):
        self.assertFragOk("where", "id IN (SELECT order_id FROM sb_order_item WHERE qty > 1)")

    def test_dollar_quote_tag_rejected_even_inside_string(self):
        tag = "$sbw_0123456789ab$"
        self.assertFragRejected("set", f"memo = 'x{tag}y'", tag)
        self.assertFragOk("set", "memo = 'x$sbw_ffffffffffff$y'", tag)

    def test_normal_fragments_with_dangerous_text_inside_strings_pass(self):
        ok = [
            ("set", "memo = '취소; -- 고객 요청 /* 메모 */ $5'"),
            ("where", "memo = 'delete from x; drop table y'"),
            ("where", "name = 'it''s (unbalanced'"),
            ("where", '"delete" = 1 AND "from" = 2'),
            ("set", 'memo = \'a"b\''),
            ("where", 'x = "we""ird"'),
            ("where", "created_at > now() - interval '1 day' AND updated_by IS NULL"),
            ("source", "SELECT * FROM bak_d999_s1_sb_order"),
            ("from", "bak_d999_s1_sb_order AS b"),
        ]
        for field, text in ok:
            with self.subTest(text=text):
                self.assertFragOk(field, text)

    def test_quoted_identifier_limits(self):
        # 서버의 "identifier will be truncated" NOTICE에 원문이 실려 표지 줄처럼 보이는 것을 막는다(N-L1)
        self.assertFragRejected("where", '"a\nb" = 1')
        self.assertFragRejected("where", '"a\tb" = 1')
        self.assertFragRejected("where", '"' + "a" * 64 + '" = 1')
        self.assertFragRejected("where", '"' + "가" * 22 + '" = 1')  # 66바이트
        self.assertFragOk("where", '"' + "a" * 63 + '" = 1')
        self.assertFragOk("where", '"' + "가" * 21 + '" = 1')  # 63바이트
        self.assertFragOk("where", '"' + "a" * 61 + '""" = 1')  # a×61 + 이스케이프된 " = 62바이트
        # 작은따옴표 문자열 안의 줄바꿈·탭·긴 값은 그대로 허용
        self.assertFragOk("set", "memo = 'a\n\tb" + "x" * 100 + "'")

    def test_identifier_that_contains_forbidden_word_is_fine(self):
        self.assertFragOk("set", "update_time = now(), created_by = 'x', into_flag = true")


# --------------------------------------------------------------------------- 2단계: SQL 조립
GOLDEN_UPDATE_DRYRUN = """\
\\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL REPEATABLE READ;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';
SET LOCAL search_path = public;
SET LOCAL client_min_messages = notice;
DO $sbw_HHHH$
#variable_conflict use_column
DECLARE
  _sbw_n bigint;
  _sbw_est double precision;
  _sbw_bulk boolean := false;
  _sbw_tids tid[];
BEGIN
  IF to_regclass('public.sb_order') IS NULL THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=table_missing'; END IF;
  IF (SELECT c.relkind FROM pg_catalog.pg_class c WHERE c.oid = to_regclass('public.sb_order')) <> 'r' THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=not_a_table'; END IF;
  IF to_regclass('public.bak_d999_s1_sb_order') IS NOT NULL THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=backup_exists'; END IF;
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhparent = to_regclass('public.sb_order')) THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=has_children'; END IF;
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_trigger g WHERE g.tgrelid = to_regclass('public.sb_order') AND NOT g.tgisinternal) THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=has_trigger'; END IF;
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite w WHERE w.ev_class = to_regclass('public.sb_order')) THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=has_rule'; END IF;
  IF EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.contype = 'f' AND k.confrelid = to_regclass('public.sb_order') AND k.confupdtype NOT IN ('a', 'r')) THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=cascade_fk'; END IF;
  SELECT CASE WHEN c.reltuples >= 100000 THEN c.reltuples END INTO _sbw_est FROM pg_catalog.pg_class c WHERE c.oid = to_regclass('public.sb_order');
  IF _sbw_est IS NULL THEN SELECT count(*) INTO _sbw_est FROM public.sb_order; END IF;
  SELECT array_agg(sb_order.ctid) INTO _sbw_tids FROM public.sb_order AS sb_order WHERE (id IN (101, 102, 103));
  _sbw_n := coalesce(cardinality(_sbw_tids), 0);
  IF _sbw_n <> 3 THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=target|got=%|expect=3', _sbw_n; END IF;
  CREATE TABLE public.bak_d999_s1_sb_order AS SELECT sb_order.* FROM public.sb_order AS sb_order WHERE sb_order.ctid = ANY (_sbw_tids);
  SELECT count(*) INTO _sbw_n FROM public.bak_d999_s1_sb_order;
  IF _sbw_n <> 3 THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=backup|got=%|expect=3', _sbw_n; END IF;
  UPDATE public.sb_order AS sb_order SET status = 'CANCELED' WHERE sb_order.ctid = ANY (_sbw_tids);
  GET DIAGNOSTICS _sbw_n = ROW_COUNT;
  IF _sbw_n <> 3 THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=affected|got=%|expect=3', _sbw_n; END IF;
  IF 3 > 200 OR 3 > 0.05 * _sbw_est THEN _sbw_bulk := true; END IF;
  RAISE NOTICE 'SBW|STEP|step=1|kind=update|table=sb_order|rows=%|backup=bak_d999_s1_sb_order|est=%|bulk=%', _sbw_n, _sbw_est::bigint, (3 > 200 OR 3 > 0.05 * _sbw_est)::int;
  IF _sbw_bulk AND NOT true THEN RAISE EXCEPTION 'SBW|FAIL|phase=bulk'; END IF;
END
$sbw_HHHH$;
ROLLBACK;
\\echo SBW|END|ROLLBACK
"""


class BuildScriptTest(unittest.TestCase):
    def setUp(self):
        self.job = parse(GOOD_UPDATE)
        self.multi = parse(GOOD_MULTI)

    def test_update_dry_run_matches_design_exactly(self):
        got = w.build_write_script(self.job, dry_run=True, bulk=False)
        self.assertEqual(got, GOLDEN_UPDATE_DRYRUN.replace("HHHH", self.job.h12))

    def test_apply_commits_and_dry_run_rolls_back(self):
        apply = w.build_write_script(self.job, dry_run=False, bulk=False)
        self.assertTrue(apply.endswith("$;\nCOMMIT;\n\\echo SBW|END|COMMIT\n"))
        self.assertNotIn("ROLLBACK", apply)
        dry = w.build_write_script(self.job, dry_run=True, bulk=False)
        self.assertTrue(dry.endswith("$;\nROLLBACK;\n\\echo SBW|END|ROLLBACK\n"))
        self.assertNotIn("COMMIT", dry)

    def test_bulk_ok_flag(self):
        line = "IF _sbw_bulk AND NOT {} THEN RAISE EXCEPTION 'SBW|FAIL|phase=bulk'; END IF;"
        self.assertIn(line.format("false"), w.build_write_script(self.job, dry_run=False, bulk=False))
        self.assertIn(line.format("true"), w.build_write_script(self.job, dry_run=False, bulk=True))
        self.assertIn(line.format("true"), w.build_write_script(self.job, dry_run=True, bulk=False))

    def test_title_is_not_in_sql(self):
        sql = w.build_write_script(self.job, dry_run=False, bulk=False)
        self.assertNotIn(self.job.title, sql)

    def test_dollar_tag_uses_job_hash(self):
        sql = w.build_write_script(self.job, dry_run=False, bulk=False)
        self.assertEqual(sql.count(f"$sbw_{self.job.h12}$"), 2)

    def test_update_with_from(self):
        sql = w.build_write_script(self.multi, dry_run=False, bulk=False)
        self.assertIn("  SELECT array_agg(sb_order.ctid) INTO _sbw_tids FROM public.sb_order AS sb_order, sb_market m WHERE (id = 1);\n", sql)
        self.assertIn("  CREATE TABLE public.bak_d999_order_status AS SELECT sb_order.* FROM public.sb_order AS sb_order WHERE sb_order.ctid = ANY (_sbw_tids);\n", sql)
        self.assertIn("  UPDATE public.sb_order AS sb_order SET status = 'X' FROM sb_market m WHERE (id = 1) AND sb_order.ctid = ANY (_sbw_tids);\n", sql)

    def test_delete_selects_targets_once_and_has_no_using(self):
        sql = w.build_write_script(self.multi, dry_run=False, bulk=False)
        self.assertIn("  SELECT array_agg(sb_order_item.ctid) INTO _sbw_tids FROM public.sb_order_item AS sb_order_item WHERE (order_id = 1);\n", sql)
        self.assertIn("  CREATE TABLE public.bak_d999_s2_sb_order_item AS SELECT sb_order_item.* FROM public.sb_order_item AS sb_order_item WHERE sb_order_item.ctid = ANY (_sbw_tids);\n", sql)
        self.assertIn("  DELETE FROM public.sb_order_item AS sb_order_item WHERE sb_order_item.ctid = ANY (_sbw_tids);\n", sql)
        job = parse(job_text([upd(kind='"delete"', set=None, **{"from": '"sb_market m"'})]))
        sql = w.build_write_script(job, dry_run=False, bulk=False)
        self.assertIn("  SELECT array_agg(sb_order.ctid) INTO _sbw_tids FROM public.sb_order AS sb_order, sb_market m WHERE (id = 1);\n", sql)
        self.assertIn("  DELETE FROM public.sb_order AS sb_order WHERE sb_order.ctid = ANY (_sbw_tids);\n", sql)
        self.assertNotIn("USING", sql)
        self.assertIn("SBW|FAIL|step=1|phase=target|got=%|expect=1", sql)
        self.assertIn("SBW|FAIL|step=1|phase=backup|got=%|expect=1", sql)
        self.assertIn("SBW|STEP|step=1|kind=delete|table=sb_order|", sql)

    def test_where_is_evaluated_once_without_from(self):
        """H1: from 없는 update와 모든 delete는 조건을 한 번만 쓴다."""
        for kind, extra in (("update", {}), ("delete", {"set": None}), ("delete", {"set": None, "from": '"sb_market m"'})):
            with self.subTest(kind=kind, extra=extra):
                job = parse(job_text([upd(kind=f'"{kind}"', where='"random() < 0.5"', **extra)]))
                sql = w.build_write_script(job, dry_run=False, bulk=False)
                self.assertEqual(sql.count("random() < 0.5"), 1)

    def test_update_with_from_reevaluates_inside_target_set(self):
        job = parse(job_text([upd(where='"random() < 0.5"', **{"from": '"sb_market m"'})]))
        sql = w.build_write_script(job, dry_run=False, bulk=False)
        self.assertEqual(sql.count("random() < 0.5"), 2)
        self.assertIn("WHERE (random() < 0.5) AND sb_order.ctid = ANY (_sbw_tids);", sql)

    def test_cascade_guard_by_kind(self):
        sql = w.build_write_script(self.multi, dry_run=False, bulk=False)
        fk = "IF EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.contype = 'f' AND k.confrelid = to_regclass('public.{t}') AND k.{col} NOT IN ('a', 'r')) THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=cascade_fk'; END IF;"
        self.assertIn(fk.format(t="sb_order", col="confupdtype", n=1), sql)
        self.assertIn(fk.format(t="sb_order_item", col="confdeltype", n=2), sql)
        self.assertNotIn("step=3|phase=cascade_fk", sql)
        self.assertEqual(sql.count("phase=cascade_fk"), 2)
        for n, t in ((1, "sb_order"), (2, "sb_order_item"), (3, "sb_example")):
            self.assertIn(f"IF EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhparent = to_regclass('public.{t}')) THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=has_children'; END IF;", sql)
            self.assertIn(f"IF EXISTS (SELECT 1 FROM pg_catalog.pg_trigger g WHERE g.tgrelid = to_regclass('public.{t}') AND NOT g.tgisinternal) THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=has_trigger'; END IF;", sql)
            self.assertIn(f"IF EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite w WHERE w.ev_class = to_regclass('public.{t}')) THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=has_rule'; END IF;", sql)

    def test_insert_with_columns_checks_affected_then_backup(self):
        sql = w.build_write_script(self.multi, dry_run=False, bulk=False)
        block = (
            "  CREATE TABLE public.bak_d999_s3_sb_example (LIKE public.sb_example);\n"
            "  WITH _sbw_ins AS (INSERT INTO public.sb_example (a, b) VALUES (1, 2) RETURNING *) INSERT INTO public.bak_d999_s3_sb_example SELECT * FROM _sbw_ins;\n"
            "  GET DIAGNOSTICS _sbw_n = ROW_COUNT;\n"
            "  IF _sbw_n <> 1 THEN RAISE EXCEPTION 'SBW|FAIL|step=3|phase=affected|got=%|expect=1', _sbw_n; END IF;\n"
            "  SELECT count(*) INTO _sbw_n FROM public.bak_d999_s3_sb_example;\n"
            "  IF _sbw_n <> 1 THEN RAISE EXCEPTION 'SBW|FAIL|step=3|phase=backup|got=%|expect=1', _sbw_n; END IF;\n"
            "  IF 1 > 200 OR 1 > 0.05 * _sbw_est THEN _sbw_bulk := true; END IF;\n"
            "  RAISE NOTICE 'SBW|STEP|step=3|kind=insert|table=sb_example|rows=%|backup=bak_d999_s3_sb_example|est=%|bulk=%', _sbw_n, _sbw_est::bigint, (1 > 200 OR 1 > 0.05 * _sbw_est)::int;\n"
        )
        self.assertIn(block, sql)

    def test_insert_without_columns(self):
        job = parse(job_text([upd(kind='"insert"', set=None, where=None, source='"SELECT * FROM bak_d999_s1_sb_order"')]))
        sql = w.build_write_script(job, dry_run=False, bulk=False)
        self.assertIn("WITH _sbw_ins AS (INSERT INTO public.sb_order SELECT * FROM bak_d999_s1_sb_order RETURNING *) INSERT INTO", sql)

    def test_every_step_has_head_and_tail_in_order(self):
        sql = w.build_write_script(self.multi, dry_run=False, bulk=False)
        positions = []
        for n, s in enumerate(self.multi.steps, 1):
            positions.append(sql.index(f"'SBW|FAIL|step={n}|phase=table_missing'"))
            positions.append(sql.index(f"'SBW|FAIL|step={n}|phase=not_a_table'"))
            positions.append(sql.index(f"to_regclass('public.{s.backup}') IS NOT NULL THEN RAISE EXCEPTION 'SBW|FAIL|step={n}|phase=backup_exists'"))
            positions.append(sql.index(f"'SBW|STEP|step={n}|kind={s.kind}|table={s.table}|rows=%|backup={s.backup}|est=%|bulk=%'"))
        positions.append(sql.index("'SBW|FAIL|phase=bulk'"))
        self.assertEqual(positions, sorted(positions))

    def test_fragment_with_korean_and_semicolon_in_string_is_embedded_verbatim(self):
        job = parse(job_text([upd(set="\"memo = '취소; -- 고객 요청'\"")]))
        sql = w.build_write_script(job, dry_run=False, bulk=False)
        self.assertIn("SET memo = '취소; -- 고객 요청' WHERE sb_order.ctid = ANY (_sbw_tids);", sql)

    def test_check_script_has_one_block_per_backup(self):
        sql = w.build_check_script(self.multi)
        for s in self.multi.steps:
            b = s.backup
            self.assertIn(
                "DO $sbw_c$ DECLARE n bigint; BEGIN\n"
                f"  IF to_regclass('public.{b}') IS NULL THEN RAISE NOTICE 'SBW|CHECK|backup={b}|exists=0';\n"
                f"  ELSE EXECUTE 'SELECT count(*) FROM public.{b}' INTO n; RAISE NOTICE 'SBW|CHECK|backup={b}|exists=1|rows=%', n; END IF;\n"
                "END $sbw_c$;\n", sql)
        self.assertNotIn("CREATE", sql)
        self.assertNotIn("COMMIT", sql)


# --------------------------------------------------------------------------- 3단계: 덤프 조건·dry-run 기록·--confirm
KST = timezone(timedelta(hours=9))
NOW = datetime(2026, 11, 18, 10, 0, 0, tzinfo=KST)
EPOCH = NOW.timestamp()


class TempEnv:
    """임시 폴더 안에 ops-write·backups/daily·ops-backup 로그를 흉내 낸다."""

    def __init__(self, root: Path, now=NOW, psql_write=("never-called",), psql_check=("never-called",), timeout=30.0):
        self.root = root
        self.dump_dir = root / "backups" / "daily"
        self.dump_dir.mkdir(parents=True)
        self.backup_log = root / "ops-backup" / "sbshop-db-backup.log"
        self.backup_log.parent.mkdir(parents=True)
        self.config = w.Config(
            psql_write=tuple(psql_write), psql_check=tuple(psql_check),
            log_file=root / "ops-write" / "sbshop-db-write.log", jobs_dir=root / "ops-write" / "jobs",
            dump_dir=self.dump_dir, backup_log=self.backup_log, now=lambda: now,
            dump_min_bytes=100, psql_timeout=timeout, epoch=lambda: EPOCH, psql_env=None)

    def dump(self, name, size=200, sha=True, log="OK", kind=None, sha_name=None, age=None):
        """age: 파일 수정 시각이 EPOCH보다 얼마나 이전인가. 없으면 이름의 시각(KST)을 따른다."""
        path = self.dump_dir / name
        path.write_bytes(b"x" * size)
        if age is None:
            m = re.search(r"([0-9]{8})-([0-9]{6})", name)
            try:
                age = NOW - datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S").replace(tzinfo=KST)
            except (AttributeError, ValueError):
                age = timedelta(minutes=1)
        mtime = EPOCH - age.total_seconds()
        os.utime(path, (mtime, mtime))
        if sha:
            (self.dump_dir / (sha_name or name + ".sha256")).write_text("00  " + name + "\n", encoding="utf-8")
        if log:
            kind = kind or ("pre" if name.startswith("sbshop-pre-") else "daily")
            with open(self.backup_log, "a", encoding="utf-8") as f:
                f.write(f"2026-11-18T09:59:00+09:00 {log} kind={kind} file={name} size={size} sec=40\n")
        return name

    def log_line(self, line):
        self.config.log_file.parent.mkdir(parents=True, exist_ok=True)
        with open(self.config.log_file, "a", encoding="utf-8") as f:
            f.write(line + "\n")


def stamp(dt):
    return dt.strftime("%Y%m%d-%H%M%S")


class FreshDumpTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = TempEnv(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def found(self):
        return w.find_fresh_dump(self.env.config)[0]

    def test_pre_dump_now_passes(self):
        name = self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")
        self.assertEqual(self.found(), name)

    def test_daily_dump_passes(self):
        name = self.env.dump(f"sbshop-{stamp(NOW - timedelta(minutes=5))}.dump")
        self.assertEqual(self.found(), name)

    def test_exactly_30_minutes_passes(self):
        name = self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=30))}.dump")
        self.assertEqual(self.found(), name)

    def test_older_than_30_minutes_fails(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=30, seconds=1))}.dump")
        self.assertIsNone(self.found())

    def test_future_dump_fails(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW + timedelta(seconds=1))}.dump")
        self.assertIsNone(self.found())

    def test_missing_sha256_fails(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump", sha=False)
        self.assertIsNone(self.found())

    def test_only_dump_name_plus_sha256_sidecar_is_accepted(self):
        name = f"sbshop-pre-{stamp(NOW)}.dump"
        self.env.dump(name, sha_name=name[:-len(".dump")] + ".sha256")
        self.assertIsNone(self.found())

    def test_log_file_field_with_path_does_not_count(self):
        name = f"sbshop-pre-{stamp(NOW)}.dump"
        self.env.dump(name, log=None)
        with open(self.env.backup_log, "a", encoding="utf-8") as f:
            f.write(f"2026-11-18 09:59:00 KST OK kind=pre file=/srv/backups/daily/{name} bytes=200 secs=40\n")
        self.assertIsNone(self.found())

    def test_server_log_line_format_is_accepted(self):
        name = f"sbshop-pre-{stamp(NOW)}.dump"
        self.env.dump(name, log=None)
        with open(self.env.backup_log, "a", encoding="utf-8") as f:
            f.write(f"2026-11-18 09:59:00 KST OK kind=pre file={name} bytes=200 secs=40\n")
        self.assertEqual(self.found(), name)

    def test_too_small_fails(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump", size=99)
        self.assertIsNone(self.found())

    def test_min_size_boundary_passes(self):
        name = self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump", size=100)
        self.assertEqual(self.found(), name)

    def test_missing_log_line_fails(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump", log=None)
        self.assertIsNone(self.found())

    def test_fail_log_line_is_not_ok(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump", log="FAIL")
        self.assertIsNone(self.found())

    def test_log_line_with_other_kind_fails(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump", kind="weekly")
        self.assertIsNone(self.found())

    def test_log_line_for_other_file_does_not_count(self):
        name = f"sbshop-pre-{stamp(NOW)}.dump"
        self.env.dump(name, log=None)
        with open(self.env.backup_log, "a", encoding="utf-8") as f:
            f.write(f"x OK kind=pre file={name}x\n")
            f.write(f"x OK kind=pre file=a{name}\n")
        self.assertIsNone(self.found())

    def test_latest_by_name_time_is_judged_without_fallback(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=10))}.dump")
        self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=1))}.dump", sha=False)
        self.assertIsNone(self.found())

    def test_latest_wins_over_older_daily(self):
        self.env.dump(f"sbshop-{stamp(NOW - timedelta(minutes=10))}.dump")
        newest = self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=2))}.dump")
        self.assertEqual(self.found(), newest)

    def test_non_matching_names_are_ignored(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump.partial")
        self.env.dump(f"other-{stamp(NOW)}.dump")
        self.env.dump("sbshop-pre-2026111\u0668-100000.dump", age=timedelta(0))
        self.assertIsNone(self.found())

    # ----- K12 ③: 나이는 파일 수정 시각(epoch)으로 잰다
    def test_age_is_file_mtime_not_name_time(self):
        # 이름 09:58:30(KST) → 수정 09:59:15(45초 뒤, 서버 실측과 같은 간격) → 지금 10:00이면 45초 전
        name = self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(seconds=90))}.dump", age=timedelta(seconds=45))
        self.assertEqual(self.found(), name)

    def test_stale_mtime_fails_even_if_name_is_fresh(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=31))}.dump", age=timedelta(minutes=30, seconds=1))
        self.assertIsNone(self.found())

    def test_mtime_exactly_30_minutes_passes(self):
        name = self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=31))}.dump", age=timedelta(minutes=30))
        self.assertEqual(self.found(), name)

    def test_future_mtime_fails(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=5))}.dump", age=timedelta(seconds=-5))
        self.assertIsNone(self.found())

    # ----- K12 ③ N-L2: 수정 시각 − 이름 시각(KST 고정 해석)이 0~10분
    def test_touched_old_dump_is_rejected(self):
        # 2시간 전에 뜬 덤프를 방금 touch한 경우
        self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(hours=2))}.dump", age=timedelta(minutes=1))
        self.assertIsNone(self.found())

    def test_mtime_before_name_time_is_rejected(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=5))}.dump", age=timedelta(minutes=5, seconds=1))
        self.assertIsNone(self.found())

    def test_name_to_mtime_gap_boundaries(self):
        for gap, ok in ((timedelta(0), True), (timedelta(minutes=10), True), (timedelta(minutes=10, seconds=1), False)):
            with self.subTest(gap=gap):
                for p in self.env.dump_dir.iterdir():
                    p.unlink()
                name = f"sbshop-pre-{stamp(NOW - timedelta(minutes=12))}.dump"
                self.env.dump(name, age=timedelta(minutes=12) - gap)
                self.assertEqual(self.found(), name if ok else None)

    def test_name_time_is_read_as_kst_regardless_of_local_zone(self):
        # 이름은 KST 상수로 해석한다. 지역 시간대·now의 시간대와 무관하게 같은 판정
        # 지역 시간대가 KST인 PC에서는 같은 결과가 우연히 나오므로, 다른 시간대의 새 프로세스에서 덤프 시험을 다시 돌린다
        # (TZ는 POSIX와 Windows C 런타임 모두 프로세스 시작 때 읽는다)
        here = Path(__file__).resolve().parent
        tests = [f"test_sbshop_db_write.FreshDumpTest.{n}" for n in (
            "test_age_is_file_mtime_not_name_time", "test_name_to_mtime_gap_boundaries", "test_touched_old_dump_is_rejected")]
        for tz in ("UTC0", "EST5EDT"):
            with self.subTest(tz=tz):
                env = dict(os.environ, TZ=tz)
                r = subprocess.run([sys.executable, "-m", "unittest", *tests], cwd=here, env=env, capture_output=True)
                self.assertEqual(r.returncode, 0, r.stderr.decode("utf-8", "replace")[-800:])

    def test_wall_clock_and_time_zone_do_not_affect_dump_judgement(self):
        name = self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=3))}.dump", age=timedelta(minutes=2))
        for now in (datetime(2000, 1, 1, tzinfo=timezone.utc), datetime(2026, 11, 18, 10, 0, tzinfo=timezone(timedelta(hours=-11)))):
            with self.subTest(now=now):
                cfg = dataclasses.replace(self.env.config, now=lambda now=now: now)
                self.assertEqual(w.find_fresh_dump(cfg)[0], name)

    def test_selection_compares_stamp_strings_not_full_names(self):
        # 전체 이름으로 비교하면 'sbshop-pre-…' 가 'sbshop-2…' 보다 크다. 시각 문자열이 늦은 daily를 골라야 한다
        self.env.dump("sbshop-pre-20261118-095000.dump", age=timedelta(minutes=1))
        daily = self.env.dump("sbshop-20261118-095500.dump", age=timedelta(minutes=1))
        self.assertEqual(self.found(), daily)

    def test_missing_dump_dir_fails(self):
        shutil.rmtree(self.env.dump_dir)
        self.assertIsNone(self.found())

    def test_missing_backup_log_fails(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")
        self.env.backup_log.unlink()
        self.assertIsNone(self.found())


SHA = "a" * 64


class DryrunRecordTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = TempEnv(Path(self.tmp.name))
        self.log = self.env.config.log_file

    def tearDown(self):
        self.tmp.cleanup()

    def test_missing_log(self):
        self.assertFalse(w.dryrun_recorded(self.log, SHA))

    def test_dryrun_ok_line_counts(self):
        self.env.log_line(f"2026-11-18T09:00:00+09:00 DRYRUN OK defect=D-999 job={SHA} tool=000000000000 steps=1 rows=3")
        self.assertTrue(w.dryrun_recorded(self.log, SHA))

    def test_other_lines_do_not_count(self):
        for line in [
            f"t DRYRUN FAIL defect=D-999 job={SHA} tool=0 reason=step=1|phase=affected|got=5|expect=3",
            f"t APPLY OK defect=D-999 job={SHA} tool=0 steps=1 rows=3",
            f"t DRYRUN OK defect=D-999 job={'b' * 64} tool=0 steps=1 rows=3",
            f"t DRYRUN OK defect=D-999 job={SHA[:12]} tool=0 steps=1 rows=3",
            f"t DRYRUN OK defect=D-999 job={SHA}0 tool=0 steps=1 rows=3",
            f"t DRYRUN OKAY defect=D-999 job={SHA} tool=0",
        ]:
            self.env.log_line(line)
        self.assertFalse(w.dryrun_recorded(self.log, SHA))


class ApplyPreconditionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = TempEnv(Path(self.tmp.name))
        self.job = parse(GOOD_UPDATE)

    def tearDown(self):
        self.tmp.cleanup()

    def ready(self):
        self.env.log_line(f"t DRYRUN OK defect=D-999 job={self.job.sha256} tool=0 steps=1 rows=3")
        return self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")

    def test_all_satisfied(self):
        name = self.ready()
        reason, dump, _ = w.apply_preconditions(self.job, self.job.h12, False, self.env.config)
        self.assertIsNone(reason)
        self.assertEqual(dump, name)

    def test_confirm_mismatch(self):
        self.ready()
        for bad in ["", self.job.h12[:11], self.job.sha256, self.job.h12.upper(), "0" * 12]:
            with self.subTest(bad=bad):
                reason, _, _ = w.apply_preconditions(self.job, bad, False, self.env.config)
                self.assertEqual(reason, "confirm-mismatch")

    def test_no_dryrun(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")
        reason, _, _ = w.apply_preconditions(self.job, self.job.h12, False, self.env.config)
        self.assertEqual(reason, "no-dryrun")

    def test_no_fresh_dump(self):
        self.env.log_line(f"t DRYRUN OK defect=D-999 job={self.job.sha256} tool=0 steps=1 rows=3")
        reason, _, _ = w.apply_preconditions(self.job, self.job.h12, False, self.env.config)
        self.assertEqual(reason, "no-fresh-dump")

    def test_confirm_is_checked_before_dryrun_and_dump(self):
        reason, _, _ = w.apply_preconditions(self.job, "0" * 12, False, self.env.config)
        self.assertEqual(reason, "confirm-mismatch")

    def test_bulk_total_over_200_needs_flag(self):
        job = parse(job_text([upd(expect="150"), upd(expect="51")]))
        self.env.log_line(f"t DRYRUN OK defect=D-999 job={job.sha256} tool=0 steps=2 rows=150+51")
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")
        reason, _, _ = w.apply_preconditions(job, job.h12, False, self.env.config)
        self.assertEqual(reason, "bulk-needs-flag")
        reason, _, _ = w.apply_preconditions(job, job.h12, True, self.env.config)
        self.assertIsNone(reason)

    def test_total_200_is_not_bulk(self):
        job = parse(job_text([upd(expect="150"), upd(expect="50")]))
        self.env.log_line(f"t DRYRUN OK defect=D-999 job={job.sha256} tool=0 steps=2 rows=150+50")
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")
        reason, _, _ = w.apply_preconditions(job, job.h12, False, self.env.config)
        self.assertIsNone(reason)


class FakePwd:
    def __init__(self, home):
        self.home = home

    def getpwuid(self, uid):
        return types.SimpleNamespace(pw_dir=self.home)


ENV_KEYS = ["SBSHOP_DBWRITE_TEST_PSQL", "SBSHOP_DBWRITE_TEST_PSQL_RO", "PGHOST", "PGDATABASE", "PGUSER",
            "HOME", "USERPROFILE", "TZ", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "PATH", "LANG"]


class TamperedEnv:
    def __enter__(self):
        self.saved = {k: os.environ.get(k) for k in ENV_KEYS}
        for k in ENV_KEYS:
            if k == "PATH":
                continue
            os.environ[k] = "/elsewhere" if k in ("HOME", "USERPROFILE", "DOCKER_CONFIG") else "tcp://elsewhere:2375"
        os.environ["TZ"] = "America/Los_Angeles"
        return self

    def __exit__(self, *exc):
        for k, v in self.saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


ACCOUNT_HOME = "/srv/account-home"


class DefaultConfigTest(unittest.TestCase):
    def setUp(self):
        self.pwd = mock.patch.object(w, "_pwd", FakePwd(ACCOUNT_HOME))
        self.pwd.start()

    def tearDown(self):
        self.pwd.stop()

    def test_production_targets_are_constants(self):
        c = w.default_config()
        # K12 ④: 계정에 저장된 docker 컨텍스트에 기대지 않는다
        self.assertEqual(c.psql_write, ("/usr/bin/docker", "--context", "default", "exec", "-i", "projects-postgres-1",
                                        "psql", "-X", "-U", "sbshop", "-d", "sbshop", "-v", "ON_ERROR_STOP=1", "-f", "-"))
        self.assertEqual(c.psql_check, ("/usr/bin/docker", "--context", "default", "exec", "-i", "projects-postgres-1",
                                        "psql", "-X", "-U", "sbshop_ro", "-d", "sbshop", "-v", "ON_ERROR_STOP=1", "-f", "-"))
        home = Path(ACCOUNT_HOME)
        self.assertEqual(c.log_file, home / "ops-write" / "sbshop-db-write.log")
        self.assertEqual(c.jobs_dir, home / "ops-write" / "jobs")
        self.assertEqual(c.dump_dir, home / "backups" / "daily")
        self.assertEqual(c.backup_log, home / "ops-backup" / "sbshop-db-backup.log")
        self.assertEqual(c.dump_min_bytes, 10 * 1024 * 1024)
        self.assertEqual(c.dump_max_age, timedelta(minutes=30))
        self.assertEqual(c.psql_timeout, 300)
        self.assertIsNotNone(c.now().utcoffset())
        self.assertIs(c.epoch, time.time)

    def test_psql_env_is_fixed(self):
        self.assertEqual(w.default_config().psql_env,
                         {"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "HOME": ACCOUNT_HOME, "LANG": "C.UTF-8"})

    def test_environment_cannot_redirect_targets(self):
        before = w.default_config()
        with TamperedEnv():
            after = w.default_config()
        fields = ("psql_write", "psql_check", "log_file", "jobs_dir", "dump_dir", "backup_log", "psql_env",
                  "dump_min_bytes", "dump_max_age", "psql_timeout")
        self.assertEqual([getattr(before, f) for f in fields], [getattr(after, f) for f in fields])

    def test_home_falls_back_to_path_home_only_without_pwd(self):
        with mock.patch.object(w, "_pwd", None):
            self.assertEqual(w.default_config().log_file, Path.home() / "ops-write" / "sbshop-db-write.log")


# --------------------------------------------------------------------------- 4단계: 실행·표지 판정·확인·로그·보관 (가짜 psql)
FAKE_PSQL = r'''
import json, pathlib, shutil, sys, time
d = pathlib.Path(sys.argv[1])
counter = d / "count"
n = int(counter.read_text()) + 1 if counter.exists() else 1
counter.write_text(str(n))
(d / f"stdin_{n}.sql").write_bytes(sys.stdin.buffer.read())
import os
(d / f"env_{n}.json").write_text(json.dumps(dict(os.environ)), encoding="utf-8")
spec = json.loads((d / "spec.json").read_text(encoding="utf-8"))
for src in spec.get("snapshot", []):
    p = pathlib.Path(src)
    if p.exists():
        shutil.copy(p, d / f"snap_{n}_{p.name}")
resp = spec["responses"][min(n, len(spec["responses"])) - 1]
time.sleep(resp.get("sleep", 0))
sys.stdout.buffer.write(resp.get("stdout", "").encode("utf-8"))
sys.stderr.buffer.write(resp.get("stderr", "").encode("utf-8"))
sys.exit(resp.get("rc", 0))
'''


class FakePsql:
    def __init__(self, root: Path, name: str):
        self.dir = root / name
        self.dir.mkdir()
        self.script = root / "fake_psql.py"
        if not self.script.exists():
            self.script.write_text(FAKE_PSQL, encoding="utf-8")
        self.respond()

    @property
    def argv(self):
        return (sys.executable, str(self.script), str(self.dir))

    def respond(self, *responses, snapshot=()):
        spec = {"responses": list(responses) or [{"rc": 0}], "snapshot": [str(p) for p in snapshot]}
        (self.dir / "spec.json").write_text(json.dumps(spec), encoding="utf-8")

    @property
    def calls(self):
        c = self.dir / "count"
        return int(c.read_text()) if c.exists() else 0

    def stdin(self, n=1):
        return (self.dir / f"stdin_{n}.sql").read_text(encoding="utf-8")

    def env(self, n=1):
        return json.loads((self.dir / f"env_{n}.json").read_text(encoding="utf-8"))

    def snapshot(self, n, name):
        p = self.dir / f"snap_{n}_{name}"
        return p.read_text(encoding="utf-8") if p.exists() else None


def step_notice(s, rows=None, est=1000, bulk=0, prefix="psql:<stdin>:30: "):
    rows = s.expect if rows is None else rows
    return (f"{prefix}NOTICE:  SBW|STEP|step={s.index}|kind={s.kind}|table={s.table}|rows={rows}"
            f"|backup={s.backup}|est={est}|bulk={bulk}\n")


def write_ok(job, end="COMMIT", skip=(), rows=None):
    err = "".join(step_notice(s, rows=(rows or {}).get(s.index)) for s in job.steps if s.index not in skip)
    return {"rc": 0, "stdout": f"BEGIN\nSET\nSET\nSET\nSET\nDO\n{end}\nSBW|END|{end}\n", "stderr": err}


def check_out(job, exists=None, rows=None, rc=0):
    lines = []
    for s in job.steps:
        e = (exists or {}).get(s.index, 1)
        if e:
            lines.append(f"psql:<stdin>:3: NOTICE:  SBW|CHECK|backup={s.backup}|exists=1|rows={(rows or {}).get(s.index, s.expect)}\n")
        else:
            lines.append(f"psql:<stdin>:3: NOTICE:  SBW|CHECK|backup={s.backup}|exists=0\n")
    return {"rc": rc, "stdout": "DO\n" * len(job.steps), "stderr": "".join(lines)}


def fail_out(reason, rc=3):
    return {"rc": rc, "stdout": "BEGIN\nSET\nSET\nSET\nSET\n",
            "stderr": f"psql:<stdin>:40: ERROR:  SBW|FAIL|{reason}\n"
                      "CONTEXT:  PL/pgSQL function inline_code_block line 20 at RAISE\n"}


def unwritable_log(config):
    """로그 파일 자리에 폴더를 두어 쓰기를 실패하게 한다."""
    config.log_file.mkdir(parents=True, exist_ok=True)
    return config


class BrokenStdout(io.StringIO):
    def write(self, s):
        raise BrokenPipeError(32, "Broken pipe")


class CliTestBase(unittest.TestCase):
    job_text = GOOD_UPDATE

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.pw = FakePsql(root, "write")
        self.pc = FakePsql(root, "check")
        self.env = TempEnv(root, psql_write=self.pw.argv, psql_check=self.pc.argv, timeout=20.0)
        self.jobfile = root / "job.toml"
        self.jobfile.write_bytes(self.job_text.encode("utf-8"))
        self.job = w.parse_job(self.job_text.encode("utf-8"))
        self.out, self.err = io.StringIO(), io.StringIO()

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *argv, stdin=None, stdout=None, config=None):
        return w.main(list(argv), config=config or self.env.config, stdin=stdin or io.BytesIO(b""),
                      stdout=stdout or self.out, stderr=self.err)

    def log_lines(self):
        p = self.env.config.log_file
        return p.read_text(encoding="utf-8").splitlines() if p.exists() else []

    def make_ready(self):
        self.env.log_line(f"2026-11-18T09:50:00+09:00 DRYRUN OK defect=D-999 job={self.job.sha256} tool=000000000000 steps=1 rows=3")
        return self.env.dump(f"sbshop-pre-{stamp(NOW - timedelta(minutes=1))}.dump")

    def apply(self, *extra, **kw):
        return self.run_cli("apply", str(self.jobfile), "--confirm", self.job.h12, *extra, **kw)

    def assertNoPsql(self):
        self.assertEqual((self.pw.calls, self.pc.calls), (0, 0), "psql이 불렸다")


TS = "2026-11-18T10:00:00+09:00"
TOOL_RE = "[0-9a-f]{12}"


class CheckCommandTest(CliTestBase):
    def test_check_prints_hash_and_sql_without_db(self):
        rc = self.run_cli("check", str(self.jobfile))
        self.assertEqual(rc, 0)
        self.assertIn(self.job.sha256, self.out.getvalue())
        self.assertIn(w.build_write_script(self.job, dry_run=False, bulk=False), self.out.getvalue())
        self.assertNoPsql()
        self.assertEqual(self.log_lines(), [])

    def test_check_invalid_job_is_refused(self):
        self.jobfile.write_text(job_text([upd(where='"id = 1;"')]), encoding="utf-8")
        self.assertEqual(self.run_cli("check", str(self.jobfile)), 2)
        self.assertNoPsql()

    def test_missing_job_file_is_refused(self):
        self.assertEqual(self.run_cli("check", str(self.jobfile) + ".none"), 2)


class OutTest(unittest.TestCase):
    def test_encoding_error_does_not_stop_output(self):
        buf = io.BytesIO()
        stream = io.TextIOWrapper(buf, encoding="ascii", errors="strict")
        out = w.Out(stream)
        out("한글 abc")
        out("next")
        stream.flush()
        text = buf.getvalue().decode("ascii")
        self.assertIn("abc", text)
        self.assertIn("next", text)

    def test_broken_pipe_is_silent(self):
        out = w.Out(BrokenStdout())
        out("a")
        out("b")
        self.assertTrue(out.broken)


class UsageTest(CliTestBase):
    def test_usage_errors_exit_64(self):
        j = str(self.jobfile)
        for argv in [[], ["frob", j], ["check"], ["check", j, "extra"], ["apply", j], ["apply", j, "--confirm"],
                     ["dry-run", j, "--bulk"], ["dry-run", j, "--confirm", "x"], ["check", j, "--bulk"],
                     ["apply", j, "--confirm", "a", "--confirm", "b"], ["--version", "x"], ["apply", j, "--force"]]:
            with self.subTest(argv=argv):
                self.assertEqual(self.run_cli(*argv), 64)
        self.assertNoPsql()
        self.assertEqual(self.log_lines(), [])

    def test_script_requires_isolated_mode(self):
        # K12 ⑤: 스크립트로 실행할 때 python3 -I 가 아니면 사용법 오류(64). main()은 검사하지 않는다
        script = str(Path(w.__file__).resolve())
        plain = subprocess.run([sys.executable, script, "--version"], capture_output=True)
        self.assertEqual(plain.returncode, 64, plain.stderr.decode("utf-8", "replace"))
        self.assertIn("-I", plain.stderr.decode("utf-8", "replace"))
        self.assertEqual(plain.stdout, b"")
        isolated = subprocess.run([sys.executable, "-I", script, "--version"], capture_output=True)
        self.assertEqual(isolated.returncode, 0, isolated.stderr.decode("utf-8", "replace"))
        self.assertTrue(isolated.stdout.decode("utf-8").startswith("sbshop_db_write "))

    def test_version_prints_own_file_hash(self):
        self.assertEqual(self.run_cli("--version"), 0)
        own = hashlib.sha256(Path(w.__file__).read_bytes()).hexdigest()[:12]
        self.assertEqual(self.out.getvalue().strip(), f"sbshop_db_write {w.TOOL_VERSION} {own}")


class DryRunTest(CliTestBase):
    def test_dry_run_ok(self):
        self.pw.respond(write_ok(self.job, end="ROLLBACK"))
        rc = self.run_cli("dry-run", str(self.jobfile))
        self.assertEqual(rc, 0, self.err.getvalue())
        self.assertEqual(self.pw.stdin(), w.build_write_script(self.job, dry_run=True, bulk=False))
        self.assertEqual(self.pc.calls, 0)
        self.assertEqual(len(self.log_lines()), 1)
        self.assertRegex(self.log_lines()[0],
                         rf"^{re.escape(TS)} DRYRUN OK defect=D-999 job={self.job.sha256} tool={TOOL_RE} steps=1 rows=3$")
        self.assertIn(self.job.h12, self.out.getvalue())

    def test_dry_run_fail_marker(self):
        self.pw.respond(fail_out("step=1|phase=affected|got=5|expect=3"))
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)
        self.assertRegex(self.log_lines()[0],
                         rf"^{re.escape(TS)} DRYRUN FAIL defect=D-999 job={self.job.sha256} tool={TOOL_RE} "
                         r"reason=step=1\|phase=affected\|got=5\|expect=3$")

    def test_dry_run_without_end_marker_fails(self):
        r = write_ok(self.job, end="ROLLBACK")
        r["stdout"] = r["stdout"].replace("SBW|END|ROLLBACK\n", "")
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)
        self.assertIn(" DRYRUN FAIL ", self.log_lines()[0])

    def test_dry_run_with_commit_end_marker_fails(self):
        self.pw.respond(write_ok(self.job, end="COMMIT"))
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)

    def test_dry_run_rows_mismatch_fails(self):
        self.pw.respond(write_ok(self.job, end="ROLLBACK", rows={1: 2}))
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)

    def test_dry_run_nonzero_exit_fails_even_with_markers(self):
        r = write_ok(self.job, end="ROLLBACK")
        r["rc"] = 1
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)

    def test_marker_text_echoed_in_sql_context_is_not_a_marker(self):
        r = write_ok(self.job, end="ROLLBACK")
        r["stderr"] = ("psql:<stdin>:20: ERROR:  syntax error at or near \"x\"\n"
                       "LINE 5:   IF _sbw_n <> 3 THEN RAISE EXCEPTION 'SBW|FAIL|step=1|phase=affected|got=%|expect=3'\n"
                       "LINE 6:   RAISE NOTICE 'SBW|STEP|step=1|kind=update|table=sb_order|rows=3|backup=bak_d999_s1_sb_order|est=1|bulk=0'\n")
        r["stdout"] = "SBW|END|ROLLBACK\n"
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)
        self.assertIn("reason=db-error: syntax error at or near", self.log_lines()[0])

    def test_missing_command_tag_fails(self):
        r = write_ok(self.job, end="ROLLBACK")
        r["stdout"] = r["stdout"].replace("\nROLLBACK\n", "\n")
        self.assertNotIn("\nROLLBACK\n", "\n" + r["stdout"])
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)
        self.assertTrue(self.log_lines()[0].endswith(" reason=no-commit-tag"))

    def test_command_tag_must_be_whole_line(self):
        r = write_ok(self.job, end="ROLLBACK")
        r["stdout"] = r["stdout"].replace("\nROLLBACK\n", "\n ROLLBACK\nxROLLBACK\n")
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)

    def test_end_marker_must_be_whole_line(self):
        for bad in ("xSBW|END|ROLLBACK", " SBW|END|ROLLBACK", "SBW|END|ROLLBACKx", "SBW|END|ROLLBACK "):
            with self.subTest(bad=bad):
                r = write_ok(self.job, end="ROLLBACK")
                r["stdout"] = r["stdout"].replace("SBW|END|ROLLBACK\n", bad + "\n")
                self.pw.respond(r)
                self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)

    def test_step_marker_must_be_whole_line(self):
        r = write_ok(self.job, end="ROLLBACK")
        # 줄 중간에 든 표지(앞에 다른 글자가 있는 줄)는 인정하지 않는다
        r["stderr"] = "LINE 3:  x " + step_notice(self.job.steps[0], prefix="")
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)
        self.assertIn("reason=no-step-marker|step=1", self.log_lines()[0])

    def test_dry_run_result_log_failure_warns_and_keeps_exit_code(self):
        self.pw.respond(write_ok(self.job, end="ROLLBACK"))
        cfg = unwritable_log(self.env.config)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile), config=cfg), 0)
        self.assertIn("기록 실패", self.err.getvalue())

    def test_completed_run_ignores_error_looking_lines(self):
        # N-L1: 종료코드 0 + 명령 태그 + 끝 표지 = 끝까지 실행됨. ERROR처럼 보이는 줄은 NOTICE 본문의 일부다
        r = write_ok(self.job, end="ROLLBACK")
        r["stderr"] += "ERROR:  SBW|FAIL|step=9|phase=fake\npsql:<stdin>:7: ERROR:  looks like an error\n"
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 0, self.err.getvalue())
        self.assertIn(" DRYRUN OK ", self.log_lines()[0])

    def test_completed_run_still_checks_step_markers(self):
        r = write_ok(self.job, end="ROLLBACK", rows={1: 2})
        r["stderr"] += "ERROR:  SBW|FAIL|step=9|phase=fake\n"
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)
        self.assertIn("reason=step-marker-mismatch|step=1", self.log_lines()[0])

    def test_incomplete_run_still_uses_error_lines(self):
        r = write_ok(self.job, end="ROLLBACK")
        r["stderr"] += "psql:<stdin>:7: ERROR:  SBW|FAIL|step=1|phase=affected|got=4|expect=3\n"
        r["rc"] = 3
        self.pw.respond(r)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)
        self.assertIn("reason=step=1|phase=affected|got=4|expect=3", self.log_lines()[0])

    def test_dry_run_invalid_job_never_calls_psql(self):
        self.jobfile.write_text(job_text([upd(expect="0")]), encoding="utf-8")
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 2)
        self.assertNoPsql()

    def test_dry_run_timeout(self):
        self.pw.respond({"rc": 0, "sleep": 10})
        cfg = dataclasses.replace(self.env.config, psql_timeout=1.0)
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile), config=cfg), 1)
        self.assertRegex(self.log_lines()[0], r" DRYRUN FAIL .* reason=timeout$")

    def test_dry_run_reads_job_from_stdin(self):
        self.pw.respond(write_ok(self.job, end="ROLLBACK"))
        rc = self.run_cli("dry-run", "-", stdin=io.BytesIO(self.job_text.encode("utf-8")))
        self.assertEqual(rc, 0, self.err.getvalue())
        self.assertIn(f"job={self.job.sha256}", self.log_lines()[0])

    def test_log_appends(self):
        self.pw.respond(write_ok(self.job, end="ROLLBACK"))
        self.run_cli("dry-run", str(self.jobfile))
        self.run_cli("dry-run", str(self.jobfile))
        self.assertEqual(len(self.log_lines()), 2)

    def test_log_has_no_title_or_fragments(self):
        self.pw.respond(write_ok(self.job, end="ROLLBACK"))
        self.run_cli("dry-run", str(self.jobfile))
        text = "\n".join(self.log_lines())
        self.assertNotIn(self.job.title, text)
        self.assertNotIn("CANCELED", text)
        self.assertNotIn("101", text)


class DryRunMultiTest(CliTestBase):
    job_text = GOOD_MULTI

    def test_missing_step_marker_fails(self):
        self.pw.respond(write_ok(self.job, end="ROLLBACK", skip={2}))
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 1)

    def test_rows_listed_in_order(self):
        self.pw.respond(write_ok(self.job, end="ROLLBACK"))
        self.assertEqual(self.run_cli("dry-run", str(self.jobfile)), 0, self.err.getvalue())
        self.assertTrue(self.log_lines()[0].endswith(" steps=3 rows=1+2+1"))


class ApplyTest(CliTestBase):
    def post_check(self, resp):
        """사전 확인(백업 테이블 없음) 뒤 쓰기 뒤 확인 응답."""
        self.pc.respond(check_out(self.job, exists={s.index: 0 for s in self.job.steps}), resp)

    def test_apply_ok(self):
        dump = self.make_ready()
        self.pw.respond(write_ok(self.job), snapshot=[self.env.config.log_file])
        self.post_check(check_out(self.job))
        rc = self.apply()
        self.assertEqual(rc, 0, self.err.getvalue())
        self.assertEqual(self.pw.stdin(), w.build_write_script(self.job, dry_run=False, bulk=False))
        self.assertEqual(self.pc.calls, 2)
        self.assertEqual(self.pc.stdin(1), w.build_check_script(self.job))
        self.assertEqual(self.pc.stdin(2), w.build_check_script(self.job))
        lines = self.log_lines()[1:]
        self.assertEqual(len(lines), 2)
        self.assertRegex(lines[0], rf"^{re.escape(TS)} APPLY START defect=D-999 job={self.job.sha256} tool={TOOL_RE} dump={re.escape(dump)} bulk=0$")
        self.assertRegex(lines[1], rf"^{re.escape(TS)} APPLY OK defect=D-999 job={self.job.sha256} tool={TOOL_RE} steps=1 rows=3 backups=bak_d999_s1_sb_order dump={re.escape(dump)}$")
        # START는 실행 전에 남는다
        snap = self.pw.snapshot(1, self.env.config.log_file.name)
        self.assertIn(" APPLY START ", snap)
        self.assertNotIn(" APPLY OK ", snap)
        self.assertIn("committed=yes", self.out.getvalue())

    def test_apply_archives_job_and_sql_before_running(self):
        self.make_ready()
        jobs = self.env.config.jobs_dir
        self.pw.respond(write_ok(self.job))
        self.post_check(check_out(self.job))
        self.assertEqual(self.apply(), 0, self.err.getvalue())
        tomls = sorted(jobs.glob("*.toml"))
        sqls = sorted(jobs.glob("*.sql"))
        self.assertEqual(len(tomls), 1)
        self.assertEqual(len(sqls), 1)
        self.assertIn(self.job.h12, tomls[0].name)
        self.assertEqual(tomls[0].read_bytes(), self.job_text.encode("utf-8"))
        self.assertEqual(sqls[0].read_text(encoding="utf-8"), w.build_write_script(self.job, dry_run=False, bulk=False))

    def test_apply_with_bulk_flag(self):
        dump = self.make_ready()
        self.pw.respond(write_ok(self.job))
        self.post_check(check_out(self.job))
        self.assertEqual(self.apply("--bulk"), 0, self.err.getvalue())
        self.assertIn("NOT true THEN RAISE EXCEPTION 'SBW|FAIL|phase=bulk'", self.pw.stdin())
        self.assertTrue(self.log_lines()[1].endswith(f"dump={dump} bulk=1"))

    def assertRefused(self, reason, *extra, check_calls=0):
        rc = self.run_cli("apply", str(self.jobfile), *extra) if extra else self.apply()
        self.assertEqual(rc, 2)
        self.assertEqual(self.pw.calls, 0, "쓰기용 psql이 불렸다")
        self.assertEqual(self.pc.calls, check_calls)
        last = self.log_lines()[-1]
        self.assertRegex(last, rf"^{re.escape(TS)} APPLY REFUSED defect=\S+ job=\S+ tool={TOOL_RE} reason={reason}$")
        self.assertFalse(any(" APPLY START " in l for l in self.log_lines()))
        self.assertFalse(self.env.config.jobs_dir.exists() and any(self.env.config.jobs_dir.iterdir()))

    def test_refused_without_dryrun(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")
        self.assertRefused("no-dryrun")

    def test_refused_on_confirm_mismatch(self):
        self.make_ready()
        self.assertRefused("confirm-mismatch", "--confirm", "000000000000")

    def test_refused_without_fresh_dump(self):
        self.env.log_line(f"t DRYRUN OK defect=D-999 job={self.job.sha256} tool=0 steps=1 rows=3")
        self.assertRefused("no-fresh-dump")

    def test_refused_bulk_without_flag(self):
        self.jobfile.write_text(job_text([upd(expect="201")]), encoding="utf-8")
        self.assertRefused("bulk-needs-flag", "--confirm", w.parse_job(self.jobfile.read_bytes()).h12)

    def test_refused_invalid_job(self):
        self.make_ready()
        self.jobfile.write_text(job_text([upd(table='"bak_d1_x"')]), encoding="utf-8")
        self.assertRefused("invalid-job")
        self.assertIn(f"job={hashlib.sha256(self.jobfile.read_bytes()).hexdigest()}", self.log_lines()[-1])

    # ----- 사전 확인 (K7 ④)
    def test_precheck_refuses_when_backup_table_exists(self):
        self.make_ready()
        self.pc.respond(check_out(self.job))
        self.assertRefused("backup-exists", check_calls=1)
        self.assertEqual(self.pc.stdin(1), w.build_check_script(self.job))
        self.assertIn("bak_d999_s1_sb_order", self.out.getvalue() + self.err.getvalue())

    def test_precheck_refuses_when_check_fails(self):
        self.make_ready()
        self.pc.respond({"rc": 2, "stderr": "psql: error: connection failed\n"})
        self.assertRefused("check-unavailable", check_calls=1)

    def test_precheck_refuses_when_markers_missing(self):
        self.make_ready()
        self.pc.respond({"rc": 0, "stdout": "DO\n", "stderr": ""})
        self.assertRefused("check-unavailable", check_calls=1)

    def test_precheck_refuses_when_check_cannot_start(self):
        self.make_ready()
        cfg = dataclasses.replace(self.env.config, psql_check=(str(Path(self.tmp.name) / "no-such-psql"),))
        rc = self.apply(config=cfg)
        self.assertEqual(rc, 2)
        self.assertEqual(self.pw.calls, 0)
        self.assertTrue(self.log_lines()[-1].endswith(" reason=check-unavailable"))

    def test_precheck_runs_after_dump_check(self):
        self.env.log_line(f"t DRYRUN OK defect=D-999 job={self.job.sha256} tool=0 steps=1 rows=3")
        self.pc.respond(check_out(self.job))
        self.assertRefused("no-fresh-dump", check_calls=0)

    # ----- committed 판정 (§4-4 순서 1~5)
    def apply_fail(self, write, check):
        self.make_ready()
        self.pw.respond(write)
        self.post_check(check)
        rc = self.apply()
        self.assertEqual(rc, 1)
        self.assertEqual(self.pc.calls, 2, "실패해도 확인 스크립트를 돌려야 한다")
        last = self.log_lines()[-1]
        self.assertRegex(last, rf"^{re.escape(TS)} APPLY FAIL defect=D-999 job={self.job.sha256} tool={TOOL_RE} reason=.+ committed=(yes|no|unknown)$")
        return last

    def test_fail_marker_and_no_backup_means_not_committed(self):
        last = self.apply_fail(fail_out("step=1|phase=affected|got=5|expect=3"), check_out(self.job, exists={1: 0}))
        self.assertTrue(last.endswith(" reason=step=1|phase=affected|got=5|expect=3 committed=no"))
        self.assertIn("committed=no", self.out.getvalue())

    def test_fail_marker_means_not_committed_even_if_backup_seen(self):
        last = self.apply_fail(fail_out("step=1|phase=backup_exists"), check_out(self.job))
        self.assertTrue(last.endswith(" reason=step=1|phase=backup_exists committed=no"))

    def test_fail_marker_means_not_committed_even_if_check_fails(self):
        last = self.apply_fail(fail_out("step=1|phase=affected|got=5|expect=3"), {"rc": 2, "stderr": "psql: error: x\n"})
        self.assertTrue(last.endswith(" committed=no"))

    def test_timeout_with_backup_present_means_committed(self):
        self.make_ready()
        self.pw.respond({"rc": 0, "sleep": 10})
        self.post_check(check_out(self.job))
        cfg = dataclasses.replace(self.env.config, psql_timeout=1.0)
        self.assertEqual(self.apply(config=cfg), 1)
        self.assertTrue(self.log_lines()[-1].endswith(" reason=timeout committed=yes"))

    def test_check_rows_mismatch_is_fail_but_committed(self):
        last = self.apply_fail(write_ok(self.job), check_out(self.job, rows={1: 2}))
        self.assertTrue(last.endswith(" committed=yes"))
        self.assertIn("reason=check-mismatch", last)

    def test_check_script_failure_means_unknown(self):
        last = self.apply_fail(write_ok(self.job), {"rc": 2, "stderr": "psql: error: connection failed\n"})
        self.assertTrue(last.endswith(" committed=unknown"))

    def test_missing_end_marker_with_backup_present(self):
        r = write_ok(self.job)
        r["stdout"] = ""
        last = self.apply_fail(r, check_out(self.job))
        self.assertTrue(last.endswith(" committed=yes"))

    def test_commit_seen_but_no_backup_is_unknown(self):
        last = self.apply_fail(write_ok(self.job), check_out(self.job, exists={1: 0}))
        self.assertTrue(last.endswith(" committed=unknown"))

    def test_no_marker_no_backup_is_not_committed(self):
        last = self.apply_fail({"rc": 2, "stderr": "server closed the connection unexpectedly\n"},
                               check_out(self.job, exists={1: 0}))
        self.assertTrue(last.endswith(" committed=no"))

    def test_completed_apply_ignores_error_looking_lines_for_ok(self):
        self.make_ready()
        r = write_ok(self.job)
        r["stderr"] += "psql:<stdin>:7: ERROR:  looks like an error\n"
        self.pw.respond(r)
        self.post_check(check_out(self.job))
        self.assertEqual(self.apply(), 0, self.err.getvalue())
        self.assertIn(" APPLY OK ", self.log_lines()[-1])

    def test_completed_apply_skips_rule1_for_committed(self):
        # 끝까지 실행됐고 백업이 다 있으면, ERROR처럼 보이는 줄이 있어도 committed=yes (실제로 커밋됐는데 no가 나오던 경로)
        r = write_ok(self.job)
        r["stderr"] += "ERROR:  SBW|FAIL|step=1|phase=fake\n"
        last = self.apply_fail(r, check_out(self.job, rows={1: 2}))
        self.assertTrue(last.endswith(" reason=check-mismatch committed=yes"), last)

    def test_any_server_error_means_not_committed_even_if_backup_seen(self):
        # 같은 작업 둘이 동시에 돌아 뒤의 것이 "이미 있는 테이블" 오류로 끝난 경우(L4)
        r = {"rc": 3, "stderr": "psql:<stdin>:20: ERROR:  relation \"bak_d999_s1_sb_order\" already exists\n"}
        last = self.apply_fail(r, check_out(self.job))
        self.assertTrue(last.endswith(" committed=no"), last)

    def test_no_backup_after_timeout_is_unknown(self):
        self.make_ready()
        self.pw.respond({"rc": 0, "sleep": 10})
        self.post_check(check_out(self.job, exists={1: 0}))
        cfg = dataclasses.replace(self.env.config, psql_timeout=1.0)
        self.assertEqual(self.apply(config=cfg), 1)
        self.assertTrue(self.log_lines()[-1].endswith(" reason=timeout committed=unknown"))
        self.assertIn("2분", self.out.getvalue() + self.err.getvalue())

    def test_no_backup_with_unclear_exit_is_unknown(self):
        for rc in (-9, 1, 0, 137):
            with self.subTest(rc=rc):
                self.tearDown()
                self.setUp()
                last = self.apply_fail({"rc": rc, "stdout": "", "stderr": ""}, check_out(self.job, exists={1: 0}))
                self.assertTrue(last.endswith(" committed=unknown"), last)

    def test_no_backup_when_psql_itself_failed_is_no(self):
        for rc in (2, 3):
            with self.subTest(rc=rc):
                self.tearDown()
                self.setUp()
                last = self.apply_fail({"rc": rc, "stdout": "", "stderr": ""}, check_out(self.job, exists={1: 0}))
                self.assertTrue(last.endswith(" committed=no"), last)

    def test_check_exit_code_nonzero_is_failure_even_with_markers(self):
        r = check_out(self.job)
        r["rc"] = 1
        last = self.apply_fail(write_ok(self.job), r)
        self.assertIn("reason=check-failed", last)
        self.assertTrue(last.endswith(" committed=unknown"))

    def test_precheck_exit_code_nonzero_refuses(self):
        self.make_ready()
        r = check_out(self.job, exists={1: 0})
        r["rc"] = 1
        self.pc.respond(r)
        self.assertRefused("check-unavailable", check_calls=1)

    def test_refusal_log_failure_keeps_exit_2(self):
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")
        cfg = unwritable_log(self.env.config)
        self.assertEqual(self.apply(config=cfg), 2)
        self.assertIn("기록 실패", self.err.getvalue())
        self.assertEqual(self.pw.calls + self.pc.calls, 0)

    def test_invalid_job_log_failure_keeps_exit_2(self):
        self.jobfile.write_text(job_text([upd(expect="0")]), encoding="utf-8")
        cfg = unwritable_log(self.env.config)
        self.assertEqual(self.run_cli("apply", str(self.jobfile), "--confirm", "x" * 12, config=cfg), 2)
        self.assertIn("기록 실패", self.err.getvalue())

    def test_psql_runs_with_configured_environment_only(self):
        self.make_ready()
        self.pw.respond(write_ok(self.job))
        self.post_check(check_out(self.job))
        env = {"SBW_FIXED": "1"}
        for k in ("SYSTEMROOT", "SystemRoot", "PATH"):  # Windows에서 파이썬 가짜 psql이 뜨는 데 필요
            if k in os.environ:
                env[k] = os.environ[k]
        cfg = dataclasses.replace(self.env.config, psql_env=env)
        os.environ["DOCKER_HOST"] = "tcp://elsewhere:2375"
        try:
            self.assertEqual(self.apply(config=cfg), 0, self.err.getvalue())
        finally:
            os.environ.pop("DOCKER_HOST", None)
        for child in (self.pw.env(1), self.pc.env(1), self.pc.env(2)):
            self.assertEqual(child.get("SBW_FIXED"), "1")
            self.assertNotIn("DOCKER_HOST", child)

    def test_db_error_reason_is_first_error_line_without_detail(self):
        long_msg = "duplicate key value violates unique constraint \"sb_order_pkey\"" + " x" * 80
        r = {"rc": 3, "stderr": f"psql:<stdin>:40: ERROR:  {long_msg}\nDETAIL:  Key (id)=(777) already exists.\n"
                                 "CONTEXT:  SQL statement \"UPDATE ...\"\n"}
        last = self.apply_fail(r, check_out(self.job, exists={1: 0}))
        reason = re.search(r" reason=(.*) committed=no$", last).group(1)
        self.assertTrue(reason.startswith("db-error: duplicate key value violates"))
        self.assertLessEqual(len(reason), len("db-error: ") + 120)
        self.assertNotIn("777", last)

    def test_broken_stdout_still_logs(self):
        self.make_ready()
        self.pw.respond(write_ok(self.job))
        self.post_check(check_out(self.job))
        rc = self.apply(stdout=BrokenStdout())
        self.assertEqual(rc, 0)
        self.assertIn(" APPLY OK ", self.log_lines()[-1])

    # ----- 실행 뒤 기록 실패
    def _failing_result_log(self):
        real = w.append_log

        def fake(config, event, *a, **kw):
            if event in ("APPLY OK", "APPLY FAIL"):
                raise OSError(28, "No space left on device")
            return real(config, event, *a, **kw)
        return mock.patch.object(w, "append_log", side_effect=fake)

    def test_result_log_failure_does_not_hide_ok(self):
        self.make_ready()
        self.pw.respond(write_ok(self.job))
        self.post_check(check_out(self.job))
        with self._failing_result_log():
            rc = self.apply()
        self.assertEqual(rc, 0)
        shown = self.out.getvalue() + self.err.getvalue()
        self.assertIn("APPLY OK", shown)
        self.assertIn("committed=yes", shown)
        self.assertIn("기록 실패", shown)
        self.assertIn(" APPLY START ", self.log_lines()[-1])

    def test_result_log_failure_does_not_hide_fail(self):
        self.make_ready()
        self.pw.respond(fail_out("step=1|phase=affected|got=5|expect=3"))
        self.post_check(check_out(self.job, exists={1: 0}))
        with self._failing_result_log():
            rc = self.apply()
        self.assertEqual(rc, 1)
        shown = self.out.getvalue() + self.err.getvalue()
        self.assertIn("APPLY FAIL", shown)
        self.assertIn("committed=no", shown)
        self.assertIn("기록 실패", shown)

    def test_start_log_failure_refuses_without_running(self):
        self.make_ready()
        self.pc.respond(check_out(self.job, exists={1: 0}))
        real = w.append_log

        def fake(config, event, *a, **kw):
            if event == "APPLY START":
                raise OSError(13, "Permission denied")
            return real(config, event, *a, **kw)
        with mock.patch.object(w, "append_log", side_effect=fake):
            rc = self.apply()
        self.assertEqual(rc, 2)
        self.assertEqual(self.pw.calls, 0)


class ApplyMultiTest(CliTestBase):
    job_text = GOOD_MULTI

    def ready(self):
        self.env.log_line(f"t DRYRUN OK defect=D-999 job={self.job.sha256} tool=0 steps=3 rows=1+2+1")
        self.env.dump(f"sbshop-pre-{stamp(NOW)}.dump")

    def test_mixed_backup_existence_means_unknown(self):
        self.ready()
        self.pw.respond({"rc": 3, "stderr": "server closed the connection unexpectedly\n"})
        self.pc.respond(check_out(self.job, exists={1: 0, 2: 0, 3: 0}), check_out(self.job, exists={2: 0}))
        self.assertEqual(self.apply(), 1)
        self.assertTrue(self.log_lines()[-1].endswith(" committed=unknown"))

    def test_precheck_refuses_if_any_one_backup_exists(self):
        self.ready()
        self.pc.respond(check_out(self.job, exists={1: 0, 3: 0}))
        self.assertEqual(self.apply(), 2)
        self.assertEqual(self.pw.calls, 0)
        self.assertTrue(self.log_lines()[-1].endswith(" reason=backup-exists"))
        shown = self.out.getvalue() + self.err.getvalue()
        self.assertIn("bak_d999_s2_sb_order_item", shown)
        self.assertNotIn("bak_d999_order_status", shown.split("REFUSED", 1)[-1])

    def test_apply_ok_lists_all_backups(self):
        self.ready()
        self.pw.respond(write_ok(self.job))
        self.pc.respond(check_out(self.job, exists={1: 0, 2: 0, 3: 0}), check_out(self.job))
        self.assertEqual(self.apply(), 0, self.err.getvalue())
        self.assertIn(" steps=3 rows=1+2+1 backups=bak_d999_order_status,bak_d999_s2_sb_order_item,bak_d999_s3_sb_example dump=",
                      self.log_lines()[-1])


if __name__ == "__main__":
    unittest.main()
