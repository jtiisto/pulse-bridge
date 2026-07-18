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


class AccelerometerSummaryRecord(BaseModel):
    device_id: str
    window_start: int
    magnitude_mean: float
    magnitude_std: float
    magnitude_max: float
    sample_count: int
    sensor_type: str
    session_id: str | None = None


class AccelerometerBatch(BaseModel):
    summaries: list[AccelerometerSummaryRecord]


class HealthResponse(BaseModel):
    status: str
    environment: str
    intervals_count: int
    accelerometer_summaries_count: int = 0


class DiagnosticEntry(BaseModel):
    timestamp_ms: int
    tag: str
    message: str


class DiagnosticUpload(BaseModel):
    device_info: str | None = None
    entries: list[DiagnosticEntry]


class DiagnosticUploadResponse(BaseModel):
    stored: int
    file: str
