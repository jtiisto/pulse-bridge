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
    "production": config.DATA_DIR / "pulse_bridge_prod.db",
    "test": config.DATA_DIR / "pulse_bridge_test.db",
}
config.DIAG_DIR = config.DATA_DIR / "diagnostics"

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


def test_zero_rr_interval_stored():
    # rr_interval_ms == 0 is a known sensor artifact; it must be accepted and
    # persisted verbatim (filtering happens downstream, not at ingestion).
    interval = make_interval(ts=100, rr=0)
    resp = client.post("/api/v1/intervals/batch", json={"intervals": [interval]})
    assert resp.status_code == 200
    assert resp.json()["accepted"] == 1

    from database import get_db
    conn = get_db("production")
    row = conn.execute(
        "SELECT rr_interval_ms FROM intervals WHERE timestamp_device = 100"
    ).fetchone()
    conn.close()
    assert row[0] == 0


def make_accel_summary(device_id="AA:BB:CC:DD:EE:FF", window_start=1000):
    return {
        "device_id": device_id,
        "window_start": window_start,
        "magnitude_mean": 1.02,
        "magnitude_std": 0.15,
        "magnitude_max": 2.34,
        "sample_count": 250,
        "sensor_type": "polar_pvs",
        "session_id": "session-1",
    }


def test_accelerometer_batch_inserts_summaries():
    summaries = [make_accel_summary(window_start=i * 1000) for i in range(5)]
    resp = client.post(
        "/api/v1/accelerometer/batch",
        json={"summaries": summaries},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] == 5
    assert data["duplicates"] == 0
    assert data["total_received"] == 5

    health = client.get("/api/v1/health").json()
    assert health["accelerometer_summaries_count"] == 5


def test_accelerometer_batch_idempotent():
    summaries = [make_accel_summary(window_start=100), make_accel_summary(window_start=200)]

    resp1 = client.post("/api/v1/accelerometer/batch", json={"summaries": summaries})
    assert resp1.json()["accepted"] == 2

    resp2 = client.post("/api/v1/accelerometer/batch", json={"summaries": summaries})
    data = resp2.json()
    assert data["accepted"] == 0
    assert data["duplicates"] == 2

    health = client.get("/api/v1/health").json()
    assert health["accelerometer_summaries_count"] == 2


def test_accelerometer_batch_partial_duplicates():
    batch1 = [make_accel_summary(window_start=100)]
    client.post("/api/v1/accelerometer/batch", json={"summaries": batch1})

    batch2 = [make_accel_summary(window_start=100), make_accel_summary(window_start=200)]
    resp = client.post("/api/v1/accelerometer/batch", json={"summaries": batch2})
    data = resp.json()
    assert data["accepted"] == 1
    assert data["duplicates"] == 1


def test_accelerometer_empty_batch():
    resp = client.post("/api/v1/accelerometer/batch", json={"summaries": []})
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] == 0
    assert data["total_received"] == 0


def test_health_includes_accelerometer_count():
    # Start fresh — health should show 0 for both
    health = client.get("/api/v1/health").json()
    assert health["intervals_count"] == 0
    assert health["accelerometer_summaries_count"] == 0

    # Add some of each
    client.post(
        "/api/v1/intervals/batch",
        json={"intervals": [make_interval(ts=100)]},
    )
    client.post(
        "/api/v1/accelerometer/batch",
        json={"summaries": [make_accel_summary(window_start=100)]},
    )

    health = client.get("/api/v1/health").json()
    assert health["intervals_count"] == 1
    assert health["accelerometer_summaries_count"] == 1


def test_accelerometer_environment_isolation():
    prod_summary = [make_accel_summary(window_start=100)]
    test_summary = [make_accel_summary(window_start=200)]

    client.post("/api/v1/accelerometer/batch", json={"summaries": prod_summary})
    client.post(
        "/api/v1/accelerometer/batch",
        json={"summaries": test_summary},
        headers={"X-Environment": "test"},
    )

    prod_health = client.get("/api/v1/health").json()
    test_health = client.get(
        "/api/v1/health", headers={"X-Environment": "test"}
    ).json()

    assert prod_health["accelerometer_summaries_count"] == 1
    assert test_health["accelerometer_summaries_count"] == 1


def test_health_unknown_environment_rejected():
    resp = client.get("/api/v1/health", headers={"X-Environment": "staging"})
    assert resp.status_code == 400
    assert "staging" in resp.json()["detail"]


def test_intervals_batch_unknown_environment_rejected():
    resp = client.post(
        "/api/v1/intervals/batch",
        json={"intervals": [make_interval(ts=100)]},
        headers={"X-Environment": "staging"},
    )
    assert resp.status_code == 400
    assert "staging" in resp.json()["detail"]


def test_accelerometer_batch_unknown_environment_rejected():
    resp = client.post(
        "/api/v1/accelerometer/batch",
        json={"summaries": [make_accel_summary(window_start=100)]},
        headers={"X-Environment": "staging"},
    )
    assert resp.status_code == 400
    assert "staging" in resp.json()["detail"]


def test_missing_environment_defaults_to_production():
    # No X-Environment header — data must land in the production DB.
    client.post(
        "/api/v1/intervals/batch",
        json={"intervals": [make_interval(ts=100)]},
    )

    from database import get_db
    prod_conn = get_db("production")
    prod_count = prod_conn.execute("SELECT COUNT(*) FROM intervals").fetchone()[0]
    prod_conn.close()

    test_conn = get_db("test")
    test_count = test_conn.execute("SELECT COUNT(*) FROM intervals").fetchone()[0]
    test_conn.close()

    assert prod_count == 1
    assert test_count == 0


def test_intervals_batch_missing_required_field_rejected():
    interval = make_interval(ts=100)
    del interval["heart_rate_bpm"]
    resp = client.post("/api/v1/intervals/batch", json={"intervals": [interval]})
    assert resp.status_code == 422


def test_accelerometer_batch_missing_required_field_rejected():
    summary = make_accel_summary(window_start=100)
    del summary["magnitude_mean"]
    resp = client.post("/api/v1/accelerometer/batch", json={"summaries": [summary]})
    assert resp.status_code == 422


def test_intervals_within_batch_duplicate_pk():
    # Two records with identical device_id + timestamp_device in ONE request.
    dup = [make_interval(ts=100), make_interval(ts=100)]
    resp = client.post("/api/v1/intervals/batch", json={"intervals": dup})
    assert resp.status_code == 200
    data = resp.json()
    assert data["total_received"] == 2
    assert data["accepted"] == 1
    assert data["duplicates"] == 1

    health = client.get("/api/v1/health").json()
    assert health["intervals_count"] == 1


def test_accelerometer_within_batch_duplicate_pk():
    # Two records with identical device_id + window_start in ONE request.
    dup = [make_accel_summary(window_start=100), make_accel_summary(window_start=100)]
    resp = client.post("/api/v1/accelerometer/batch", json={"summaries": dup})
    assert resp.status_code == 200
    data = resp.json()
    assert data["total_received"] == 2
    assert data["accepted"] == 1
    assert data["duplicates"] == 1

    health = client.get("/api/v1/health").json()
    assert health["accelerometer_summaries_count"] == 1


def test_accelerometer_batch_without_session_id():
    summary = make_accel_summary(window_start=100)
    del summary["session_id"]
    resp = client.post("/api/v1/accelerometer/batch", json={"summaries": [summary]})
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] == 1
    assert data["total_received"] == 1

    from database import get_db
    conn = get_db("production")
    row = conn.execute(
        "SELECT session_id FROM accelerometer_summaries WHERE window_start = 100"
    ).fetchone()
    conn.close()
    assert row[0] is None


def make_diagnostic_entry(timestamp_ms=1000, tag="BleScanner", message="scan started"):
    return {
        "timestamp_ms": timestamp_ms,
        "tag": tag,
        "message": message,
    }


def test_diagnostics_upload_happy_path():
    entries = [make_diagnostic_entry(timestamp_ms=i) for i in range(3)]
    resp = client.post(
        "/api/v1/diagnostics/upload",
        json={"device_info": "Pixel 8 / Android 15", "entries": entries},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["stored"] == 3

    file_path = config.DIAG_DIR / data["file"]
    assert file_path.exists()
    # device_info header line + one line per entry.
    assert len(file_path.read_text().splitlines()) == 4

    file_path.unlink()


def test_diagnostics_upload_env_in_filename():
    resp = client.post(
        "/api/v1/diagnostics/upload",
        json={"device_info": None, "entries": [make_diagnostic_entry()]},
        headers={"X-Environment": "test"},
    )
    assert resp.status_code == 200
    file_name = resp.json()["file"]
    assert file_name.startswith("diag_test_")

    (config.DIAG_DIR / file_name).unlink()


def test_diagnostics_upload_empty_entries():
    resp = client.post(
        "/api/v1/diagnostics/upload",
        json={"entries": []},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["stored"] == 0

    file_path = config.DIAG_DIR / data["file"]
    assert file_path.exists()

    file_path.unlink()


def test_diagnostics_upload_filenames_unique():
    # Two consecutive uploads must not collide even within the same millisecond.
    resp1 = client.post(
        "/api/v1/diagnostics/upload",
        json={"entries": [make_diagnostic_entry()]},
    )
    resp2 = client.post(
        "/api/v1/diagnostics/upload",
        json={"entries": [make_diagnostic_entry()]},
    )
    assert resp1.status_code == 200
    assert resp2.status_code == 200

    file1 = resp1.json()["file"]
    file2 = resp2.json()["file"]
    assert file1 != file2

    path1 = config.DIAG_DIR / file1
    path2 = config.DIAG_DIR / file2
    assert path1.exists()
    assert path2.exists()

    path1.unlink()
    path2.unlink()


def test_diagnostics_upload_unknown_environment_rejected():
    resp = client.post(
        "/api/v1/diagnostics/upload",
        json={"entries": [make_diagnostic_entry()]},
        headers={"X-Environment": "staging"},
    )
    assert resp.status_code == 400
    assert "staging" in resp.json()["detail"]


@pytest.fixture(scope="session", autouse=True)
def cleanup():
    yield
    shutil.rmtree(_test_dir, ignore_errors=True)


# --- Golden payload contract tests ---
# The SAME files are asserted byte-for-byte against the Android client's
# serializer (core/network GoldenPayloadTest); posting them here proves the
# server accepts exactly what the app emits.

GOLDEN_DIR = __import__("pathlib").Path(__file__).resolve().parent.parent / "testdata" / "golden"


def load_golden(name):
    import json

    with open(GOLDEN_DIR / name) as f:
        return json.load(f)


def test_golden_interval_batch_accepted_and_stored():
    payload = load_golden("interval_batch.json")
    resp = client.post(
        "/api/v1/intervals/batch",
        json=payload,
        headers={"X-Environment": "test"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] == 3
    assert data["duplicates"] == 0
    assert data["total_received"] == 3

    from database import get_db

    conn = get_db("test")
    try:
        rows = conn.execute(
            "SELECT rr_interval_ms, session_id, window_label FROM intervals "
            "WHERE device_id = 'GOLDEN:AA' ORDER BY timestamp_device"
        ).fetchall()
        assert len(rows) == 3
        assert rows[1][0] == 0  # zero-RR artifact preserved
        assert rows[2][1] is None  # null session_id preserved
        assert rows[2][2] == "w1"  # window_label preserved
    finally:
        conn.close()


def test_golden_accelerometer_batch_accepted_and_stored():
    payload = load_golden("accelerometer_batch.json")
    resp = client.post(
        "/api/v1/accelerometer/batch",
        json=payload,
        headers={"X-Environment": "test"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["accepted"] == 2
    assert data["duplicates"] == 0

    from database import get_db

    conn = get_db("test")
    try:
        rows = conn.execute(
            "SELECT sensor_type, session_id FROM accelerometer_summaries "
            "WHERE device_id = 'GOLDEN:PVS' ORDER BY window_start"
        ).fetchall()
        assert len(rows) == 2
        assert rows[0][0] == "polar_pvs"
        assert rows[1][1] is None
    finally:
        conn.close()


def test_golden_diagnostics_upload_accepted():
    payload = load_golden("diagnostics_upload.json")
    resp = client.post(
        "/api/v1/diagnostics/upload",
        json=payload,
        headers={"X-Environment": "test"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["stored"] == 2

    diag_file = config.DIAG_DIR / data["file"]
    assert diag_file.exists()
    with open(diag_file) as f:
        lines = f.readlines()
    assert len(lines) == 3  # device_info line + 2 entries
    diag_file.unlink()
