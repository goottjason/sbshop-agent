"""Exercise the actual stock queue service, locking and rollback against isolated PostgreSQL 16."""
import os
import pathlib
import subprocess
import time
import uuid

BACKEND = pathlib.Path(__file__).resolve().parents[1]
CONTAINER = "sbshop-stock-check-" + uuid.uuid4().hex[:10]


def run(*args, check=True):
    result = subprocess.run(args, text=True, capture_output=True, timeout=45)
    if check and result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    return result


started = False
try:
    run("docker", "run", "--rm", "--detach", "--name", CONTAINER,
        "--publish", "127.0.0.1::5432", "--env", "POSTGRES_DB=sbshop_stock_check",
        "--env", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:16-alpine")
    started = True
    for attempt in range(60):
        if run("docker", "exec", CONTAINER, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", check=False).returncode == 0:
            break
        time.sleep(0.2)
    else:
        raise RuntimeError("Temporary PostgreSQL was not ready")
    port = run("docker", "port", CONTAINER, "5432/tcp").stdout.strip().split(":")[-1]
    env = dict(os.environ, SBSHOP_STOCK_TEST_POSTGRES_URL=f"jdbc:postgresql://127.0.0.1:{port}/sbshop_stock_check")
    subprocess.run([str(BACKEND / "gradlew"), ":core:test", "--tests", "*MarketStockSyncIntegrationTest",
                    "--rerun-tasks", "--console=plain"], cwd=BACKEND, env=env, check=True, timeout=240)
finally:
    if started:
        run("docker", "stop", "--time", "5", CONTAINER)
