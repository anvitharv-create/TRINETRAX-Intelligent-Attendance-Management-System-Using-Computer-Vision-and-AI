"""Security regression tests: unauthenticated endpoint access.

These tests verify that endpoints which were previously accessible without
authentication now properly require it, and that role-based scoping is
enforced.
"""
import datetime

import main
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


def _mark(db, student, subject, status, on):
    db.add(main.AttendanceRecord(student_id=student.id, subject_id=subject.id,
                                 date=on, status=status, marked_by=1, confidence=None))
    db.commit()


def test_attendance_list_requires_auth(client, db):
    """GET /api/attendance was previously world-readable."""
    s = _add_student(db, "SEC001")
    subj = _add_subject(db, "SEC5M01")
    _mark(db, s, subj, "present", datetime.date(2026, 1, 1))

    assert client.get("/api/attendance").status_code in (401, 403)
    assert client.get("/api/attendance?subject_id=%d" % subj.id).status_code in (401, 403)

    _, teacher_token = create_user(db, "sec-teach", "teacher")
    res = client.get("/api/attendance", headers=auth(teacher_token))
    assert res.status_code == 200


def test_attendance_by_subject_requires_auth(client, db):
    """GET /api/attendance/{subject_id} was previously world-readable."""
    s = _add_student(db, "SEC002")
    subj = _add_subject(db, "SEC5M02")
    _mark(db, s, subj, "present", datetime.date(2026, 1, 1))

    assert client.get("/api/attendance/%d" % subj.id).status_code in (401, 403)

    _, teacher_token = create_user(db, "sec-teach2", "teacher")
    res = client.get("/api/attendance/%d" % subj.id, headers=auth(teacher_token))
    assert res.status_code == 200


def test_student_attendance_requires_auth_and_is_scoped(client, db):
    """GET /api/attendance/student/{id} was previously world-readable.
    Students must only see their own attendance."""
    s1 = _add_student(db, "SEC003")
    s2 = _add_student(db, "SEC004")
    subj = _add_subject(db, "SEC5M03")
    _mark(db, s1, subj, "present", datetime.date(2026, 1, 1))
    _mark(db, s2, subj, "absent", datetime.date(2026, 1, 1))

    # unauthenticated
    assert client.get("/api/attendance/student/%d" % s1.id).status_code in (401, 403)

    # student can see own (use the user created by _add_student)
    stu_user = db.query(main.User).filter(main.User.username == "SEC003").first()
    stu_token = main._token_store_create  # not needed; login via token
    # create a token for the student user
    import uuid as _uuid
    from main import _token_store_create
    token = str(_uuid.uuid4())
    _token_store_create(db, token, stu_user.id)

    res = client.get("/api/attendance/student/%d" % s1.id, headers=auth(token))
    assert res.status_code == 200
    assert len(res.json()) == 1

    # student cannot see another student
    res = client.get("/api/attendance/student/%d" % s2.id, headers=auth(token))
    assert res.status_code == 403

    # teacher can see any student
    _, teacher_token = create_user(db, "sec-teach3", "teacher")
    res = client.get("/api/attendance/student/%d" % s2.id, headers=auth(teacher_token))
    assert res.status_code == 200


def test_notifications_require_auth_and_are_scoped(client, db):
    """GET /api/notifications/{user_id} was previously world-readable."""
    _, user_token = create_user(db, "sec-notif", "student")
    other = main.User(username="sec-other", email="sec-other@test.local",
                      full_name="Other", role="student", branch="ISE",
                      hashed_password=main.hash_password("x"))
    db.add(other)
    db.commit()
    db.refresh(other)

    notif = main.Notification(user_id=other.id, title="Test", message="Secret",
                              type="test", read=False,
                              created_at=datetime.datetime.utcnow())
    db.add(notif)
    db.commit()

    # unauthenticated
    assert client.get("/api/notifications/%d" % other.id).status_code in (401, 403)

    # user cannot read another user's notifications
    res = client.get("/api/notifications/%d" % other.id, headers=auth(user_token))
    assert res.status_code == 403

    # admin can read any
    _, admin_token = create_user(db, "sec-admin", "admin")
    res = client.get("/api/notifications/%d" % other.id, headers=auth(admin_token))
    assert res.status_code == 200


def test_cors_is_not_wildcard(client, db):
    """CORS must not allow arbitrary origins."""
    res = client.get("/api/health", headers={"Origin": "http://evil.com"})
    assert res.headers.get("access-control-allow-origin") != "*"
