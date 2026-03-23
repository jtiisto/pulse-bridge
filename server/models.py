from pydantic import BaseModel


class IntervalRecord(BaseModel):
    device_id: str
    timestamp_device: int
    timestamp_phone: int
    heart_rate_bpm: int
    rr_interval_ms: int
    rr_sequence_index: int
    is_gap: bool = False
    window_label: str | None = None
    sensor_type: str
    session_id: str | None = None


class IntervalBatch(BaseModel):
    intervals: list[IntervalRecord]


class SyncResponse(BaseModel):
    accepted: int
    duplicates: int
    total_received: int


class HealthResponse(BaseModel):
    status: str
    environment: str
    intervals_count: int
