"""Validate E01 result files without treating unexecuted cases as passes."""
import json
import sys
from pathlib import Path

ALLOWED = {"PASS", "FAIL", "BLOCKED", "INVALID", "INSUFFICIENT_SAMPLE"}
REQUIRED = {"caseId", "status", "commit", "environment", "startedAt", "finishedAt", "expected", "actual", "artifacts"}

def main(root: str) -> int:
    files = list(Path(root).rglob("result.json"))
    errors = []
    for file in files:
        try:
            value = json.loads(file.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"{file}: invalid JSON: {exc}")
            continue
        missing = REQUIRED - value.keys()
        if missing:
            errors.append(f"{file}: missing {sorted(missing)}")
        if value.get("status") not in ALLOWED:
            errors.append(f"{file}: invalid status {value.get('status')!r}")
        if value.get("status") == "PASS" and not value.get("artifacts"):
            errors.append(f"{file}: PASS requires artifacts")
    if errors:
        print("\n".join(errors))
        return 1
    print(f"validated {len(files)} result files")
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1] if len(sys.argv) > 1 else "reports/acceptance"))
