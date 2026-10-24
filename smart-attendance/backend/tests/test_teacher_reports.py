"""Teacher attendance reports: subject scoping, USN ordering, date filters."""
import datetime

import main
from conftest import auth, create_user


def _make_teacher(db, username, branch="ISE"):
    user, token = create_user(db, username, "teacher", branch=branch)
    teacher = main.Teacher(
        user_id=user.id,
        name=user.full_name,
        employee_id="EMP-%s" % username,
        branch=branch,
        semester="5th Semester",
        section="Section A",
        email="%s@teach.local" % username,
    )
    db.add(teacher)
    db.commit()
    db.refresh(teacher)
    return user, teacher, token


def _make_student(db, usn, branch="ISE", semester="5th Semester", section="Section A"):
    user = main.User(
        username=usn,
        email="%s@stu.local" % usn,
        full_name="Student %s" % usn,
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
        name="Student %s" % usn,
        roll_number=usn,
        branch=branch,
        semester=semester,
        section=section,
        email="%s@stu.local" % usn,
    )
    db.add(student)
    db.commit()
    db.refresh(student)
    return student


def _mark(db, student, subject, day, status, marked_by):
    db.add(main.AttendanceRecord(
        student_id=student.id,
        subject_id=subject.id,
        date=day,
        status=status,
        marked_by=marked_by,
    ))
    db.commit()


def _seed(db):
    """Two teachers, three subjects (one each + one unassigned), three students.

    Dates are relative to today so the assertions stay valid forever.
    Returns a dict of everything the tests need.
    """
    _, teacher, token = _make_teacher(db, "tr-owner")
    _, other_teacher, other_token = _make_teacher(db, "tr-other")

    mine = main.Subject(name="Database Systems", code="BIS203", branch="ISE",
                        semester="5th Semester", teacher_id=teacher.id)
    theirs = main.Subject(name="Cloud Computing", code="BIS402", branch="ISE",
                          semester="5th Semester", teacher_id=other_teacher.id)
    unassigned = main.Subject(name="Open Elective", code="BISX01", branch="ISE",
                              semester="5th Semester", teacher_id=None)
    db.add_all([mine, theirs, unassigned])
    db.commit()

    # deliberately inserted out of USN order
    s3 = _make_student(db, "1AR24IS003")
    s1 = _make_student(db, "1AR24IS001")
    s2 = _make_student(db, "1AR24IS002")

    today = datetime.date.today()
    d1 = today - datetime.timedelta(days=10)
    d2 = today - datetime.timedelta(days=5)
    d3 = today - datetime.timedelta(days=1)

    _mark(db, s1, mine, d1, "present", teacher.user_id)
    _mark(db, s1, mine, d2, "present", teacher.user_id)
    _mark(db, s1, mine, d3, "absent", teacher.user_id)
    # foreign-subject record that must never leak into this teacher's report
    _mark(db, s1, theirs, d1, "absent", other_teacher.user_id)

    _mark(db, s2, mine, d1, "present", teacher.user_id)
    _mark(db, s2, mine, d2, "unknown", teacher.user_id)

    _mark(db, s3, mine, d1, "present", teacher.user_id)
    _mark(db, s3, theirs, d1, "present", other_teacher.user_id)

    return {
        "token": token,
        "other_token": other_token,
        "teacher": teacher,
        "mine": mine,
        "theirs": theirs,
        "unassigned": unassigned,
        "students": [s1, s2, s3],
        "d1": d1, "d2": d2, "d3": d3,
    }


def _get(client, token, **params):
    qs = "&".join("%s=%s" % (k, v) for k, v in params.items() if v is not None)
    return client.get("/api/teacher/reports/attendance" + ("?" + qs if qs else ""),
                      headers=auth(token))


def test_report_requires_auth_and_teacher_role(client, db):
    seed = _seed(db)
    assert client.get("/api/teacher/reports/attendance").status_code in (401, 403)

    stu_user, stu_token = create_user(db, "tr-student", "student")
    res = client.get("/api/teacher/reports/attendance", headers=auth(stu_token))
    assert res.status_code == 403

    res = _get(client, seed["token"])
    assert res.status_code == 200, res.text


def test_report_only_covers_assigned_subjects(client, db):
    seed = _seed(db)

    res = _get(client, seed["token"])
    body = res.json()

    assigned_ids = [s["id"] for s in body["subjects"]]
    assert assigned_ids == [seed["mine"].id]
    assert [s["subject_id"] for s in body["subject_stats"]] == [seed["mine"].id]

    # the unassigned and the other teacher's subjects are not listed either
    listed = {s["id"] for s in body["subjects"]}
    assert seed["theirs"].id not in listed
    assert seed["unassigned"].id not in listed

    # records stored against a foreign subject never reach this report:
    # student 001 has 3 records in `mine` + 1 absent in `theirs`
    rows = {r["student_id"]: r for r in body["rows"]}
    assert rows["1AR24IS001"]["total"] == 3
    assert rows["1AR24IS001"]["absent"] == 1
    # student 003's `theirs` record is excluded too (1 record, not 2)
    assert rows["1AR24IS003"]["total"] == 1
    assert body["summary"]["records"] == 6

    # asking for a subject that is not mine is refused
    for foreign in (seed["theirs"].id, seed["unassigned"].id):
        res = _get(client, seed["token"], subject_id=foreign)
        assert res.status_code == 403, res.text

    # restricting to my own subject works
    res = _get(client, seed["token"], subject_id=seed["mine"].id)
    assert res.status_code == 200
    assert len(res.json()["rows"]) == 3


def test_each_teacher_sees_only_their_own_subjects(client, db):
    seed = _seed(db)

    body = _get(client, seed["other_token"]).json()
    assert [s["id"] for s in body["subjects"]] == [seed["theirs"].id]
    assert [s["subject_id"] for s in body["subject_stats"]] == [seed["theirs"].id]
    rows = {r["student_id"]: r for r in body["rows"]}
    # 001 absent in `theirs`, 003 present in `theirs` - nothing from `mine`
    assert rows["1AR24IS001"]["total"] == 1
    assert rows["1AR24IS001"]["absent"] == 1
    assert "1AR24IS002" not in rows
    assert body["summary"]["records"] == 2

    # and cannot reach my subject
    assert _get(client, seed["other_token"], subject_id=seed["mine"].id).status_code == 403


def test_rows_are_sorted_by_usn_ascending(client, db):
    seed = _seed(db)
    body = _get(client, seed["token"]).json()
    usns = [r["student_id"] for r in body["rows"]]
    assert usns == sorted(usns) == ["1AR24IS001", "1AR24IS002", "1AR24IS003"]


def test_date_range_filters_records_and_summaries(client, db):
    seed = _seed(db)
    d2 = seed["d2"].isoformat()
    d3 = seed["d3"].isoformat()

    body = _get(client, seed["token"], start_date=d2, end_date=d3).json()
    assert body["filters"]["start_date"] == d2
    assert body["filters"]["end_date"] == d3

    rows = {r["student_id"]: r for r in body["rows"]}
    # only d2 + d3 records survive for 001 (d1 dropped)
    assert rows["1AR24IS001"]["total"] == 2
    assert rows["1AR24IS001"]["present"] == 1
    assert rows["1AR24IS001"]["absent"] == 1
    assert rows["1AR24IS001"]["pct"] == 50.0
    # 002 has a single `unknown` record in range - counts in the denominator
    assert rows["1AR24IS002"]["total"] == 1
    assert rows["1AR24IS002"]["present"] == 0
    assert rows["1AR24IS002"]["pct"] == 0.0
    # 003 only has a d1 record, so it drops out of the period entirely
    assert "1AR24IS003" not in rows

    assert body["summary"]["students"] == 2
    assert body["summary"]["records"] == 3
    assert body["summary"]["present"] == 1
    assert body["summary"]["absent"] == 1
    assert body["summary"]["other"] == 1
    assert body["summary"]["pct"] == 33.3

    # a window that contains nothing reports zeros, never fabricated rows
    empty = _get(client, seed["token"],
                 start_date=(seed["d1"] + datetime.timedelta(days=1)).isoformat(),
                 end_date=(seed["d1"] + datetime.timedelta(days=2)).isoformat()).json()
    assert empty["rows"] == []
    assert empty["summary"]["records"] == 0
    assert empty["summary"]["pct"] == 0.0


def test_unbounded_report_uses_existing_percentage_rule(client, db):
    seed = _seed(db)
    rows = {r["student_id"]: r for r in _get(client, seed["token"]).json()["rows"]}
    # present / total * 100, unknown counted in the denominator
    assert rows["1AR24IS001"]["pct"] == 66.7   # 2 present of 3
    assert rows["1AR24IS002"]["pct"] == 50.0   # 1 present of 2 (1 unknown)
    assert rows["1AR24IS003"]["pct"] == 100.0  # 1 present of 1


def test_inverted_date_range_is_rejected(client, db):
    seed = _seed(db)
    res = _get(client, seed["token"],
               start_date=seed["d3"].isoformat(), end_date=seed["d1"].isoformat())
    assert res.status_code == 400


def test_teacher_without_profile_gets_404(client, db):
    _, token = create_user(db, "tr-noprofile", "teacher")
    res = client.get("/api/teacher/reports/attendance", headers=auth(token))
    assert res.status_code == 404


def test_legacy_report_endpoint_still_supports_date_filters(client, db):
    seed = _seed(db)
    _, admin_token = create_user(db, "legacy-admin", "admin")
    d2 = seed["d2"].isoformat()
    res = client.get("/api/reports/attendance?subject_id=%d&start_date=%s&end_date=%s"
                     % (seed["mine"].id, d2, seed["d3"].isoformat()),
                     headers=auth(admin_token))
    assert res.status_code == 200
    records = res.json()["records"]
    for entries in records.values():
        assert all(d2 <= e["date"] <= seed["d3"].isoformat() for e in entries)


def test_legacy_report_endpoint_is_admin_only(client, db):
    """The subject-records dump is used only by the Principal reports page,
    so students and teachers must never reach it."""
    _seed(db)
    _, teacher_token = create_user(db, "legacy-teach", "teacher")
    _, student_token = create_user(db, "legacy-stu", "student")
    assert client.get("/api/reports/attendance").status_code in (401, 403)
    assert client.get("/api/reports/attendance",
                      headers=auth(teacher_token)).status_code == 403
    assert client.get("/api/reports/attendance",
                      headers=auth(student_token)).status_code == 403


def test_attendance_list_date_range_and_single_date_still_work(client, db):
    seed = _seed(db)
    _, staff_token = create_user(db, "att-list-staff", "teacher")
    h = auth(staff_token)
    subject = seed["mine"].id
    d1, d2, d3 = (seed[k].isoformat() for k in ("d1", "d2", "d3"))

    res = client.get("/api/attendance?subject_id=%d&start_date=%s&end_date=%s"
                     % (subject, d2, d3), headers=h)
    assert res.status_code == 200
    dates = sorted({r["date"] for r in res.json()})
    assert dates == [d2, d3]

    # the original single-date filter is unchanged
    res = client.get("/api/attendance?subject_id=%d&date=%s" % (subject, d1), headers=h)
    assert res.status_code == 200
    assert all(r["date"] == d1 for r in res.json())
    assert len(res.json()) == 3

    # no filters at all still returns everything for the subject
    res = client.get("/api/attendance?subject_id=%d" % subject, headers=h)
    assert res.status_code == 200
    assert len(res.json()) == 6

    # inverted range is rejected
    res = client.get("/api/attendance?subject_id=%d&start_date=%s&end_date=%s"
                     % (subject, d3, d1), headers=h)
    assert res.status_code == 400
