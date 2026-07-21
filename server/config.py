from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"

DIAG_DIR = DATA_DIR / "diagnostics"

DB_FILES = {
    "production": DATA_DIR / "pulse_bridge_prod.db",
    "test": DATA_DIR / "pulse_bridge_test.db",
}

DEFAULT_ENVIRONMENT = "production"
