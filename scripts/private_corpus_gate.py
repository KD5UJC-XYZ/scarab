"""Explicit availability guard for optional, separately supplied research corpora."""
from pathlib import Path

def require_private_corpus():
    root = Path(__file__).resolve().parents[1]
    required = ["fixtures/chain-fetch", "fixtures/post-byron", "fixtures/body-commitment", "fixtures/block-evidence", "core/src/test/resources/historical-index", "core/src/test/resources/post-byron", "core/src/test/resources/body-commitment", "core/src/test/resources/block-evidence"]
    missing = [path for path in required if not (root / path).is_dir() or not any(f.is_file() for f in (root / path).rglob("*"))]
    if missing:
        raise SystemExit("Private corpus unavailable; no private gate ran. Missing: " + ", ".join(missing))
