"""Regression tests: existing functionality keeps working after the upgrade."""
import datetime

import main
import recognition.service as rs
from conftest import auth, create_user


def test_health(client):
    res = client.get("/api/health")
    assert res.status_code == 200
    assert res.json()["status"] == "ok"


def test_me_endpoint_requires_token(client, db):
    _, token = create_user(db, "reg-me", "teacher")
    assert client.get("/api/users/me").status_code in (401, 403)
    res = client.get("/api/users/me", headers=auth(token))
    assert res.status_code == 200
    assert res.json()["username"] == "reg-me"


def test_admin_creates_and_lists_students(client, db):
    _, admin_token = create_user(db, "reg-admin", "admin")
    h = auth(admin_token)
    res = client.post("/api/students", headers=h, json={
        "student_id": "REG001", "name": "Regression One", "roll_number": "REG001",
        "branch": "CSE", "semester": "3rd Semester", "section": "Section A",
        "email": "reg001@test.local",
    })
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["student_id"] == "REG001"
    assert body["password"] == "REG001@26"  # shown once to the admin

    res = client.get("/api/students", headers=h)
    assert res.status_code == 200
    assert any(s["student_id"] == "REG001" for s in res.json())

    # created account can log in with the returned password
    res = client.post("/api/auth/login",
                      json={"username": "REG001", "password": "REG001@26"})
    assert res.status_code == 200

    # student role may not list other-branch data via create/delete
    _, student_token = create_user(db, "reg-stu", "student")
    assert client.delete("/api/students/99999", headers=auth(student_token)).status_code == 403


def test_student_update_and_delete_roundtrip(client, db):
    _, admin_token = create_user(db, "reg-admin2", "admin")
    h = auth(admin_token)
    res = client.post("/api/students", headers=h, json={
        "student_id": "REG002", "name": "Regression Two", "roll_number": "REG002",
        "branch": "ECE", "semester": "5th Semester", "section": "Section B",
        "email": "reg002@test.local",
    })
    assert res.status_code == 200
    student_pk = res.json()["id"]

    res = client.put("/api/students/%d" % student_pk, headers=h,
                     json={"name": "Renamed Two", "password": "newpass1"})
    assert res.status_code == 200
    assert client.post("/api/auth/login",
                       json={"username": "REG002", "password": "newpass1"}).status_code == 200

    res = client.delete("/api/students/%d" % student_pk, headers=h)
    assert res.status_code == 200
    db.expire_all()
    assert db.query(main.Student).filter(main.Student.id == student_pk).first() is None


def test_attendance_capture_manual_results_and_auth(client, db):
    _, admin_token = create_user(db, "reg-admin3", "admin")
    h = auth(admin_token)
    res = client.post("/api/students", headers=h, json={
        "student_id": "REG003", "name": "Regression Three", "roll_number": "REG003",
        "branch": "ISE", "semester": "1st Semester", "section": "Section A",
        "email": "reg003@test.local",
    })
    student_pk = res.json()["id"]
    subject = main.Subject(name="Test Subject", code="TST101", branch="ISE",
                           semester="1st Semester")
    db.add(subject)
    db.commit()

    today = datetime.date.today().isoformat()
    # students must never capture attendance
    _, student_token = create_user(db, "reg-stu3", "student")
    payload = {"subject_id": subject.id, "date": today,
               "student_results": [{"student_id": student_pk, "status": "present",
                                    "confidence": 0.9}]}
    assert client.post("/api/attendance/capture", headers=auth(student_token),
                       json=payload).status_code == 403
    # unauthenticated
    assert client.post("/api/attendance/capture", json=payload).status_code in (401, 403)

    res = client.post("/api/attendance/capture", headers=h, json=payload)
    assert res.status_code == 200, res.text
    assert res.json()["saved"] == 1
    assert res.json()["present"] == 1

    res = client.get("/api/attendance/student/%d" % student_pk, headers=h)
    assert res.status_code == 200
    assert len(res.json()) == 1

    # put/delete need staff auth
    record = db.query(main.AttendanceRecord).filter(
        main.AttendanceRecord.student_id == student_pk).first()
    assert client.put("/api/attendance/%d" % record.id, headers=auth(student_token),
                      json={"status": "absent"}).status_code == 403
    res = client.put("/api/attendance/%d" % record.id, headers=h,
                     json={"status": "absent"})
    assert res.status_code == 200
    db.refresh(record)
    assert record.status == "absent"


def test_capture_with_image_never_records_mock_attendance(client, db, monkeypatch):
    monkeypatch.setattr(main, "get_recognition_service",
                        lambda: rs.MockRecognitionService())
    _, admin_token = create_user(db, "reg-admin4", "admin")
    subject = main.Subject(name="S2", code="TST102", branch="ISE",
                           semester="1st Semester")
    db.add(subject)
    db.commit()
    res = client.post("/api/attendance/capture", headers=auth(admin_token), json={
        "subject_id": subject.id, "date": datetime.date.today().isoformat(),
        "image_base64": "data:image/jpeg;base64,AAAA", "use_recognition": True,
    })
    assert res.status_code == 503
    assert "Refusing to record simulated results" in res.json()["detail"]


def test_analyze_endpoint_requires_staff(client, db):
    assert client.post("/api/recognition/analyze",
                       json={"image_base64": "x"}).status_code in (401, 403)
    _, student_token = create_user(db, "reg-stu-an", "student")
    res = client.post("/api/recognition/analyze", headers=auth(student_token),
                      json={"image_base64": "x"})
    assert res.status_code == 403


def test_subjects_require_admin_or_hod(client, db):
    _, teacher_token = create_user(db, "reg-teach-sub", "teacher")
    _, hod_token = create_user(db, "reg-hod-sub", "hod")
    payload = {"name": "New Sub", "code": "NEW1", "branch": "ISE",
               "semester": "1st Semester"}
    res = client.post("/api/subjects", headers=auth(teacher_token), json=payload)
    assert res.status_code == 403
    res = client.post("/api/subjects", headers=auth(hod_token), json=payload)
    assert res.status_code == 200, res.text
    subject_id = res.json()["id"]
    res = client.delete("/api/subjects/%d" % subject_id, headers=auth(hod_token))
    assert res.status_code == 200


def test_analytics_and_admin_overviews_still_work(client, db):
    _, admin_token = create_user(db, "reg-admin5", "admin")
    h = auth(admin_token)
    for url in ("/api/admin/overview", "/api/analytics/overall",
                "/api/reports/attendance", "/api/admin/departments"):
        res = client.get(url, headers=h)
        assert res.status_code == 200, "%s -> %s %s" % (url, res.status_code, res.text)
    assert client.get("/api/admin/overview").status_code in (401, 403)
