"""Verify market search SQL in an isolated, temporary PostgreSQL 16 container."""

import pathlib
import argparse
import os
import subprocess
import time
import uuid

BACKEND = pathlib.Path(__file__).resolve().parents[1]
CONTAINER = "sbshop-search-check-" + uuid.uuid4().hex[:10]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--marketplus-jpa", action="store_true", help="Also exercise the actual JPA search/summary/import in isolated PostgreSQL")
args = parser.parse_args()


def run(*args, text=None, check=True):
    result = subprocess.run(args, input=text, text=True, capture_output=True, timeout=45)
    if check and result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result


sql = (BACKEND / "docs/ddl/2026-09-06-market-search-identifiers.sql").read_text()
sql += r"""
CREATE TEMP TABLE cases (document text, expected boolean);
INSERT INTO cases VALUES
 (NULL, false), ('invalid', false), ('{}', false), ('[]', false), ('null', false),
 ('{"goodsNo":null}', false), ('{"goodsNo":false}', false), ('{"goodsNo":{}}', false),
 ('{"goodsNo":[]}', false), ('{"goodsNo":""}', false), ('{"goodsNo":"  \t\r\n"}', false),
 ('{"previousIdentifiers":[{"goodsNo":"OLD"}]}', false),
 ('{"goodsNo":"A123"}', true), ('{"goodsNo":123}', true),
 ('{"goodsNo":" CURRENT ","previousIdentifiers":[{"goodsNo":"OLD"}]}', true);
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM cases WHERE sb_market_has_identifier(document, 'goodsNo') IS DISTINCT FROM expected) THEN
   RAISE EXCEPTION 'identifier contract failed';
 END IF;
END $$;
CREATE TEMP TABLE equality_cases (document text, wanted text, expected boolean);
INSERT INTO equality_cases VALUES
 (NULL,'123',false), ('invalid','123',false), ('{}','123',false), ('[]','123',false),
 ('null','123',false), ('{"goodsNo":null}','123',false), ('{"goodsNo":true}','true',false),
 ('{"goodsNo":{}}','{}',false), ('{"goodsNo":[]}','[]',false), ('{"goodsNo":""}','',false),
 ('{"goodsNo":"123"}',NULL,false), ('{"goodsNo":123}','123',true),
 ('{"goodsNo":"123"}','123',true), ('{"goodsNo":" 123"}','123',false),
 ('{"goodsNo":"123"}','0123',false), ('{"previousIdentifiers":[{"goodsNo":"123"}]}','123',false),
 ('{"goodsNo":" A "}',' A ',true);
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM equality_cases WHERE sb_market_identifier_equals(document, 'goodsNo', wanted) IS DISTINCT FROM expected) THEN
   RAISE EXCEPTION 'exact identifier equality contract failed';
 END IF;
END $$;
CREATE TEMP TABLE products (id bigint PRIMARY KEY);
CREATE TEMP TABLE registrations (product_id bigint, market_type text, connection_state text, identifiers text);
CREATE UNIQUE INDEX ON registrations(product_id, market_type);
INSERT INTO products SELECT generate_series(1, 10000);
INSERT INTO registrations SELECT id, 'COUPANG', 'LINKED', '{"sellerProductId":123}' FROM products;
INSERT INTO registrations SELECT id, 'ELEVEN_STREET', 'LINKED', '{"prdNo":456}' FROM products WHERE id % 2 = 0;
UPDATE registrations SET connection_state = 'DETACHED_DELETED' WHERE product_id = 2 AND market_type = 'ELEVEN_STREET';
UPDATE registrations SET identifiers = '{"previousIdentifiers":[{"sellerProductId":999}]}' WHERE product_id = 3 AND market_type = 'COUPANG';
DO $$ DECLARE matched bigint; BEGIN
 SELECT COUNT(*) INTO matched FROM products p
 WHERE EXISTS (SELECT 1 FROM registrations r WHERE r.product_id=p.id AND r.market_type='COUPANG'
   AND r.connection_state='LINKED' AND sb_market_has_identifier(r.identifiers,'sellerProductId'))
 AND NOT EXISTS (SELECT 1 FROM registrations r WHERE r.product_id=p.id AND r.market_type='ELEVEN_STREET'
   AND r.connection_state='LINKED' AND sb_market_has_identifier(r.identifiers,'prdNo'));
 IF matched <> 5000 THEN RAISE EXCEPTION 'compound filter count: %', matched; END IF;
END $$;
SELECT 'PostgreSQL 16: 15 presence cases, 17 exact identity cases and 10,000-product compound filter passed' AS result;
"""

started = False
try:
    network = ["--publish", "127.0.0.1::5432"] if args.marketplus_jpa else ["--network", "none"]
    run("docker", "run", "--rm", "--detach", "--name", CONTAINER, *network,
        "--env", "POSTGRES_DB=sbshop_marketplus_check",
        "--env", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:16-alpine")
    started = True
    for attempt in range(60):
        if run("docker", "exec", CONTAINER, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", check=False).returncode == 0:
            break
        time.sleep(0.2)
    else:
        raise RuntimeError("Temporary PostgreSQL was not ready")
    result = run("docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", "sbshop_marketplus_check", "-v", "ON_ERROR_STOP=1", text=sql)
    print(result.stdout)
    if args.marketplus_jpa:
        port = run("docker", "port", CONTAINER, "5432/tcp").stdout.strip().split(":")[-1]
        env = dict(os.environ, SBSHOP_MARKETPLUS_TEST_POSTGRES_URL=f"jdbc:postgresql://127.0.0.1:{port}/sbshop_marketplus_check")
        subprocess.run([str(BACKEND / "gradlew"), ":core:test", "--tests", "*MarketPlusTransmissionIntegrationTest", "--rerun-tasks", "--console=plain"],
                       cwd=BACKEND, env=env, check=True, timeout=180)
finally:
    if started:
        run("docker", "stop", "--time", "5", CONTAINER)
