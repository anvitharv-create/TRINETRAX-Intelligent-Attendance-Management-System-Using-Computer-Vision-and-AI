"""Student attendance report: own-data scoping, summary maths, PDF export."""
import datetime

import main
import student_report
from conftest import auth, create_user


def _make_student(db, usn, branch="ISE", semester="5th Semester", section="Section A",
                  name=None, full_name=None):
    user = main.User(
        username=usn,
        email="%s@stu.local" % usn,
        full_name=full_name or "Student %s" % usn,
        role="student",
        branch=branch,
        semester=semester,
        section=section,
        hashed_password=main.hash_password("x"),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    student = main.Student(
        student_id=usn,
        user_id=user.id,
        name=name or "Student %s" % usn,
        roll_number="R%s" % usn,
        branch=branch,
        semester=semester,
        section=section,
        email="%s@stu.local" % usn,
    )
    db.add(student)
    db.commit()
    db.refresh(student)
    return user, student


def _student_with_token(db, usn, token):
    user, student = _make_student(db, usn)
    main._token_store_create(db, token, user.id)
    return user, student


def _teacher(db, username="sr-teacher"):
    user, _token = create_user(db, username, "teacher")
    teacher = main.Teacher(
        user_id=user.id, name=user.full_name, employee_id="EMP-%s" % username,
        branch="ISE", semester="5th Semester", section="Section A",
        email="%s@teach.local" % username,
    )
    db.add(teacher)
    db.commit()
    db.refresh(teacher)
    return user, teacher


def _subject(db, code="BIS203", name="Database Systems", semester="5th Semester"):
    subject = main.Subject(name=name, code=code, branch="ISE",
                           semester=semester, teacher_id=None)
    db.add(subject)
    db.commit()
    db.refresh(subject)
    return subject


def _mark(db, student, subject, day, status, marked_by_user_id):
    db.add(main.AttendanceRecord(
        student_id=student.id, subject_id=subject.id, date=day,
        status=status, marked_by=marked_by_user_id,
    ))
    db.commit()


def _seed(db):
    """One student with 3 records (2 present, 1 absent) across two subjects."""
    marker, _ = _teacher(db)
    subj = _subject(db)
    other = _subject(db, code="BIS402", name="Cloud Computing")

    today = datetime.date.today()
    d1 = today - datetime.timedelta(days=10)
    d2 = today - datetime.timedelta(days=5)
    d3 = today - datetime.timedelta(days=1)

    su, student = _student_with_token(db, "1AR24IS001", "sr-token-1")
    _mark(db, student, subj, d1, "present", marker.id)
    _mark(db, student, subj, d2, "present", marker.id)
    _mark(db, student, other, d3, "absent", marker.id)

    # a second student whose records must never leak in
    su2, student2 = _make_student(db, "1AR24IS002")
    _mark(db, student2, subj, d1, "absent", marker.id)
    _mark(db, student2, other, d2, "present", marker.id)

    return {
        "token": "sr-token-1", "marker": marker, "subj": subj, "other": other,
        "student": student, "student2": student2, "d1": d1, "d2": d2, "d3": d3,
    }


def _get(client, token, **params):
    qs = "&".join("%s=%s" % (k, v) for k, v in params.items() if v is not None)
    return client.get("/api/student/report" + ("?" + qs if qs else ""),
                      headers=auth(token))


def test_requires_auth_and_student_role(client, db):
    seed = _seed(db)
    assert client.get("/api/student/report").status_code in (401, 403)

    # a teacher may not read a student report
    teacher_user, _ = _teacher(db, "sr-teacher2")
    main._token_store_create(db, "sr-token-teacher2", teacher_user.id)
    res = client.get("/api/student/report", headers=auth("sr-token-teacher2"))
    assert res.status_code == 403

    res = _get(client, seed["token"])
    assert res.status_code == 200, res.text


def test_student_without_profile_gets_404(client, db):
    _, token = create_user(db, "sr-noprofile", "student")
    assert client.get("/api/student/report", headers=auth(token)).status_code == 404


def test_reports_own_profile_and_all_subjects(client, db):
    seed = _seed(db)
    body = _get(client, seed["token"]).json()

    profile = body["student"]
    assert profile["usn"] == "1AR24IS001"
    assert profile["name"] == "Student 1AR24IS001"
    assert profile["roll_number"] == "R1AR24IS001"
    assert profile["branch"] == "ISE"
    assert profile["semester"] == "5th Semester"
    assert profile["section"] == "Section A"
    assert profile["email"] == "1AR24IS001@stu.local"
    assert profile["username"] == "1AR24IS001"

    # every subject of the student appears, no subject selector involved
    subjects = {r["subject"] for r in body["records"]}
    assert subjects == {"Database Systems", "Cloud Computing"}
    assert body["scope"] == "All subjects"
    assert body["min_attendance_pct"] == 75


def test_only_own_records_and_student_id_param_is_ignored(client, db):
    seed = _seed(db)
    body = _get(client, seed["token"], student_id=seed["student2"].id).json()
    assert body["student"]["usn"] == "1AR24IS001"
    assert len(body["records"]) == 3
    # the other student's 2 records never show up
    assert {r["subject_code"] for r in body["records"]} == {"BIS203", "BIS402"}


def test_records_are_newest_first(client, db):
    seed = _seed(db)
    body = _get(client, seed["token"]).json()
    dates = [r["date"] for r in body["records"]]
    assert dates == sorted(dates, reverse=True)
    assert dates == [seed["d3"].isoformat(), seed["d2"].isoformat(), seed["d1"].isoformat()]


def test_summary_maths_with_eligibility(client, db):
    seed = _seed(db)
    summary = _get(client, seed["token"]).json()["summary"]
    assert summary["total_marked"] == 3
    assert summary["present"] == 2
    assert summary["absent"] == 1
    assert summary["other"] == 0
    assert summary["pct"] == 66.7
    assert summary["eligible"] is False
    assert summary["min_attendance_pct"] == 75
    assert summary["shortage_pct"] == 8.3
    # 3 marked, 2 present -> one more present class reaches exactly 75%
    assert summary["classes_to_attend"] == 1


def test_classes_to_attend_exact_maths():
    # 74 present of 100 -> needs 4 more: 78/104 = 75%
    assert student_report._classes_to_attend(74, 100) == 4
    # already at / above the minimum
    assert student_report._classes_to_attend(75, 100) is None
    assert student_report._classes_to_attend(80, 100) is None
    # nothing marked yet
    assert student_report._classes_to_attend(0, 0) is None
    # 0 of 1 marked -> three more presents: 3/4 = 75%
    assert student_report._classes_to_attend(0, 1) == 3
    # an impossible 100% requirement never invents a number
    original = student_report.MIN_ATTENDANCE_PCT
    try:
        student_report.MIN_ATTENDANCE_PCT = 100.0
        assert student_report._classes_to_attend(0, 1) is None
    finally:
        student_report.MIN_ATTENDANCE_PCT = original


def test_unknown_is_not_counted_as_absent(client, db):
    marker, _ = _teacher(db)
    subj = _subject(db)
    _, student = _student_with_token(db, "1AR24IS009", "sr-unknown-token")

    day = datetime.date.today() - datetime.timedelta(days=2)
    _mark(db, student, subj, day, "present", marker.id)
    _mark(db, student, subj, day, "unknown", marker.id)

    body = _get(client, "sr-unknown-token").json()["summary"]
    assert body["total_marked"] == 2
    assert body["present"] == 1
    assert body["absent"] == 0
    assert body["other"] == 1
    assert body["pct"] == 50.0


def test_date_range_filters_records_and_summary(client, db):
    seed = _seed(db)
    body = _get(client, seed["token"], start_date=seed["d2"].isoformat(),
                end_date=seed["d3"].isoformat()).json()
    assert body["filter"]["start_date"] == seed["d2"].isoformat()
    assert body["filter"]["end_date"] == seed["d3"].isoformat()
    assert [r["date"] for r in body["records"]] == [seed["d3"].isoformat(),
                                                    seed["d2"].isoformat()]
    summary = body["summary"]
    assert summary["total_marked"] == 2
    assert summary["present"] == 1
    assert summary["absent"] == 1
    assert summary["pct"] == 50.0

    # a window with nothing in it reports the empty state, not fake rows
    empty = _get(client, seed["token"],
                 start_date=(seed["d1"] + datetime.timedelta(days=1)).isoformat(),
                 end_date=(seed["d1"] + datetime.timedelta(days=2)).isoformat()).json()
    assert empty["records"] == []
    assert empty["summary"]["pct"] is None
    assert empty["summary"]["eligible"] is None
    assert empty["summary"]["classes_to_attend"] is None


def test_inverted_date_range_is_rejected(client, db):
    seed = _seed(db)
    res = _get(client, seed["token"], start_date=seed["d3"].isoformat(),
               end_date=seed["d1"].isoformat())
    assert res.status_code == 400


def test_eligible_student_gets_no_shortage_or_classes_needed(client, db):
    marker, _ = _teacher(db)
    subj = _subject(db)
    _, student = _student_with_token(db, "1AR24IS010", "sr-good-token")

    day = datetime.date.today() - datetime.timedelta(days=3)
    _mark(db, student, subj, day, "present", marker.id)
    _mark(db, student, subj, day, "present", marker.id)

    summary = _get(client, "sr-good-token").json()["summary"]
    assert summary["pct"] == 100.0
    assert summary["eligible"] is True
    assert summary["shortage_pct"] == 0.0
    assert summary["classes_to_attend"] is None


def test_student_with_no_records(client, db):
    _, student = _student_with_token(db, "1AR24IS011", "sr-empty-token")

    body = _get(client, "sr-empty-token").json()
    assert body["records"] == []
    assert body["summary"]["pct"] is None
    assert body["summary"]["eligible"] is None
    assert body["summary"]["shortage_pct"] is None
    assert body["summary"]["classes_to_attend"] is None
    assert body["student"]["usn"] == "1AR24IS011"


# --------------------------------------------------------------------------
# PDF
# --------------------------------------------------------------------------
def _assert_valid_pdf(raw):
    assert raw.startswith(b"%PDF-1.4")
    assert raw.rstrip().endswith(b"%%EOF")
    idx = raw.rfind(b"startxref")
    assert idx != -1
    xref_pos = int(raw[idx:].split()[1])
    assert raw[xref_pos:xref_pos + 4] == b"xref"
    lines = raw[xref_pos:].split(b"\n")
    size = int(lines[1].split()[1])
    for i in range(1, size):
        offset = int(lines[2 + i].split()[0])
        assert raw[offset:].startswith(b"%d 0 obj" % i), "bad offset for obj %d" % i


def test_pdf_download(client, db):
    seed = _seed(db)
    res = client.get("/api/student/report/pdf", headers=auth(seed["token"]))
    assert res.status_code == 200, res.text
    assert res.headers["content-type"].startswith("application/pdf")
    assert "attendance_report_1AR24IS001.pdf" in res.headers["content-disposition"]

    raw = res.content
    _assert_valid_pdf(raw)
    # the visible content really is this report
    assert b"1AR24IS001" in raw
    assert b"Student 1AR24IS001" in raw
    assert b"Database Systems" in raw
    assert b"NOT ELIGIBLE" in raw
    assert b"66.7" in raw
    assert b"Present" in raw and b"Absent" in raw


def test_pdf_respects_date_range(client, db):
    seed = _seed(db)
    # only the newest day survives: the Cloud Computing "absent" record
    only = seed["d3"].isoformat()
    res = client.get("/api/student/report/pdf?start_date=%s&end_date=%s" % (only, only),
                     headers=auth(seed["token"]))
    assert res.status_code == 200
    _assert_valid_pdf(res.content)
    assert b"Cloud Computing" in res.content
    assert b"Database Systems" not in res.content


def test_pdf_requires_student_role(client, db):
    _seed(db)
    assert client.get("/api/student/report/pdf").status_code in (401, 403)


def test_pdf_paginates_many_rows():
    records = [
        {"id": i, "date": "2026-01-01", "subject": "Subject %d" % (i % 3),
         "subject_code": "C%d" % (i % 3), "status": "present" if i % 2 else "absent"}
        for i in range(1, 141)
    ]
    report = {
        "generated_at": "01 Jan 2026, 00:00",
        "student": {"name": "Bulk Student", "usn": "1AR24IS999", "roll_number": "R999",
                    "branch": "ISE", "semester": "5th Semester", "section": "Section A",
                    "email": "bulk@stu.local", "username": "bulk"},
        "scope": "All subjects",
        "filter": {"start_date": None, "end_date": None},
        "min_attendance_pct": 75,
        "summary": {"total_marked": 140, "present": 70, "absent": 70, "other": 0,
                    "pct": 50.0, "eligible": False, "min_attendance_pct": 75,
                    "shortage_pct": 25.0, "classes_to_attend": 47},
        "records": records,
    }
    raw = student_report.render_pdf(report)
    _assert_valid_pdf(raw)
    assert raw.count(b"/Type /Page") >= 3
    assert b"Page 1 of" in raw and b"Page 2 of" in raw
