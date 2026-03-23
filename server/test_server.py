import os
import shutil
import tempfile

import pytest
from fastapi.testclient import TestClient

# Override data dir before importing app
_test_dir = tempfile.mkdtemp()
os.environ["_TEST_DATA_DIR"] = _test_dir

import config
config.DATA_DIR = type(config.DATA_DIR)(_test_dir)
config.DB_FILES = {
    "production": config.DATA_DIR / "wellness_prod.db",
    "test": config.DATA_DIR / "wellness_test.db",
}

from main import app

client = TestClient(app)


@pytest.fixture(autouse=True)
def clean_db():
    yield
    for f in config.DATA_DIR.glob("*.db*"):
        f.unlink(missing_ok=True)


def make_interval(device_id="AA:BB:CC:DD:EE:FF", ts=1000, hr=120, rr=800):
    return {
        "device_id": device_id,
        "timestamp_device": ts,
        "timestamp_phone": ts + 5,
        "heart_rate_bpm": hr,
        "rr_interval_ms": rr,
        "rr_sequence_index": 0,
        "is_gap": False,
        "sensor_type": "garmin_hrm",
        "session_id": "session-1",
    }


def test_health_default_environment():
    resp = client.get("/api/v1/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["environment"] == "production"
    assert data["intervals_count"] == 0


def test_health_test_environment():
    resp = client.get("/api/v1/health", headers={"X-Environment": "test"})
    assert resp.status_code == 200
    assert resp.json()["environment"] == "test"


def test_batch_sync_inserts_intervals():
    intervals = [make_interval(ts=i) for i in range(10)]
    resp = client.post(
        "/api/v1/intervals/batch",
        json={"intervals": intervals},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] == 10
    assert data["duplicates"] == 0
    assert data["total_received"] == 10

    # Verify via health endpoint
    health = client.get("/api/v1/health").json()
    assert health["intervals_count"] == 10


def test_batch_sync_idempotent():
    intervals = [make_interval(ts=100), make_interval(ts=200)]

    # First sync
    resp1 = client.post("/api/v1/intervals/batch", json={"intervals": intervals})
    assert resp1.json()["accepted"] == 2

    # Second sync — same data, should be all duplicates
    resp2 = client.post("/api/v1/intervals/batch", json={"intervals": intervals})
    data = resp2.json()
    assert data["accepted"] == 0
    assert data["duplicates"] == 2

    # Total count should still be 2
    health = client.get("/api/v1/health").json()
    assert health["intervals_count"] == 2


def test_batch_sync_partial_duplicates():
    batch1 = [make_interval(ts=100)]
    client.post("/api/v1/intervals/batch", json={"intervals": batch1})

    batch2 = [make_interval(ts=100), make_interval(ts=200)]
    resp = client.post("/api/v1/intervals/batch", json={"intervals": batch2})
    data = resp.json()
    assert data["accepted"] == 1
    assert data["duplicates"] == 1


def test_environment_isolation():
    prod_interval = [make_interval(ts=100)]
    test_interval = [make_interval(ts=200)]

    client.post("/api/v1/intervals/batch", json={"intervals": prod_interval})
    client.post(
        "/api/v1/intervals/batch",
        json={"intervals": test_interval},
        headers={"X-Environment": "test"},
    )

    prod_health = client.get("/api/v1/health").json()
    test_health = client.get(
        "/api/v1/health", headers={"X-Environment": "test"}
    ).json()

    assert prod_health["intervals_count"] == 1
    assert test_health["intervals_count"] == 1


def test_empty_batch():
    resp = client.post("/api/v1/intervals/batch", json={"intervals": []})
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] == 0
    assert data["total_received"] == 0


def test_gap_flag_stored():
    interval = make_interval(ts=100)
    interval["is_gap"] = True
    client.post("/api/v1/intervals/batch", json={"intervals": [interval]})

    from database import get_db
    conn = get_db("production")
    cursor = conn.execute(
        "SELECT is_gap FROM intervals WHERE timestamp_device = 100"
    )
    row = cursor.fetchone()
    conn.close()
    assert row[0] == 1


@pytest.fixture(scope="session", autouse=True)
def cleanup():
    yield
    shutil.rmtree(_test_dir, ignore_errors=True)
