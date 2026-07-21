"""Tests for Pulse Bridge MCP tool helpers."""

import sqlite3

import pytest

from .config import MCPConfig
from .database import DatabaseManager
from .tools import (
    get_aligned_timeseries,
    get_latest_session_report,
    get_session_report,
    get_vo2_summary,
    list_sessions,
)


def _init_db(path):
    conn = sqlite3.connect(path)
    conn.execute(
        """
        CREATE TABLE intervals (
            device_id TEXT NOT NULL,
            timestamp_device INTEGER NOT NULL,
            timestamp_phone INTEGER NOT NULL,
            heart_rate_bpm INTEGER NOT NULL,
            rr_interval_ms INTEGER NOT NULL,
            rr_sequence_index INTEGER NOT NULL,
            is_gap INTEGER NOT NULL DEFAULT 0,
            window_label TEXT,
            sensor_type TEXT NOT NULL,
            session_id TEXT,
            synced_at INTEGER NOT NULL,
            PRIMARY KEY (device_id, timestamp_device)
        )
        """
    )
    conn.commit()
    conn.close()


def _insert_session(path, session_id="session-1", start_ms=1_000_000):
    conn = sqlite3.connect(path)
    rows = []
    ts = start_ms
    for i in range(420):
        hr = 100 if i < 120 else 165 if i < 300 else 105
        rr = int(60_000 / hr)
        ts += rr
        rows.append((
            "AA:BB",
            ts,
            ts + 5,
            hr,
            rr,
            i,
            0,
            None,
            "garmin_hrm",
            session_id,
            ts + 10,
        ))
    conn.executemany(
        """
        INSERT INTO intervals (
            device_id, timestamp_device, timestamp_phone, heart_rate_bpm,
            rr_interval_ms, rr_sequence_index, is_gap, window_label,
            sensor_type, session_id, synced_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        rows,
    )
    conn.commit()
    conn.close()
    return rows


def _insert_vo2_session(path, session_id="vo2-session", start_ms=2_000_000):
    conn = sqlite3.connect(path)
    rows = []
    ts = start_ms
    sequence = 0

    def add_block(hr, seconds):
        nonlocal ts, sequence
        count = int(seconds * hr / 60)
        rr = int(60_000 / hr)
        for _ in range(count):
            ts += rr
            rows.append((
                "AA:BB",
                ts,
                ts + 5,
                hr,
                rr,
                sequence,
                0,
                None,
                "garmin_hrm",
                session_id,
                ts + 10,
            ))
            sequence += 1

    add_block(100, 20)
    for rep in range(3):
        add_block(170, 60)
        if rep < 2:
            add_block(110, 45)

    conn.executemany(
        """
        INSERT INTO intervals (
            device_id, timestamp_device, timestamp_phone, heart_rate_bpm,
            rr_interval_ms, rr_sequence_index, is_gap, window_label,
            sensor_type, session_id, synced_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        rows,
    )
    conn.commit()
    conn.close()
    return rows


@pytest.fixture
def db(tmp_path):
    path = tmp_path / "pulse_bridge_prod.db"
    _init_db(path)
    _insert_session(path)
    _insert_vo2_session(path)
    return DatabaseManager(MCPConfig.from_db_path(path))


def test_list_sessions(db):
    sessions = list_sessions(db)
    assert {session["session_id"] for session in sessions} == {"session-1", "vo2-session"}
    baseline = next(session for session in sessions if session["session_id"] == "session-1")
    assert baseline["beats"] == 420


def test_get_session_report_supports_crop(db):
    full = get_session_report(db, "session-1", hrmax=188)
    crop_start = full["raw_capture_window"]["start_ms"] + 20_000
    cropped = get_session_report(db, "session-1", start_ms=crop_start, hrmax=188)

    assert cropped["analysis_window"]["source"] == "manual"
    assert cropped["analysis_window"]["trimmed_before_s"] > 0
    assert cropped["raw_capture_window"]["beats"] == 420
    assert cropped["analysis_window"]["start_ms"] >= crop_start
    assert cropped["quality"]["hr_usable"] is True


def test_get_latest_session_report(db):
    report = get_latest_session_report(db)
    assert report["session_id"] == "vo2-session"
    assert "quality" in report


def test_get_aligned_timeseries(db):
    series = get_aligned_timeseries(db, "session-1", resolution_s=10)
    assert series["session_id"] == "session-1"
    assert series["resolution_s"] == 10
    assert series["rows"]
    assert {"hr_mean", "hr_max", "rr_coverage", "artifact_frac", "gap"} <= set(series["rows"][0])


def test_get_vo2_summary_matches_intent(db):
    sessions = list_sessions(db)
    vo2 = next(s for s in sessions if s["session_id"] == "vo2-session")
    start = vo2["start_ms"] + 20_000
    result = get_vo2_summary(
        db,
        "vo2-session",
        start_ms=start,
        hrmax=188,
        intent={
            "rounds": 3,
            "work_duration_s": 60,
            "rest_duration_s": 45,
            "target_hr_min": 169,
            "target_hr_max": 188,
            "modality": "bike",
        },
    )

    assert result["vo2"]["expected_structure_available"] is True
    assert len(result["vo2"]["work_bouts"]) == 3
    assert result["vo2"]["work_bouts"][0]["peak_hr"] == 170
    assert result["vo2"]["work_bouts"][0]["seconds_at_or_above_target_hr_min"] > 0
    assert result["timeseries"]["rows"]


def test_get_vo2_summary_without_intent_returns_timeseries_fallback(db):
    result = get_vo2_summary(db, "vo2-session")

    assert result["vo2"]["expected_structure_available"] is False
    assert result["vo2"]["flags"]["intent_missing"] is True
    assert result["vo2"]["work_bouts"] == []
    assert result["timeseries"]["rows"]


def test_get_vo2_summary_rejects_bad_intent(db):
    with pytest.raises(ValueError, match="target_hr_min"):
        get_vo2_summary(
            db,
            "vo2-session",
            intent={
                "rounds": 3,
                "work_duration_s": 60,
                "rest_duration_s": 45,
                "target_hr_min": 180,
                "target_hr_max": 170,
            },
        )
