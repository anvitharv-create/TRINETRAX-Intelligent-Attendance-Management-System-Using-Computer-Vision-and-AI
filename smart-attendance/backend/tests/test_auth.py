"""Authentication, password storage and route-protection tests."""
import main
from conftest import auth, create_user


def test_login_success_returns_token(client, db):
    create_user(db, "alice", "student", password="s3cret!")
    # drop the fixture token - login must issue its own
    res = client.post("/api/auth/login", json={"username": "alice", "password": "s3cret!"})
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["access_token"]
    assert body["user"]["username"] == "alice"
    assert "password" not in body["user"] and "hashed_password" not in body["user"]


def test_login_wrong_password(client, db):
    create_user(db, "bob", "student", password="right")
    res = client.post("/api/auth/login", json={"username": "bob", "password": "wrong"})
    assert res.status_code == 401


def test_registered_password_is_stored_as_pbkdf2_hash(client, db):
    res = client.post("/api/auth/register/student", json={
        "name": "Hash Check", "branch": "ISE", "semester": "1st Semester",
        "section": "Section A",
    })
    assert res.status_code == 200, res.text
    sid = res.json()["student_id"]
    plain = res.json()["password"]  # returned once for display, never stored
    user = db.query(main.User).filter(main.User.username == sid).first()
    assert main.is_hashed_password(user.hashed_password)
    assert user.hashed_password != plain
    assert main.verify_password(plain, user.hashed_password)
    assert not main.verify_password("nope", user.hashed_password)
    # login with the displayed password still works
    assert client.post("/api/auth/login",
                       json={"username": sid, "password": plain}).status_code == 200


def test_legacy_plaintext_row_logs_in_and_gets_upgraded(client, db):
    user = main.User(username="legacy", email="legacy@test.local", full_name="Legacy",
                     role="teacher", branch="ISE", hashed_password="oldpass")
    db.add(user)
    db.commit()
    res = client.post("/api/auth/login", json={"username": "legacy", "password": "oldpass"})
    assert res.status_code == 200, res.text
    db.refresh(user)
    assert main.is_hashed_password(user.hashed_password)
    assert main.verify_password("oldpass", user.hashed_password)


def test_password_helpers_roundtrip():
    h = main.hash_password("hunter2")
    assert main.is_hashed_password(h)
    assert main.verify_password("hunter2", h)
    assert not main.verify_password("hunter3", h)
    assert main.verify_password("plain", "plain")  # legacy fallback
    assert not main.verify_password("other", "plain")
    assert not main.verify_password("x", None)
    assert not main.verify_password("x", "pbkdf2_sha256$broken")


def test_credentials_endpoint_no_longer_leaks_passwords(client, db):
    # unauthenticated -> rejected
    assert client.get("/api/auth/credentials").status_code in (401, 403)
    # student -> forbidden
    _, student_token = create_user(db, "stu1", "student")
    assert client.get("/api/auth/credentials", headers=auth(student_token)).status_code == 403
    # admin -> allowed, but never sees a password field
    _, admin_token = create_user(db, "boss", "admin")
    res = client.get("/api/auth/credentials", headers=auth(admin_token))
    assert res.status_code == 200
    for row in res.json()["credentials"]:
        assert "password" not in row
        assert "hashed_password" not in row


def test_mutating_routes_require_auth(client, db):
    _, student_token = create_user(db, "mut-stu", "student")
    _, hod_token = create_user(db, "mut-hod", "hod")

    checks = [
        ("put", "/api/students/1", {"name": "x"}),
        ("delete", "/api/students/1", None),
        ("put", "/api/teachers/1", {"name": "x"}),
        ("delete", "/api/teachers/1", None),
        ("post", "/api/subjects", {"name": "n", "code": "c", "branch": "ISE",
                                   "semester": "1st Semester"}),
        ("delete", "/api/subjects/1", None),
        ("put", "/api/attendance/1", {"status": "present"}),
        ("delete", "/api/attendance/1", None),
            ("post", "/api/branch-semester-section", {"branch": "ISE",
                                                     "semester": "1st Semester",
                                                     "section": "Section Z"}),
        ("post", "/api/recognition/detect", {"image_base64": "x"}),
        ("post", "/api/faces/bulk/preview", None),
    ]
    for method, url, payload in checks:
        call = getattr(client, method)
        # no token at all
        res = call(url, json=payload) if payload is not None else call(url)
        assert res.status_code in (401, 403), "%s %s unauthenticated -> %s" % (
            method, url, res.status_code)


def test_role_checks_block_students_from_staff_routes(client, db):
    _, student_token = create_user(db, "role-stu", "student")
    h = auth(student_token)
    res = client.put("/api/students/9999", json={"name": "x"}, headers=h)
    assert res.status_code == 403
    res = client.delete("/api/subjects/9999", headers=h)
    assert res.status_code == 403
    res = client.post("/api/faces/bulk/preview", headers=h)
    assert res.status_code == 403
    # HOD may delete subjects but not teachers
    _, hod_token = create_user(db, "role-hod", "hod")
    res = client.delete("/api/teachers/9999", headers=auth(hod_token))
    assert res.status_code in (403, 404)
    assert client.delete("/api/teachers/9999", headers=h).status_code == 403


def test_notification_read_is_owner_or_admin_only(client, db):
    owner, owner_token = create_user(db, "notif-owner", "student")
    other, other_token = create_user(db, "notif-other", "student")
    _, admin_token = create_user(db, "notif-admin", "admin")
    n = main.Notification(user_id=owner.id, title="t", message="m", type="info")
    db.add(n)
    db.commit()

    res = client.put("/api/notifications/%d/read" % n.id, headers=auth(other_token))
    assert res.status_code == 403
    res = client.put("/api/notifications/%d/read" % n.id, headers=auth(owner_token))
    assert res.status_code == 200
    # admin can mark someone else's as read (fixture already flipped it; use a 2nd)
    n2 = main.Notification(user_id=owner.id, title="t2", message="m2", type="info")
    db.add(n2)
    db.commit()
    res = client.put("/api/notifications/%d/read" % n2.id, headers=auth(admin_token))
    assert res.status_code == 200


def test_startup_migration_hashes_legacy_rows(client, db):
    """One-time migration: plaintext -> PBKDF2, backup taken, idempotent."""
    legacy = main.User(username="legacy-mig", email="legacy-mig@test.local",
                       full_name="Legacy", role="student", branch="ISE",
                       hashed_password="legacy-plain-pw")
    db.add(legacy)
    db.commit()
    already, _ = create_user(db, "mig-already", "teacher")
    before = db.query(main.User).filter(main.User.id == already.id).one().hashed_password
    assert main.is_hashed_password(before)

    main._migrate_plaintext_passwords()
    db.expire_all()

    legacy_row = db.query(main.User).filter(
        main.User.username == "legacy-mig").one()
    assert main.is_hashed_password(legacy_row.hashed_password)
    assert main.verify_password("legacy-plain-pw", legacy_row.hashed_password)
    # already-hashed row must be byte-identical after the migration
    after = db.query(main.User).filter(main.User.id == already.id).one()
    assert after.hashed_password == before

    # second run changes nothing
    main._migrate_plaintext_passwords()
    db.expire_all()
    again = db.query(main.User).filter(
        main.User.username == "legacy-mig").one()
    assert again.hashed_password == legacy_row.hashed_password
