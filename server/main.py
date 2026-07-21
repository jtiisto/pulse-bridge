import json
import time
import uuid

from fastapi import FastAPI, Header, HTTPException

from config import DIAG_DIR
from database import get_db, insert_accelerometer_summaries, insert_intervals
from models import (
    AccelerometerBatch,
    DiagnosticUpload,
    DiagnosticUploadResponse,
    HealthResponse,
    IntervalBatch,
    SyncResponse,
)

app = FastAPI(title="Pulse Bridge Server", version="0.1.0")

VALID_ENVIRONMENTS = ("production", "test")


def resolve_environment(x_environment: str | None) -> str:
    # Missing header defaults to production for backward compatibility.
    if x_environment is None:
        return "production"
    if x_environment not in VALID_ENVIRONMENTS:
        raise HTTPException(
            status_code=400,
            detail=(
                f"Invalid X-Environment header: {x_environment!r}. "
                f"Must be one of {list(VALID_ENVIRONMENTS)}."
            ),
        )
    return x_environment


@app.get("/api/v1/health")
def health(x_environment: str | None = Header(None)) -> HealthResponse:
    env = resolve_environment(x_environment)
    conn = get_db(env)
    try:
        intervals_count = conn.execute(
            "SELECT COUNT(*) FROM intervals"
        ).fetchone()[0]
        accel_count = conn.execute(
            "SELECT COUNT(*) FROM accelerometer_summaries"
        ).fetchone()[0]
        return HealthResponse(
            status="ok",
            environment=env,
            intervals_count=intervals_count,
            accelerometer_summaries_count=accel_count,
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


@app.post("/api/v1/accelerometer/batch")
def accelerometer_batch_sync(
    batch: AccelerometerBatch,
    x_environment: str | None = Header(None),
) -> SyncResponse:
    env = resolve_environment(x_environment)
    conn = get_db(env)
    try:
        synced_at = int(time.time() * 1000)
        records = [
            {**summary.model_dump(), "synced_at": synced_at}
            for summary in batch.summaries
        ]
        inserted, duplicates = insert_accelerometer_summaries(conn, records)

        return SyncResponse(
            accepted=inserted,
            duplicates=duplicates,
            total_received=len(records),
        )
    finally:
        conn.close()


@app.post("/api/v1/diagnostics/upload")
def diagnostics_upload(
    upload: DiagnosticUpload,
    x_environment: str | None = Header(None),
) -> DiagnosticUploadResponse:
    env = resolve_environment(x_environment)
    DIAG_DIR.mkdir(parents=True, exist_ok=True)
    file_name = f"diag_{env}_{int(time.time() * 1000)}_{uuid.uuid4().hex[:8]}.jsonl"
    file_path = DIAG_DIR / file_name

    with file_path.open("w") as f:
        if upload.device_info is not None:
            f.write(json.dumps({"device_info": upload.device_info}) + "\n")
        for entry in upload.entries:
            f.write(entry.model_dump_json() + "\n")

    return DiagnosticUploadResponse(stored=len(upload.entries), file=file_name)
