"""Principal/Admin reports page: filters, section stats, threshold and export.

Every number below is hand-checkable from the rows created inside the test -
no production records, no invented figures.
"""
import io
from datetime import date, timedelta

import openpyxl

import main
import student_report
from conftest import auth, create_user

TODAY = date.today()


def _day(offset):
    return TODAY - timedelta(days=offset)


def _add_student(db, sid, branch="ISE", semester="5th Semester", section="Section A"):
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


def _add_subject(db, code, branch="ISE", semester="5th Semester"):
    subj = main.Subject(name="%s Name" % code, code=code, branch=branch, semester=semester)
    db.add(subj)
    db.commit()
    db.refresh(subj)
    return subj


def _mark(db, student, subject, status, on):
    db.add(main.AttendanceRecord(student_id=student.id, subject_id=subject.id,
                                 date=on, status=status, marked_by=1, confidence=None))
    db.commit()


def _seed(db):
    """REP001: 1 present / 2 = 50% (Section A)
       REP002: 2 present / 2 = 100% (Section B)"""
    _, admin_token = create_user(db, "adm-rep", "admin")
    _, teacher_token = create_user(db, "tch-rep", "teacher")
    s1 = _add_student(db, "REP001", section="Section A")
    s2 = _add_student(db, "REP002", section="Section B")
    math = _add_subject(db, "REP5M01")
    _mark(db, s1, math, "present", _day(1))
    _mark(db, s1, math, "absent", _day(2))
    _mark(db, s2, math, "present", _day(1))
    _mark(db, s2, math, "present", _day(2))
    return {"admin": admin_token, "teacher": teacher_token, "subject": math,
            "s1": s1, "s2": s2}


def _export(client, token, params):
    from urllib.parse import urlencode
    return client.get("/api/admin/reports/export?%s" % urlencode(params),
                      headers=auth(token))


def test_export_requires_admin(client, db):
    seed_data = _seed(db)
    params = {"report_type": "college"}
    assert client.get("/api/admin/reports/export?report_type=college").status_code in (401, 403)
    assert _export(client, seed_data["teacher"], params).status_code == 403
    res = _export(client, seed_data["admin"], params)
    assert res.status_code == 200, res.text
    assert res.headers["content-type"].startswith("application/vnd.openxmlformats")
    assert res.content[:2] == b"PK"          # real xlsx (zip) file


def test_all_four_report_types_export_a_openable_workbook(client, db):
    seed_data = _seed(db)
    h = seed_data["admin"]
    cases = [
        {"report_type": "college"},
        {"report_type": "department", "branch": "ISE"},
        {"report_type": "department", "branch": "ISE", "semester": "5th Semester",
         "section": "Section A"},
        {"report_type": "subject", "subject_id": seed_data["subject"].id},
        {"report_type": "low"},
        {"report_type": "low", "branch": "ISE"},
    ]
    for params in cases:
        res = _export(client, h, params)
        assert res.status_code == 200, "%s -> %s %s" % (params, res.status_code, res.text)
        book = openpyxl.load_workbook(io.BytesIO(res.content))
        assert "Report" in book.sheetnames, book.sheetnames


def test_export_validates_its_inputs(client, db):
    seed_data = _seed(db)
    h = seed_data["admin"]
    assert _export(client, h, {"report_type": "nonsense"}).status_code == 400
    assert _export(client, h, {"report_type": "subject"}).status_code == 400
    assert _export(client, h, {"report_type": "department"}).status_code == 400
    assert _export(client, h, {"report_type": "department", "branch": "ISE",
                               "section": "Section A"}).status_code == 400
    assert _export(client, h, {"report_type": "college",
                               "start_date": "2026-02-01",
                               "end_date": "2026-01-01"}).status_code == 400


def test_college_export_matches_the_on_screen_overview(client, db):
    seed_data = _seed(db)
    h = seed_data["admin"]
    overview = client.get("/api/admin/overview", headers=auth(h)).json()
    res = _export(client, h, {"report_type": "college"})
    book = openpyxl.load_workbook(io.BytesIO(res.content))
    rows = list(book["Departments"].iter_rows(min_row=2, values_only=True))
    by_branch = {r[0]: r for r in rows}
    for b in overview["branch_stats"]:
        exported = by_branch.get(b["branch"])
        assert exported is not None, b["branch"]
        assert exported[1] == b["students"]
        assert exported[4] == b["attendance_pct"]


def test_date_range_narrows_every_number(client, db):
    seed_data = _seed(db)
    h = seed_data["admin"]
    full = client.get("/api/admin/overview", headers=auth(h)).json()
    assert full["records_in_window"] == 4
    narrow = client.get(
        "/api/admin/overview?start_date=%s&end_date=%s"
        % (_day(1).isoformat(), _day(1).isoformat()), headers=auth(h)).json()
    assert narrow["records_in_window"] == 2
    # department export in the same window agrees with the screen
    res = _export(client, h, {"report_type": "department", "branch": "ISE",
                              "start_date": _day(1).isoformat(),
                              "end_date": _day(1).isoformat()})
    book = openpyxl.load_workbook(io.BytesIO(res.content))
    sem_rows = list(book["Semesters"].iter_rows(min_row=2, values_only=True))
    assert sem_rows[0][2] == 100.0   # both students present on day 1


def test_semester_and_section_breakdowns_return_real_percentages(client, db):
    seed_data = _seed(db)
    h = seed_data["admin"]
    dept = client.get("/api/admin/departments/ISE", headers=auth(h)).json()
    assert dept["semesters"][0]["semester"] == "5th Semester"
    assert dept["semesters"][0]["attendance_pct"] == 75.0   # 3 present / 4

    sem = client.get("/api/admin/departments/ISE/5th Semester", headers=auth(h)).json()
    sections = {s["section"]: s for s in sem["sections"]}
    assert sections["Section A"]["students"] == 1
    assert sections["Section A"]["attendance_pct"] == 50.0
    assert sections["Section B"]["attendance_pct"] == 100.0

    cls = client.get("/api/admin/classes/ISE/5th Semester/Section A",
                     headers=auth(h)).json()
    assert cls["threshold_pct"] == student_report.MIN_ATTENDANCE_PCT
    assert cls["students"][0]["student_id"] == "REP001"
    assert cls["students"][0]["attendance_pct"] == 50.0

    # inverted window is rejected on every filtered endpoint
    bad = "start_date=%s&end_date=%s" % (_day(1).isoformat(), _day(5).isoformat())
    assert client.get("/api/admin/departments/ISE?%s" % bad,
                      headers=auth(h)).status_code == 400


def test_low_attendance_uses_the_configured_threshold(client, db, monkeypatch):
    seed_data = _seed(db)
    h = seed_data["admin"]
    # default configured threshold (75) flags the 50% student
    rows = client.get("/api/admin/low-attendance/ISE", headers=auth(h)).json()
    assert [r["student_id"] for r in rows] == ["REP001"]
    # a stricter operator threshold changes the source calculation, not the UI
    monkeypatch.setattr(student_report, "MIN_ATTENDANCE_PCT", 40)
    rows = client.get("/api/admin/low-attendance/ISE", headers=auth(h)).json()
    assert rows == []


def test_subject_export_shows_usn_names_and_formula(client, db):
    seed_data = _seed(db)
    h = seed_data["admin"]
    res = _export(client, h, {"report_type": "subject",
                              "subject_id": seed_data["subject"].id})
    book = openpyxl.load_workbook(io.BytesIO(res.content))
    rows = {r[0]: r for r in book["Students"].iter_rows(min_row=2, values_only=True)}
    assert rows["REP001"][1] == "REP001"        # name column
    assert rows["REP001"][2:6] == (2, 1, 1, 0)  # total, present, absent, other
    assert rows["REP001"][6] == 50.0            # present / total * 100
    assert rows["REP002"][6] == 100.0
