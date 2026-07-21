"""Tool implementations for Pulse Bridge MCP.

These functions are deliberately independent from FastMCP decorators so they
can be unit-tested without launching an MCP runtime.
"""

from __future__ import annotations

import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .database import DatabaseManager, SessionSummary

SERVER_DIR = Path(__file__).resolve().parent.parent.parent / "server"
if str(SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(SERVER_DIR))

from analysis.db import _normalize_ts  # noqa: E402
from analysis.hr import time_weighted_mean_hr  # noqa: E402
from analysis.intent import parse_intent  # noqa: E402
from analysis.pipeline import analyze  # noqa: E402
from analysis.quality import Beat, classify, rr_coverage  # noqa: E402
from analysis.segment import detect_bouts  # noqa: E402
from analysis.vo2 import summarize_vo2  # noqa: E402


def _parse_ms(value: int | str | None) -> int | None:
    if value is None:
        return None
    if isinstance(value, int):
        return value
    text = value.strip()
    if not text:
        return None
    if text.isdigit():
        return int(text)
    normalized = text.replace("Z", "+00:00")
    dt = datetime.fromisoformat(normalized)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def _session_dict(session: SessionSummary) -> dict[str, Any]:
    return {
        "session_id": session.session_id,
        "device_id": session.device_id,
        "start_ms": session.start_ms,
        "end_ms": session.end_ms,
        "duration_s": session.duration_s,
        "beats": session.beats,
    }


def _rows_to_beats(rows) -> list[Beat]:
    beats = [
        Beat(
            ts_ms=_normalize_ts(
                int(row["timestamp_device"]),
                int(row["rr_interval_ms"]),
                row["sensor_type"],
            ),
            rr_ms=int(row["rr_interval_ms"]),
            hr_bpm=int(row["heart_rate_bpm"]),
            is_gap=bool(row["is_gap"]),
        )
        for row in rows
    ]
    beats.sort(key=lambda beat: beat.ts_ms)
    return beats


def crop_beats(
    beats: list[Beat],
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
) -> list[Beat]:
    crop_start = _parse_ms(start_ms)
    crop_end = _parse_ms(end_ms)
    if crop_start is not None and crop_end is not None and crop_start > crop_end:
        raise ValueError("start_ms/start_time must be <= end_ms/end_time")
    return [
        beat for beat in beats
        if (crop_start is None or beat.ts_ms >= crop_start)
        and (crop_end is None or beat.ts_ms <= crop_end)
    ]


def _window_dict(
    beats: list[Beat],
    source: str,
    raw_start_ms: int,
    raw_end_ms: int,
) -> dict[str, Any]:
    start_ms = beats[0].ts_ms
    end_ms = beats[-1].ts_ms
    return {
        "source": source,
        "start_ms": start_ms,
        "end_ms": end_ms,
        "duration_s": round((end_ms - start_ms) / 1000.0, 1),
        "trimmed_before_s": round(max(0, start_ms - raw_start_ms) / 1000.0, 1),
        "trimmed_after_s": round(max(0, raw_end_ms - end_ms) / 1000.0, 1),
    }


def list_sessions(
    db: DatabaseManager,
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
    limit: int | None = None,
) -> list[dict[str, Any]]:
    """Return recent captured sessions."""
    sessions = db.list_sessions(
        start_ms=_parse_ms(start_ms),
        end_ms=_parse_ms(end_ms),
        limit=limit,
    )
    return [_session_dict(session) for session in sessions]


def get_session_report(
    db: DatabaseManager,
    session_id: str,
    hrmax: int | None = None,
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
) -> dict[str, Any]:
    """Analyze one session, optionally cropped to an explicit time window."""
    rows = db.load_session_rows(session_id)
    if not rows:
        raise ValueError(f"No data for session {session_id}")

    raw_beats = _rows_to_beats(rows)
    beats = crop_beats(raw_beats, start_ms=start_ms, end_ms=end_ms)
    if not beats:
        raise ValueError("Crop window contains no beats")

    report = analyze(beats, hrmax=hrmax)
    raw_start_ms = raw_beats[0].ts_ms
    raw_end_ms = raw_beats[-1].ts_ms
    device_ids = sorted({row["device_id"] for row in rows if row["device_id"]})
    window_source = "manual" if start_ms is not None or end_ms is not None else "full_capture"

    return {
        "session_id": session_id,
        "device": {
            "device_ids": device_ids,
            "sensor_types": sorted({row["sensor_type"] for row in rows if row["sensor_type"]}),
        },
        "raw_capture_window": {
            "start_ms": raw_start_ms,
            "end_ms": raw_end_ms,
            "duration_s": round((raw_end_ms - raw_start_ms) / 1000.0, 1),
            "beats": len(raw_beats),
        },
        "analysis_window": _window_dict(beats, window_source, raw_start_ms, raw_end_ms),
        "quality": {
            "rr_coverage": report["rr_coverage"],
            "gaps": report["gaps"],
            "artifact_frac_overall": report["artifact_frac_overall"],
            "trusted_dfa_windows": report["alpha1"]["windows_trusted"],
            "total_dfa_windows": report["alpha1"]["windows_total"],
            "hr_usable": report["hr"]["avg"] is not None,
            "rr_hrv_usable": report["rmssd_ms"] is not None,
        },
        "hr": report["hr"],
        "rmssd_ms": report["rmssd_ms"],
        "alpha1": report["alpha1"],
        "bouts": report["bouts"],
        "windows": report["windows"],
        "flags": {
            "analysis_window_uncertain": False,
            "rr_quality_insufficient": report["alpha1"]["windows_trusted"] == 0,
        },
    }


def get_latest_session_report(
    db: DatabaseManager,
    hrmax: int | None = None,
) -> dict[str, Any]:
    sessions = db.list_sessions(limit=1)
    if not sessions:
        raise ValueError("No sessions found")
    return get_session_report(db, sessions[0].session_id, hrmax=hrmax)


def get_aligned_timeseries(
    db: DatabaseManager,
    session_id: str,
    resolution_s: int = 5,
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
) -> dict[str, Any]:
    """Return compact HR/quality buckets for one session."""
    if resolution_s < 1:
        raise ValueError("resolution_s must be at least 1")
    rows = db.load_session_rows(session_id)
    if not rows:
        raise ValueError(f"No data for session {session_id}")
    raw_beats = _rows_to_beats(rows)
    beats = crop_beats(raw_beats, start_ms=start_ms, end_ms=end_ms)
    if not beats:
        raise ValueError("Crop window contains no beats")

    t0 = beats[0].ts_ms
    bucket_ms = resolution_s * 1000
    out_rows = []
    start = t0
    while start <= beats[-1].ts_ms:
        stop = start + bucket_ms
        bucket = [beat for beat in beats if start <= beat.ts_ms < stop]
        if bucket:
            flags = classify(bucket)
            valid_hr = [beat.hr_bpm for beat in bucket if beat.hr_bpm > 0]
            hr_weighted = time_weighted_mean_hr(bucket)
            out_rows.append({
                "timestamp_ms": int(start),
                "offset_s": round((start - t0) / 1000.0, 1),
                "duration_s": resolution_s,
                "hr_mean": None if hr_weighted is None else round(hr_weighted, 1),
                "hr_max": max(valid_hr) if valid_hr else None,
                "rr_coverage": round(rr_coverage(bucket), 3),
                "artifact_frac": round(flags.artifact_fraction, 3),
                "gap": bool(flags.gap.any()),
                "beats": len(bucket),
            })
        start = stop

    return {
        "session_id": session_id,
        "resolution_s": resolution_s,
        "analysis_window": {
            "start_ms": beats[0].ts_ms,
            "end_ms": beats[-1].ts_ms,
            "duration_s": round((beats[-1].ts_ms - beats[0].ts_ms) / 1000.0, 1),
        },
        "rows": out_rows,
    }


def get_vo2_summary(
    db: DatabaseManager,
    session_id: str,
    intent: dict[str, Any] | None = None,
    hrmax: int | None = None,
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
    resolution_s: int = 5,
) -> dict[str, Any]:
    """Return expected/detected VO2 interval summary plus fallback time series."""
    interval_intent = parse_intent(intent)
    rows = db.load_session_rows(session_id)
    if not rows:
        raise ValueError(f"No data for session {session_id}")
    raw_beats = _rows_to_beats(rows)
    beats = crop_beats(raw_beats, start_ms=start_ms, end_ms=end_ms)
    if not beats:
        raise ValueError("Crop window contains no beats")

    detected = detect_bouts(beats)
    base_report = get_session_report(
        db,
        session_id=session_id,
        hrmax=hrmax,
        start_ms=start_ms,
        end_ms=end_ms,
    )
    vo2 = summarize_vo2(beats, detected, interval_intent, hrmax=hrmax)
    timeseries = get_aligned_timeseries(
        db,
        session_id=session_id,
        resolution_s=resolution_s,
        start_ms=start_ms,
        end_ms=end_ms,
    )

    return {
        "session_id": session_id,
        "analysis_window": base_report["analysis_window"],
        "quality": base_report["quality"],
        "hr": base_report["hr"],
        "vo2": vo2,
        "timeseries": timeseries,
    }
