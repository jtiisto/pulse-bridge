import sqlite3
from pathlib import Path

from config import DATA_DIR, DB_FILES, DEFAULT_ENVIRONMENT

SCHEMA = """
CREATE TABLE IF NOT EXISTS intervals (
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
);

CREATE INDEX IF NOT EXISTS idx_intervals_timestamp ON intervals(timestamp_device);
CREATE INDEX IF NOT EXISTS idx_intervals_window ON intervals(window_label);
CREATE INDEX IF NOT EXISTS idx_intervals_session ON intervals(session_id);

CREATE TABLE IF NOT EXISTS device_sessions (
    session_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    sensor_type TEXT NOT NULL,
    start_time INTEGER NOT NULL,
    end_time INTEGER,
    total_intervals INTEGER NOT NULL DEFAULT 0,
    average_hr INTEGER
);

CREATE TABLE IF NOT EXISTS sync_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    synced_at INTEGER NOT NULL,
    intervals_received INTEGER NOT NULL,
    intervals_inserted INTEGER NOT NULL,
    intervals_duplicate INTEGER NOT NULL,
    client_info TEXT
);
"""


def get_db(environment: str | None = None) -> sqlite3.Connection:
    env = environment or DEFAULT_ENVIRONMENT
    db_path = DB_FILES.get(env)
    if db_path is None:
        raise ValueError(f"Unknown environment: {env}")

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(SCHEMA)
    return conn


def insert_intervals(
    conn: sqlite3.Connection,
    intervals: list[dict],
) -> tuple[int, int]:
    """Insert intervals with INSERT OR IGNORE. Returns (inserted, duplicates)."""
    cursor = conn.cursor()
    inserted = 0
    for interval in intervals:
        cursor.execute(
            """
            INSERT OR IGNORE INTO intervals (
                device_id, timestamp_device, timestamp_phone,
                heart_rate_bpm, rr_interval_ms, rr_sequence_index,
                is_gap, window_label, sensor_type, session_id, synced_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                interval["device_id"],
                interval["timestamp_device"],
                interval["timestamp_phone"],
                interval["heart_rate_bpm"],
                interval["rr_interval_ms"],
                interval["rr_sequence_index"],
                1 if interval.get("is_gap", False) else 0,
                interval.get("window_label"),
                interval["sensor_type"],
                interval.get("session_id"),
                interval["synced_at"],
            ),
        )
        if cursor.rowcount > 0:
            inserted += 1

    conn.commit()
    duplicates = len(intervals) - inserted
    return inserted, duplicates
