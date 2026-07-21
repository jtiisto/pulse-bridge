"""Configuration for the Pulse Bridge MCP server."""

from dataclasses import dataclass
from pathlib import Path


_DEFAULT_DB_PATH = (
    Path(__file__).resolve().parent.parent.parent
    / "server"
    / "data"
    / "pulse_bridge_prod.db"
)


@dataclass
class MCPConfig:
    """Configuration for read-only Pulse Bridge analysis access."""

    db_path: Path
    max_rows: int = 1000
    max_rows_absolute: int = 5000
    transport: str = "stdio"
    host: str = "127.0.0.1"
    port: int = 8003

    @classmethod
    def from_db_path(cls, db_path: Path, max_rows: int = 1000) -> "MCPConfig":
        return cls(db_path=db_path, max_rows=max_rows)

    def validate(self) -> None:
        if not self.db_path.exists():
            raise ValueError(f"Database file not found: {self.db_path}")
        if not self.db_path.is_file():
            raise ValueError(f"Database path is not a file: {self.db_path}")
        if self.max_rows < 1:
            raise ValueError("max_rows must be at least 1")
        if self.max_rows > self.max_rows_absolute:
            raise ValueError(
                f"max_rows ({self.max_rows}) cannot exceed "
                f"max_rows_absolute ({self.max_rows_absolute})"
            )
        if self.transport not in ("stdio", "http", "sse"):
            raise ValueError(f"Invalid transport: {self.transport}")
        if self.port < 1 or self.port > 65535:
            raise ValueError(f"Invalid port: {self.port}")
