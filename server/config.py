from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"

DB_FILES = {
    "production": DATA_DIR / "wellness_prod.db",
    "test": DATA_DIR / "wellness_test.db",
}

DEFAULT_ENVIRONMENT = "production"
