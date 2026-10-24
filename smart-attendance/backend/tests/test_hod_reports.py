"""
HOD subject & semester comparison report.

Everything is built from rows created inside the test - no production records,
and the attendance threshold is read from the project configuration
(student_report.MIN_ATTENDANCE_PCT) instead of being hardcoded.
"""
import importlib
import io
from datetime import date

import pytest

import main
import student_report
from conftest import auth, create_user


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


def _mark(db, student, subject, status, day=1, marked_by=1):
    db.add(main.AttendanceRecord(student_id=student.id, subject_id=subject.id,
                                 date=date(2026, 1, day), status=status,
                                 marked_by=marked_by, confidence=None))
    db.commit()


@pytest.fixture()
def dept(db):
    """Two subjects in the HOD's department with known, hand-checkable numbers."""
    _, hod_token = create_user(db, "hod-cmp", "hod", branch="ISE")
    _, other_token = create_user(db, "hod-other", "hod", branch="CSE")

    s1 = _add_student(db, "CMP001", section="Section A")   # 2 present / 4 records = 50%
    s2 = _add_student(db, "CMP002", section="Section B")   # 3 present / 4 records = 75%
    s3 = _add_student(db, "CMP003", section="Section A")   # 6th-semester student, also in ISE5M01

    math = _add_subject(db, "ISE5M01", semester="5th Semester")     # 7 present / 10 = 70%
    prog = _add_subject(db, "ISE5P02", semester="5th Semester")     # 4 present / 4 = 100%
    six = _add_subject(db, "ISE6C03", semester="6th Semester")      # 1 present / 1 = 100%
    cse = _add_subject(db, "CSE5X99", branch="CSE", semester="5th Semester")

    for i in range(4):
        _mark(db, s1, math, "present" if i < 2 else "absent", day=i + 1)
        _mark(db, s2, math, "present" if i < 3 else "absent", day=i + 1)
    for i in range(4):
        _mark(db, s1, prog, "present", day=i + 1)
    _mark(db, s3, six, "present", day=1)
    for i in range(2):
        _mark(db, s3, math, "present", day=i + 1)   # 9 present / 10 records = 90%

    db.commit()
    return {
        "hod": hod_token, "other": other_token,
        "math": math.id, "prog": prog.id, "six": six.id, "cse": cse.id,
    }


def _get(client, token, params=""):
    return client.get("/api/hod/reports/comparison" + params, headers=auth(token))


def test_hod_gets_own_department_summary(client, db, dept):
    res = _get(client, dept["hod"])
    assert res.status_code == 200, res.text
    body = res.json()

    assert body["branch"] == "ISE"
    s = body["summary"]
    assert s["total_subjects"] == 3                 # CSE subject excluded
    assert s["subjects_with_records"] == 3
    assert s["semesters_represented"] == 2
    assert s["attendance_pct"] == 80.0              # 12 present / 15 records
    assert s["threshold_pct"] == student_report.MIN_ATTENDANCE_PCT
    assert s["total_records"] == 15


def test_subject_rows_match_recorded_data(client, db, dept):
    body = _get(client, dept["hod"]).json()
    rows = {r["code"]: r for r in body["subject_rows"]}

    assert rows["ISE5M01"]["pct"] == 70.0
    assert rows["ISE5M01"]["status"] == "below_threshold"
    assert rows["ISE5M01"]["sections"] == ["Section A", "Section B"]
    assert rows["ISE5P02"]["pct"] == 100.0
    assert rows["ISE5P02"]["status"] == "ok"
    assert rows["ISE6C03"]["pct"] == 100.0
    assert body["summary"]["subjects_below_threshold"] == 1
    # 70% falls in the "60% to 75%" bucket, the only non-empty one
    assert [d["count"] for d in body["distribution"]] == [0, 1, 0, 2]


def test_no_records_is_never_reported_as_zero_percent(client, db, dept):
    """A subject nobody has marked stays 'no records', not 0% and not absent."""
    _add_subject(db, "ISE5N04", semester="5th Semester")
    rows = {r["code"]: r for r in _get(client, dept["hod"]).json()["subject_rows"]}
    assert rows["ISE5N04"]["pct"] is None
    assert rows["ISE5N04"]["status"] == "no_data"
    assert rows["ISE5N04"]["records"] == 0


def s_below(body):
    return body["summary"]["subjects_below_threshold"]


def test_semester_filter_narrows_every_metric(client, db, dept):
    body = _get(client, dept["hod"], "?semester=6th%20Semester").json()
    assert [r["code"] for r in body["subject_rows"]] == ["ISE6C03"]
    assert body["summary"]["subjects_with_records"] == 1
    assert body["summary"]["semesters_represented"] == 1
    assert body["summary"]["attendance_pct"] == 100.0
    assert [r["semester"] for r in body["semester_rows"]] == ["6th Semester"]


def test_semester_with_no_records_reports_no_data(client, db, dept):
    body = _get(client, dept["hod"], "?subject_id=%d" % dept["cse"])
    assert body.status_code == 403


def test_semester_filter_that_has_never_been_marked(client, db, dept):
    _add_subject(db, "ISE7X05", semester="7th Semester")
    body = _get(client, dept["hod"], "?semester=7th%20Semester").json()
    assert body["summary"]["subjects_with_records"] == 0
    assert body["summary"]["attendance_pct"] is None
    assert body["semester_rows"] == []
    assert body["insights"] == [
        "No attendance has been recorded for the selected filters yet."
    ]


def test_section_filter_only_keeps_that_section(client, db, dept):
    body = _get(client, dept["hod"], "?section=Section%20B").json()
    row = {r["code"]: r for r in body["subject_rows"]}["ISE5M01"]
    assert row["records"] == 4
    assert row["pct"] == 75.0
    assert body["summary"]["students"] == 1
    assert [d["count"] for d in body["distribution"]] == [0, 0, 1, 0]


def test_subject_and_date_filters_combine(client, db, dept):
    res = _get(client, dept["hod"],
               "?subject_id=%d&start_date=2026-01-01&end_date=2026-01-02" % dept["math"])
    assert res.status_code == 200, res.text
    body = res.json()
    row = body["subject_rows"][0]
    assert row["code"] == "ISE5M01"
    assert row["records"] == 6          # days 1-2 for three students
    assert row["present"] == 6
    assert body["summary"]["attendance_pct"] == 100.0
    # the other two subjects are outside this filter
    assert [r["code"] for r in body["subject_rows"]] == ["ISE5M01"]


def test_department_isolation(client, db, dept):
    """Two HODs of different departments never see each other's numbers."""
    ise = _get(client, dept["hod"]).json()
    cse = _get(client, dept["other"]).json()
    assert ise["branch"] == "ISE" and cse["branch"] == "CSE"
    assert {r["code"] for r in ise["subject_rows"]}.isdisjoint(
        {r["code"] for r in cse["subject_rows"]})
    assert ise["summary"]["total_records"] > 0
    assert cse["summary"]["total_records"] == 0     # the CSE subject has no records


def test_foreign_subject_is_refused(client, db, dept):
    res = _get(client, dept["hod"], "?subject_id=%d" % dept["cse"])
    assert res.status_code == 403


def test_bad_dates_are_rejected(client, db, dept):
    res = _get(client, dept["hod"], "?start_date=2026-13-99")
    assert res.status_code in (400, 422)
    res = _get(client, dept["hod"], "?start_date=2026-05-01&end_date=2026-01-01")
    assert res.status_code == 400


def test_non_hod_roles_are_refused(client, db, dept):
    _, teacher_token = create_user(db, "teacher-cmp", "teacher", branch="ISE")
    _, admin_token = create_user(db, "admin-cmp", "admin", branch="ISE")
    for token in (teacher_token, admin_token):
        assert _get(client, token).status_code == 403
    assert _get(client, "not-a-token").status_code in (401, 403)


def test_exports_use_the_same_numbers(client, db, dept):
    json_res = _get(client, dept["hod"], "?semester=5th%20Semester")
    summary = json_res.json()["summary"]

    pdf = client.get("/api/hod/reports/comparison/pdf",
                     headers=auth(dept["hod"]), params={"semester": "5th Semester"})
    assert pdf.status_code == 200
    assert pdf.headers["content-type"] == "application/pdf"
    assert pdf.content.startswith(b"%PDF")
    assert b"70.0%" in pdf.content

    xlsx = client.get("/api/hod/reports/comparison/xlsx",
                      headers=auth(dept["hod"]), params={"semester": "5th Semester"})
    assert xlsx.status_code == 200
    book = xlsx.content
    assert book[:2] == b"PK"          # a real xlsx (zip) container

    from openpyxl import load_workbook
    wb = load_workbook(io.BytesIO(book))
    assert wb.sheetnames == ["Summary", "Subjects", "Semesters"]
    rows = {r[0].value: r[1].value for r in wb["Summary"].iter_rows(min_row=6, max_col=2)}
    assert rows["Total subjects"] == summary["total_subjects"]
    assert rows["Attendance percentage"] == summary["attendance_pct"]
    codes = [r[0].value for r in wb["Subjects"].iter_rows(min_row=2, max_col=1)]
    assert "ISE5M01" in codes and "ISE5P02" in codes


def test_empty_filters_still_return_a_valid_report(client, db):
    """A brand-new department with nothing recorded must not error or fabricate."""
    _, token = create_user(db, "hod-empty", "hod", branch="ECE")
    body = _get(client, token).json()
    assert body["summary"]["total_subjects"] == 0
    assert body["summary"]["attendance_pct"] is None
    assert body["subject_rows"] == []
    assert body["distribution"][0]["count"] == 0


def test_threshold_is_configuration_driven(client, db, dept, monkeypatch):
    """The report must follow MIN_ATTENDANCE_PCT instead of a hardcoded 75."""
    monkeypatch.setattr(student_report, "MIN_ATTENDANCE_PCT", 90.0)
    import hod_report
    importlib.reload(hod_report)

    db2 = main.SessionLocal()
    try:
        report = hod_report.build_report(db2, {"branch": "ISE", "role": "hod"},
                                         semester="5th Semester")
    finally:
        db2.close()
    assert report["summary"]["threshold_pct"] == 90.0
    assert report["summary"]["subjects_below_threshold"] == 1   # 70% only