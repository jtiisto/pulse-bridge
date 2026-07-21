"""Pulse Bridge MCP server implementation."""

import os
from pathlib import Path
from typing import Any

try:
    from fastmcp import FastMCP
except ImportError:
    FastMCP = None

from .config import MCPConfig, _DEFAULT_DB_PATH
from .database import DatabaseManager
from . import tools


def _default_config() -> MCPConfig:
    db_path = Path(os.environ.get("PULSE_BRIDGE_DB_PATH", str(_DEFAULT_DB_PATH)))
    return MCPConfig.from_db_path(db_path)


def create_mcp_server(config: MCPConfig | None = None):
    """Create and configure the read-only Pulse Bridge MCP server."""
    if FastMCP is None:
        raise ImportError(
            "FastMCP is required for MCP server functionality. "
            "Install with: pip install fastmcp"
        )
    if config is None:
        config = _default_config()

    config.validate()
    db = DatabaseManager(config)
    mcp = FastMCP("Pulse Bridge Analysis")

    @mcp.tool()
    def list_sessions(
        start_ms: int | str | None = None,
        end_ms: int | str | None = None,
        limit: int | None = None,
    ) -> list[dict[str, Any]]:
        """WHEN TO USE: Find captured Pulse Bridge sessions available for analysis.

        Optional start/end bounds accept epoch milliseconds or ISO-8601 strings.
        """
        return tools.list_sessions(db, start_ms=start_ms, end_ms=end_ms, limit=limit)

    @mcp.tool()
    def get_session_report(
        session_id: str,
        hrmax: int | None = None,
        start_ms: int | str | None = None,
        end_ms: int | str | None = None,
    ) -> dict[str, Any]:
        """WHEN TO USE: Analyze one Pulse Bridge capture session.

        Optional start/end bounds crop the capture before metrics are calculated.
        """
        return tools.get_session_report(
            db,
            session_id=session_id,
            hrmax=hrmax,
            start_ms=start_ms,
            end_ms=end_ms,
        )

    @mcp.tool()
    def get_latest_session_report(hrmax: int | None = None) -> dict[str, Any]:
        """WHEN TO USE: Analyze the most recent Pulse Bridge capture session."""
        return tools.get_latest_session_report(db, hrmax=hrmax)

    @mcp.tool()
    def get_aligned_timeseries(
        session_id: str,
        resolution_s: int = 5,
        start_ms: int | str | None = None,
        end_ms: int | str | None = None,
    ) -> dict[str, Any]:
        """WHEN TO USE: Get compact time buckets when bout matching is uncertain."""
        return tools.get_aligned_timeseries(
            db,
            session_id=session_id,
            resolution_s=resolution_s,
            start_ms=start_ms,
            end_ms=end_ms,
        )

    @mcp.tool()
    def get_vo2_summary(
        session_id: str,
        intent: dict[str, Any] | None = None,
        hrmax: int | None = None,
        start_ms: int | str | None = None,
        end_ms: int | str | None = None,
        resolution_s: int = 5,
    ) -> dict[str, Any]:
        """WHEN TO USE: Compare a VO2/HIIT interval plan to captured HR data.

        Intent should include rounds, work_duration_s, rest_duration_s, and
        optional target_hr_min/target_hr_max. Returns uncertainty flags and a
        compact fallback time series.
        """
        return tools.get_vo2_summary(
            db,
            session_id=session_id,
            intent=intent,
            hrmax=hrmax,
            start_ms=start_ms,
            end_ms=end_ms,
            resolution_s=resolution_s,
        )

    return mcp


def main() -> None:
    mcp = create_mcp_server()
    mcp.run()


if __name__ == "__main__":
    main()
