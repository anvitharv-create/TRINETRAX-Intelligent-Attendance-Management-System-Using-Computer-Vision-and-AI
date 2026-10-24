"""
Reduce duplicated seed-generated students to a sane class size.

Verified problem
----------------
seed_demo_data() used to append 30-40 new students per section on every server
restart, so backend/attendance.db grew to 29,027 student rows while only 886
distinct names exist (some names appear 138 times).

Rules (agreed with the project owner)
-------------------------------------
* ISE students are NEVER touched.
* Every other class (branch x semester x section) is reduced to
  TARGET_PER_CLASS students when it currently holds more than MAX_PER_CLASS.
* Students that already have attendance records are kept first, so attendance
  history stays attached to real rows.
* Attendance rows of removed students are removed with them (no orphans).

Usage
-----
    python scripts/dedupe_students.py            # dry run, prints the plan
    python scripts/dedupe_students.py --apply    # actually modifies the DB

A timestamped backup of the DB is written before --apply.
"""

import argparse
import os
import shutil
import sqlite3
import sys
from datetime import datetime

DEFAULT_DB = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "attendance.db")
TARGET_PER_CLASS = 50
MAX_PER_CLASS = 60
PRESERVE_BRANCH = "ISE"
CHUNK = 500


def _chunks(seq, size=CHUNK):
    for i in range(0, len(seq), size):
        yield seq[i:i + size]


def load(db_path):
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    try:
        students = con.execute(
            "select id, user_id, branch, semester, section from students order by id"
        ).fetchall()
        with_att = {r[0] for r in con.execute(
            "select distinct student_id from attendance_records")}
        notif_users = {r[0] for r in con.execute(
            "select distinct user_id from notifications")}
        session_users = {r[0] for r in con.execute(
            "select distinct user_id from token_sessions")}
        attendance_total = con.execute("select count(*) from attendance_records").fetchone()[0]
        return students, with_att, notif_users, session_users, attendance_total
    finally:
        con.close()


def plan(db_path, target=TARGET_PER_CLASS, maximum=MAX_PER_CLASS):
    students, with_att, _, _, attendance_total = load(db_path)
    classes = {}
    for sid, uid, branch, semester, section in students:
        classes.setdefault((branch, semester, section), []).append((sid, uid))

    keep_ids, drop_ids = set(), set()
    per_class = []
    for (branch, semester, section), rows in sorted(classes.items()):
        n = len(rows)
        if branch == PRESERVE_BRANCH or n <= maximum:
            keep_ids.update(sid for sid, _ in rows)
            per_class.append({"branch": branch, "semester": semester, "section": section,
                              "before": n, "after": n,
                              "action": "keep (ISE)" if branch == PRESERVE_BRANCH else "keep"})
            continue
        ordered = sorted(rows, key=lambda r: (0 if r[0] in with_att else 1, r[0]))
        keep = ordered[:target]
        keep_ids.update(sid for sid, _ in keep)
        drop_ids.update(sid for sid, _ in ordered[target:])
        per_class.append({"branch": branch, "semester": semester, "section": section,
                          "before": n, "after": len(keep), "action": "reduce"})

    drop_user_ids = {uid for sid, uid, *_ in students if sid in drop_ids}
    attendance_to_delete = sum(1 for r in con_attendance(db_path) if r in drop_ids)
    stats = {
        "students_before": len(students),
        "students_to_delete": len(drop_ids),
        "users_to_delete": len(drop_user_ids),
        "attendance_to_delete": attendance_to_delete,
        "attendance_total": attendance_total,
        "ise_students_untouched": sum(1 for s in students if s[2] == PRESERVE_BRANCH),
    }
    return per_class, sorted(drop_ids), sorted(drop_user_ids), stats


def con_attendance(db_path):
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    try:
        return [r[0] for r in con.execute("select student_id from attendance_records")]
    finally:
        con.close()


def apply(db_path, drop_ids, drop_user_ids):
    backup = "%s.bak-%s" % (db_path, datetime.now().strftime("%Y%m%d-%H%M%S"))
    shutil.copy2(db_path, backup)
    con = sqlite3.connect(db_path)
    try:
        cur = con.cursor()
        deleted = {"students": 0, "users": 0, "attendance": 0, "notifications": 0, "sessions": 0}
        for part in _chunks(drop_ids):
            marks = ",".join("?" * len(part))
            cur.execute("delete from attendance_records where student_id in (%s)" % marks, tuple(part))
            deleted["attendance"] += cur.rowcount
            cur.execute("delete from students where id in (%s)" % marks, tuple(part))
            deleted["students"] += cur.rowcount
        for part in _chunks(drop_user_ids):
            marks = ",".join("?" * len(part))
            cur.execute("delete from notifications where user_id in (%s)" % marks, tuple(part))
            deleted["notifications"] += cur.rowcount
            cur.execute("delete from token_sessions where user_id in (%s)" % marks, tuple(part))
            deleted["sessions"] += cur.rowcount
            cur.execute("delete from users where id in (%s)" % marks, tuple(part))
            deleted["users"] += cur.rowcount
        con.commit()
        deleted["backup"] = backup
        return deleted
    finally:
        con.close()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", default=DEFAULT_DB)
    ap.add_argument("--apply", action="store_true", help="actually modify the DB (default is dry run)")
    ap.add_argument("--target", type=int, default=TARGET_PER_CLASS)
    ap.add_argument("--max", type=int, default=MAX_PER_CLASS, dest="maximum")
    args = ap.parse_args(argv)

    if not os.path.exists(args.db):
        print("DB not found:", args.db)
        return 1

    per_class, drop_ids, drop_user_ids, stats = plan(args.db, args.target, args.maximum)
    print("DB:", args.db)
    print("ISE students (untouched): %d" % stats["ise_students_untouched"])
    print("%-6s %-14s %-10s %7s %7s  %s" % ("branch", "semester", "section", "before", "after", "action"))
    for c in per_class:
        print("%-6s %-14s %-10s %7d %7d  %s" % (c["branch"], c["semester"], c["section"],
                                                 c["before"], c["after"], c["action"]))
    print("students before: %d | to delete: %d | users to delete: %d | attendance rows to delete: %d (of %d)"
          % (stats["students_before"], stats["students_to_delete"], stats["users_to_delete"],
             stats["attendance_to_delete"], stats["attendance_total"]))

    if not args.apply:
        print("\nDRY RUN - nothing changed. Re-run with --apply to execute.")
        return 0
    if not drop_ids:
        print("\nNothing to delete.")
        return 0
    result = apply(args.db, drop_ids, drop_user_ids)
    print("\nAPPLIED:", result)
    con = sqlite3.connect(args.db)
    print("students now: %d | users: %d | attendance: %d | ISE: %d"
          % (con.execute("select count(*) from students").fetchone()[0],
             con.execute("select count(*) from users").fetchone()[0],
             con.execute("select count(*) from attendance_records").fetchone()[0],
             con.execute("select count(*) from students where branch='ISE'").fetchone()[0]))
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
