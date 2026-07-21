"""Read-only SQLite access for Pulse Bridge MCP tools."""

import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import MCPConfig


@dataclass
class SessionSummary:
    session_id: str
    device_id: str
    start_ms: int
    end_ms: int
    beats: int

    @property
    def duration_s(self) -> float:
        return round((self.end_ms - self.start_ms) / 1000.0, 1)


class SQLiteConnection:
    """Read-only SQLite connection context manager."""

    def __init__(self, db_path: Path):
        self.db_path = db_path
        self.conn: sqlite3.Connection | None = None

    def __enter__(self) -> sqlite3.Connection:
        self.conn = sqlite3.connect(f"file:{self.db_path}?mode=ro", uri=True)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA busy_timeout = 5000")
        return self.conn

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        if self.conn:
            self.conn.close()


class DatabaseManager:
    """Narrow query surface used by the MCP tools."""

    def __init__(self, config: MCPConfig):
        self.config = config

    def get_connection(self) -> SQLiteConnection:
        return SQLiteConnection(self.config.db_path)

    def list_sessions(
        self,
        start_ms: int | None = None,
        end_ms: int | None = None,
        limit: int | None = None,
    ) -> list[SessionSummary]:
        where = ["session_id IS NOT NULL"]
        params: list[Any] = []
        if start_ms is not None:
            where.append("timestamp_device >= ?")
            params.append(start_ms)
        if end_ms is not None:
            where.append("timestamp_device <= ?")
            params.append(end_ms)
        effective_limit = min(limit or self.config.max_rows, self.config.max_rows)
        params.append(effective_limit)

        with self.get_connection() as conn:
            rows = conn.execute(
                f"""
                SELECT
                    session_id,
                    MIN(device_id) AS device_id,
                    MIN(timestamp_device) AS start_ms,
                    MAX(timestamp_device) AS end_ms,
                    COUNT(*) AS beats
                FROM intervals
                WHERE {" AND ".join(where)}
                GROUP BY session_id
                ORDER BY start_ms DESC
                LIMIT ?
                """,
                params,
            ).fetchall()
        return [
            SessionSummary(
                session_id=row["session_id"],
                device_id=row["device_id"],
                start_ms=int(row["start_ms"]),
                end_ms=int(row["end_ms"]),
                beats=int(row["beats"]),
            )
            for row in rows
        ]

    def load_session_rows(self, session_id: str) -> list[sqlite3.Row]:
        with self.get_connection() as conn:
            return conn.execute(
                """
                SELECT
                    timestamp_device,
                    rr_interval_ms,
                    heart_rate_bpm,
                    is_gap,
                    sensor_type,
                    device_id
                FROM intervals
                WHERE session_id = ?
                ORDER BY timestamp_device
                """,
                (session_id,),
            ).fetchall()
