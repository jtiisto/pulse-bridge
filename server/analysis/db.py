"""Load capture sessions from the per-environment SQLite database."""

import sqlite3
import sys
from pathlib import Path

# Reuse the server's DB path config
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from config import DB_FILES  # noqa: E402

from .quality import Beat  # noqa: E402


def _connect(environment):
    path = DB_FILES.get(environment)
    if path is None:
        raise ValueError(f"Unknown environment {environment!r}; expected one of {list(DB_FILES)}")
    if not Path(path).exists():
        raise FileNotFoundError(f"No database at {path}")
    return sqlite3.connect(path)


def list_sessions(environment="production", limit=20):
    """Recent sessions as (session_id, device_id, start_ms, end_ms, beats)."""
    conn = _connect(environment)
    try:
        rows = conn.execute(
            """
            SELECT session_id, device_id, MIN(timestamp_device), MAX(timestamp_device), COUNT(*)
            FROM intervals
            WHERE session_id IS NOT NULL
            GROUP BY session_id
            ORDER BY MIN(timestamp_device) DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
        return rows
    finally:
        conn.close()


# Polar PPI samples are timestamped at the START of the interval (parser adds
# the PPI after stamping), whereas Garmin RR rows are stamped at the END. Shift
# Polar timestamps forward by their own RR so both use the end-anchored
# convention the analysis assumes (otherwise coverage/omission/RMSSD boundaries
# are off by one interval).
POLAR_SENSOR_TYPE = "polar_pvs"


def _normalize_ts(ts_ms, rr_ms, sensor_type):
    if sensor_type == POLAR_SENSOR_TYPE and rr_ms > 0:
        return ts_ms + rr_ms
    return ts_ms


def load_beats(session_id=None, environment="production", start_ms=None, end_ms=None):
    """Ordered Beat list for a session id or an explicit time range."""
    conn = _connect(environment)
    try:
        if session_id is not None:
            where, params = "session_id = ?", [session_id]
        elif start_ms is not None and end_ms is not None:
            where, params = "timestamp_device BETWEEN ? AND ?", [start_ms, end_ms]
        else:
            raise ValueError("Provide session_id or (start_ms, end_ms)")
        rows = conn.execute(
            f"""
            SELECT timestamp_device, rr_interval_ms, heart_rate_bpm, is_gap, sensor_type
            FROM intervals
            WHERE {where}
            ORDER BY timestamp_device
            """,
            params,
        ).fetchall()
        beats = [
            Beat(
                ts_ms=_normalize_ts(r[0], r[1], r[4]),
                rr_ms=r[1],
                hr_bpm=r[2],
                is_gap=bool(r[3]),
            )
            for r in rows
        ]
        # A Polar shift can reorder rows relative to Garmin — keep sorted
        beats.sort(key=lambda b: b.ts_ms)
        return beats
    finally:
        conn.close()


def latest_session(environment="production"):
    sessions = list_sessions(environment, limit=1)
    return sessions[0][0] if sessions else None
