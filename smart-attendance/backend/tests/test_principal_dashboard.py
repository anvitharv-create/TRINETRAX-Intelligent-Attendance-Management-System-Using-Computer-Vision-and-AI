"""
Principal (admin) college overview.

Every expectation is hand-checkable from the rows created here - no production
records are read or written, and the threshold comes from the project config.
"""
from datetime import date, datetime, timedelta

import pytest

import main
import student_report
from conftest import auth, create_user


def _student(db, sid, branch, semester="5th Semester", section="Section A"):
    user = main.User(username=sid, email="%s@test.local" % sid, full_name=sid,
                     role="student", branch=branch,
                     hashed_password=main.hash_password("x"))
    db.add(user)
    db.commit()
    stu = main.Student(student_id=sid, user_id=user.id, name=sid, branch=branch,
                       semester=semester, section=section)
    db.add(stu)
    db.commit()
    db.refresh(stu)
    return stu


def _teacher(db, tid, branch, role="teacher"):
    user = main.User(username=tid, email="%s@test.local" % tid, full_name=tid,
                     role=role, branch=branch,
                     hashed_password=main.hash_password("x"))
    db.add(user)
    db.commit()
    t = main.Teacher(user_id=user.id, name=tid, employee_id=tid, branch=branch)
    db.add(t)
    db.commit()
    return t


def _subject(db, code, branch, semester="5th Semester"):
    s = main.Subject(name=code, code=code, branch=branch, semester=semester)
    db.add(s)
    db.commit()
    return s


def _mark(db, student, subject, status, day, marked_by=1):
    db.add(main.AttendanceRecord(student_id=student.id, subject_id=subject.id,
                                 date=day, status=status, marked_by=marked_by))
    db.commit()


@pytest.fixture()
def college(db):
    """Two departments, hand-counted numbers, and records around today."""
    _, token = create_user(db, "principal", "admin", branch="ISE")

    today = datetime.now().date()
    yest = today - timedelta(days=1)

    s1 = _student(db, "P001", "ISE")          # 2/4 present = 50%  -> below threshold
    s2 = _student(db, "P002", "ISE")          # 4/4 present = 100% -> healthy
    s3 = _student(db, "P003", "CSE", section="Section B")

    math = _subject(db, "ISE5M01", "ISE")
    prog = _subject(db, "ISE5P02", "ISE")
    cse_sub = _subject(db, "CSE5C03", "CSE")

    for i in range(4):
        _mark(db, s1, math, "present" if i < 2 else "absent", yest, marked_by=1)
    for i in range(4):
        _mark(db, s2, prog, "present", yest, marked_by=1)
    _mark(db, s3, cse_sub, "present", yest, marked_by=1)
    # today: P001 absent, P002 present, P003 unknown (must NOT be an absence)
    _mark(db, s1, math, "absent", today, marked_by=1)
    _mark(db, s2, prog, "present", today, marked_by=1)
    _mark(db, s3, cse_sub, "unknown", today, marked_by=1)

    _teacher(db, "T001", "ISE")
    _teacher(db, "T002", "CSE")
    return {"token": token, "today": today, "yest": yest, "s1": s1, "s2": s2, "s3": s3}


def _overview(client, token, params=None):
    return client.get("/api/admin/overview", headers=auth(token), params=params or {})


def test_counts_entities_exactly_once(client, db, college):
    body = _overview(client, college["token"]).json()
    assert body["total_students"] == 3
    assert body["total_teachers"] == 2
    assert body["total_departments"] == 2


def test_today_present_and_absent_are_unique_students(client, db, college):
    body = _overview(client, college["token"]).json()
    assert body["present_today"] == 1        # P002
    assert body["absent_today"] == 1         # P001 - P003's "unknown" is not absent


def test_student_marked_present_and_absent_today_is_not_double_counted(client, db, college):
    s2 = college["s2"]
    math = db.query(main.Subject).filter(main.Subject.code == "ISE5M01").first()
    _mark(db, s2, math, "absent", college["today"], marked_by=1)
    body = _overview(client, college["token"]).json()
    assert body["present_today"] == 1        # still P002
    assert body["absent_today"] == 1         # and still only P001


def test_overall_and_department_percentages(client, db, college):
    body = _overview(client, college["token"]).json()
    # yesterday: 9 records / 7 present, today: 3 records / 1 present -> 8/12 = 66.7%
    assert body["overall_attendance_pct"] == 66.7
    stats = {b["branch"]: b for b in body["branch_stats"]}
    assert stats["ISE"]["records"] == 10
    assert stats["ISE"]["attendance_pct"] == round(7 / 10 * 100, 1)
    assert stats["CSE"]["records"] == 2
    assert stats["CSE"]["attendance_pct"] == 50.0


def test_date_filter_narrows_every_attendance_number(client, db, college):
    body = _overview(client, college["token"],
                     {"start_date": college["today"].isoformat(),
                      "end_date": college["today"].isoformat()}).json()
    assert body["records_in_window"] == 3
    assert body["overall_attendance_pct"] == round(1 / 3 * 100, 1)
    # head counts are not date-based and must not shrink
    assert body["total_students"] == 3
    records = {b["branch"]: b["records"] for b in body["branch_stats"]}
    assert records == {"ISE": 2, "CSE": 1}


def test_low_attendance_is_counted_in_full_not_capped(client, db, college):
    """The old query stopped at 20 rows, so the card under-reported."""
    body = _overview(client, college["token"]).json()
    assert body["low_attendance_count"] == len(body["low_attendance_students"])
    assert body["threshold_pct"] == student_report.MIN_ATTENDANCE_PCT
    # P001 = 2/5 = 40%, P003 = 1 present + 1 unknown = 50%
    assert [s["student_id"] for s in body["low_attendance_students"]] == ["P001", "P003"]


def test_attention_list_uses_the_configured_threshold(client, db, college):
    body = _overview(client, college["token"]).json()
    flagged = {a["branch"] for a in body["attention"]}
    assert flagged == {"ISE", "CSE"}          # both are under 75% in this fixture
    for a in body["attention"]:
        assert a["attendance_pct"] < body["threshold_pct"]

    stats = {b["branch"]: b for b in body["branch_stats"]}
    assert stats["ISE"]["below_threshold_students"] == 1
    assert stats["CSE"]["below_threshold_students"] == 1


def test_trend_skips_days_without_records(client, db, college):
    body = _overview(client, college["token"], {"trend_days": 7}).json()
    dates = [t["date"] for t in body["monthly_trend"]]
    assert dates == [college["yest"].isoformat(), college["today"].isoformat()]
    assert body["monthly_trend"][0]["pct"] == round(7 / 9 * 100, 1)


def test_empty_college_returns_no_fabricated_numbers(client, db):
    _, token = create_user(db, "principal-empty", "admin", branch="ISE")
    body = _overview(client, token).json()
    assert body["total_students"] == 0
    assert body["total_teachers"] == 0
    assert body["total_departments"] == 0
    assert body["overall_attendance_pct"] is None      # not 0.0
    assert body["present_today"] == 0
    assert body["absent_today"] == 0
    assert body["low_attendance_count"] == 0
    assert body["branch_stats"] == []
    assert body["monthly_trend"] == []
    assert body["attention"] == []


def test_department_without_attendance_is_null_not_zero(client, db, college):
    _subject(db, "ISE7Z09", "ISE", semester="7th Semester")
    stats = {b["branch"]: b for b in _overview(client, college["token"]).json()["branch_stats"]}
    assert stats["ISE"]["attendance_pct"] is not None


def test_bad_date_range_is_rejected(client, db, college):
    res = _overview(client, college["token"], {"start_date": "2026-05-01",
                                               "end_date": "2026-01-01"})
    assert res.status_code == 400


def test_only_admin_can_read_the_overview(client, db, college):
    for role in ("hod", "teacher", "student"):
        _, token = create_user(db, "notadmin-%s" % role, role, branch="ISE")
        assert _overview(client, token).status_code == 403
    assert _overview(client, "nope").status_code in (401, 403)


def test_repeated_records_never_inflate_counts(client, db, college):
    """The same student marked in several subjects today is still counted once."""
    prog = db.query(main.Subject).filter(main.Subject.code == "ISE5P02").first()
    for _ in range(3):
        _mark(db, college["s2"], prog, "present", college["today"], marked_by=1)
    body = _overview(client, college["token"]).json()
    assert body["present_today"] == 1
    assert body["absent_today"] == 1
    assert body["total_students"] == 3
    seen = [s["student_id"] for s in body["low_attendance_students"]]
    assert len(seen) == len(set(seen))          # no duplicated low-attendance row