"""
Audit (and optionally clean) backend/recognition/face_data/embeddings.json.

Verified findings on the current file (2026-10-14):
  * 7 entries: 5 real ISE students + 2 orphans
      - "STU-100" / "John Doe"   -> no such user or student in the DB (demo entry)
      - ""        / "DR B I D Kumar" -> empty student id (the HOD, stored as .jpg)
  * all vectors are 128-d and finite; no duplicate vectors
  * the 5 valid entries match the 5 students flagged face_embedding='registered'

Usage
-----
    python scripts/audit_embeddings.py           # audit only (nothing changed)
    python scripts/audit_embeddings.py --clean   # remove orphan entries (backup first)
"""

import argparse
import json
import os
import shutil
import sqlite3
import sys
from datetime import datetime

BACKEND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FACE_DATA = os.path.join(BACKEND, "recognition", "face_data")
EMBEDDINGS = os.path.join(FACE_DATA, "embeddings.json")
DEFAULT_DB = os.path.join(BACKEND, "attendance.db")


def load_json(path):
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def audit(db_path, entries):
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    try:
        known = {r[0] for r in con.execute("select student_id from students")}
        flagged = {r[0] for r in con.execute(
            "select student_id from students where face_embedding is not null and face_embedding != ''")}
    finally:
        con.close()

    report = {"valid": [], "orphan": [], "malformed": [], "unflagged": []}
    for sid, rec in entries.items():
        emb = rec.get("embedding") if isinstance(rec, dict) else None
        if not isinstance(emb, list) or len(emb) != 128:
            report["malformed"].append((sid, "embedding is not a 128-d list"))
            continue
        if not sid or sid not in known:
            report["orphan"].append((sid, rec.get("name")))
            continue
        report["valid"].append((sid, rec.get("name")))
        if sid not in flagged:
            report["unflagged"].append((sid, rec.get("name")))

    missing = sorted(flagged - set(entries))
    return report, missing, flagged


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", default=DEFAULT_DB)
    ap.add_argument("--embeddings", default=EMBEDDINGS)
    ap.add_argument("--clean", action="store_true", help="delete orphan/malformed entries (backup first)")
    args = ap.parse_args(argv)

    entries = load_json(args.embeddings)
    report, missing, flagged = audit(args.db, entries)

    print("embeddings file:", args.embeddings)
    print("entries: %d | valid: %d | orphan: %d | malformed: %d"
          % (len(entries), len(report["valid"]), len(report["orphan"]), len(report["malformed"])))
    for sid, name in report["valid"]:
        print("  VALID    %-14s %s" % (sid, name))
    for sid, name in report["orphan"]:
        print("  ORPHAN   %-14r %s" % (sid, name))
    for sid, why in report["malformed"]:
        print("  BAD      %-14r %s" % (sid, why))
    for sid, name in report["unflagged"]:
        print("  NOTE     %-14s student exists but has no face_embedding flag" % sid)
    if missing:
        print("  MISSING  students flagged face_embedding but absent from embeddings.json: %s" % missing)

    drop = [sid for sid, _ in report["orphan"]] + [sid for sid, _ in report["malformed"]]
    if not drop:
        print("\nNothing to clean.")
        return 0
    if not args.clean:
        print("\nDRY RUN - run with --clean to remove %d entr(y/ies)." % len(drop))
        return 0

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = "%s.bak-%s" % (args.embeddings, ts)
    shutil.copy2(args.embeddings, backup)
    for sid in drop:
        entries.pop(sid, None)
    with open(args.embeddings, "w", encoding="utf-8") as fh:
        json.dump(entries, fh)
    print("\nRemoved %d entr(y/ies). Backup: %s" % (len(drop), backup))

    # orphan crop files (e.g. the literal ".jpg" belonging to the empty id)
    removed_files = []
    for fname in sorted(os.listdir(FACE_DATA)):
        if not fname.lower().endswith(".jpg"):
            continue
        sid = fname[:-4]
        if not sid or sid not in entries:
            src = os.path.join(FACE_DATA, fname)
            dst = os.path.join(FACE_DATA, "orphan-" + ts + "-" + fname)
            shutil.move(src, dst)
            removed_files.append(fname)
    if removed_files:
        print("Moved orphan crop(s) aside: %s" % removed_files)
    return 0


if __name__ == "__main__":
    sys.exit(main())
