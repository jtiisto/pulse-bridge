import time

from fastapi import FastAPI, Header

from database import get_db, insert_intervals
from models import HealthResponse, IntervalBatch, SyncResponse

app = FastAPI(title="Wellness Sync Server", version="0.1.0")


def resolve_environment(x_environment: str | None) -> str:
    if x_environment and x_environment in ("production", "test"):
        return x_environment
    return "production"


@app.get("/api/v1/health")
def health(x_environment: str | None = Header(None)) -> HealthResponse:
    env = resolve_environment(x_environment)
    conn = get_db(env)
    try:
        cursor = conn.execute("SELECT COUNT(*) FROM intervals")
        count = cursor.fetchone()[0]
        return HealthResponse(
            status="ok",
            environment=env,
            intervals_count=count,
        )
    finally:
        conn.close()


@app.post("/api/v1/intervals/batch")
def batch_sync(
    batch: IntervalBatch,
    x_environment: str | None = Header(None),
) -> SyncResponse:
    env = resolve_environment(x_environment)
    conn = get_db(env)
    try:
        synced_at = int(time.time() * 1000)
        records = [
            {**interval.model_dump(), "synced_at": synced_at}
            for interval in batch.intervals
        ]
        inserted, duplicates = insert_intervals(conn, records)

        # Log the sync
        conn.execute(
            """
            INSERT INTO sync_log (synced_at, intervals_received, intervals_inserted, intervals_duplicate, client_info)
            VALUES (?, ?, ?, ?, ?)
            """,
            (synced_at, len(records), inserted, duplicates, f"env={env}"),
        )
        conn.commit()

        return SyncResponse(
            accepted=inserted,
            duplicates=duplicates,
            total_received=len(records),
        )
    finally:
        conn.close()
