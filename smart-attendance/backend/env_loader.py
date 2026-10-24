"""Load operator-supplied secrets from a local .env file.

Stdlib only - no python-dotenv dependency. Values already present in the real
process environment always win, so a real deployment can still export them
normally. The file is optional: without it the provider simply reports itself
as unconfigured, which is the honest default.
"""
import os

_LOADED = False


def load_env(path: str = None) -> dict:
    """Read KEY=VALUE lines from a .env file into os.environ (non-destructive)."""
    global _LOADED
    if path is None:
        base = os.path.dirname(os.path.abspath(__file__))
        candidates = [os.path.join(base, ".env"),
                      os.path.join(os.path.dirname(base), ".env")]
    else:
        candidates = [path]

    found = {}
    for candidate in candidates:
        if not os.path.isfile(candidate):
            continue
        try:
            with open(candidate, "r", encoding="utf-8") as handle:
                for line in handle:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, _, value = line.partition("=")
                    key = key.strip()
                    value = value.strip().strip('"').strip("'")
                    if key:
                        found[key] = value
        except OSError:
            continue
        break

    for key, value in found.items():
        if not os.environ.get(key):
            os.environ[key] = value
    _LOADED = True
    return found


def ensure_loaded():
    if not _LOADED:
        load_env()
