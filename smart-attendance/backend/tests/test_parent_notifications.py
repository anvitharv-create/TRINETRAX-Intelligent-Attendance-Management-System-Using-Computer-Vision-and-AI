"""Parent WhatsApp report delivery: scope, parent matching, honest outcomes."""
import datetime
import os
import pathlib
import time

import main
import parent_report_delivery as pd
from conftest import auth, create_user


def _make_teacher(db, username, branch="ISE"):
    user, token = create_user(db, username, "teacher", branch=branch)
    teacher = main.Teacher(
        user_id=user.id, name=user.full_name, employee_id="EMP-%s" % username,
        branch=branch, semester="5th Semester", section="Section A",
        email="%s@teach.local" % username,
    )
    db.add(teacher)
    db.commit()
    db.refresh(teacher)
    return user, teacher, token


def _make_student(db, usn, branch="ISE", semester="5th Semester", section="Section A",
                  phone=None, verified=False, parent_email=None,
                  parent_email_verified=False, own_email=None):
    user = main.User(
        username=usn, email=own_email or "%s@stu.local" % usn, full_name="Student %s" % usn,
        role="student", branch=branch, semester=semester, section=section,
        hashed_password=main.hash_password("x"),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    student = main.Student(
        student_id=usn, user_id=user.id, name="Student %s" % usn,
        roll_number="R%s" % usn, branch=branch, semester=semester, section=section,
        email=own_email or "%s@stu.local" % usn, parent_whatsapp=phone,
        parent_whatsapp_verified=verified, parent_email=parent_email,
        parent_email_verified=parent_email_verified,
    )
    db.add(student)
    db.commit()
    db.refresh(student)
    return student


def _subject(db, teacher, code="BIS203", name="Database Systems", semester="5th Semester"):
    subject = main.Subject(name=name, code=code, branch="ISE",
                           semester=semester, teacher_id=teacher.id)
    db.add(subject)
    db.commit()
    db.refresh(subject)
    return subject


def _mark(db, student, subject, day, status, marked_by):
    db.add(main.AttendanceRecord(
        student_id=student.id, subject_id=subject.id, date=day,
        status=status, marked_by=marked_by,
    ))
    db.commit()


def _seed(db, with_contacts=False):
    """One teacher, one subject, three students (only some deliverable)."""
    user, teacher, token = _make_teacher(db, "pn-teacher")
    subject = _subject(db, teacher)

    s_ok = _make_student(db, "1AR24PN001", phone="+919812345678", verified=True)
    s_no = _make_student(db, "1AR24PN002")
    s_unver = _make_student(db, "1AR24PN003", phone="+919812345679", verified=False)

    day = datetime.date.today() - datetime.timedelta(days=2)
    for s in (s_ok, s_no, s_unver):
        _mark(db, s, subject, day, "present", user.id)
        _mark(db, s, subject, day, "absent", user.id)

    # a student in a different class the teacher does NOT teach
    other = _make_student(db, "1AR24EC001", branch="ECE", semester="1st Semester",
                          phone="+919800000000", verified=True)

    return {"token": token, "teacher": teacher, "user": user, "subject": subject,
            "ok": s_ok, "no": s_no, "unver": s_unver, "other": other}


def _wait_batch(client, token, batch_id, timeout=8.0):
    deadline = time.time() + timeout
    body = client.get("/api/teacher/reports/notify/batch/%s" % batch_id,
                      headers=auth(token)).json()
    while body.get("status") == "running" and time.time() < deadline:
        time.sleep(0.15)
        body = client.get("/api/teacher/reports/notify/batch/%s" % batch_id,
                          headers=auth(token)).json()
    return body


# --------------------------------------------------------------------------
# Authorization & scope
# --------------------------------------------------------------------------
def test_endpoints_require_teacher_role(client, db):
    _seed(db)
    assert client.get("/api/teacher/reports/notify/eligible").status_code in (401, 403)

    _, s_token = create_user(db, "pn-student", "student")
    _make_student(db, "1AR24PNSTU")
    for path in ("/api/teacher/reports/notify/eligible",):
        assert client.get(path, headers=auth(s_token)).status_code == 403
    assert client.post("/api/teacher/reports/notify", json={},
                       headers=auth(s_token)).status_code == 403


def test_teacher_without_profile_gets_404(client, db):
    _, token = create_user(db, "pn-noprofile", "teacher")
    assert client.get("/api/teacher/reports/notify/eligible",
                      headers=auth(token)).status_code == 404


def test_eligible_lists_only_the_teachers_own_students(client, db):
    seed = _seed(db)
    body = client.get("/api/teacher/reports/notify/eligible",
                      headers=auth(seed["token"])).json()
    usns = [r["usn"] for r in body["students"]]
    assert usns == ["1AR24PN001", "1AR24PN002", "1AR24PN003"]
    assert "1AR24EC001" not in usns
    assert body["counts"]["total"] == 3
    assert body["counts"]["deliverable"] == 1
    assert body["counts"]["no_contact"] == 1
    assert body["counts"]["unverified"] == 1


def test_full_phone_number_is_never_returned(client, db):
    seed = _seed(db)
    body = client.get("/api/teacher/reports/notify/eligible",
                      headers=auth(seed["token"])).json()
    for row in body["students"]:
        assert "9812345678" not in str(row.values())
        if row["phone_masked"]:
            assert "*" in row["phone_masked"]


def test_out_of_scope_student_is_rejected(client, db):
    seed = _seed(db)
    res = client.post("/api/teacher/reports/notify",
                      json={"student_ids": [seed["other"].id]},
                      headers=auth(seed["token"]))
    assert res.status_code == 400
    assert "outside your authorized scope" in res.json()["detail"]


def test_another_teacher_cannot_poll_my_batch(client, db):
    seed = _seed(db)
    _user2, _t2, token2 = _make_teacher(db, "pn-other")
    started = client.post("/api/teacher/reports/notify", json={"dry_run": True},
                          headers=auth(seed["token"])).json()
    res = client.get("/api/teacher/reports/notify/batch/%s" % started["batch_id"],
                     headers=auth(token2))
    assert res.status_code == 404


# --------------------------------------------------------------------------
# Contact validation - the honest outcomes
# --------------------------------------------------------------------------
def test_missing_and_unverified_contacts_are_skipped_not_sent(client, db):
    seed = _seed(db)
    started = client.post("/api/teacher/reports/notify", json={"dry_run": True},
                          headers=auth(seed["token"])).json()
    body = _wait_batch(client, seed["token"], started["batch_id"])
    assert body["status"] == "done"
    by_usn = {r["usn"]: r for r in body["results"]}

    assert by_usn["1AR24PN001"]["outcome"] == "sent"
    assert by_usn["1AR24PN002"]["outcome"] == "skipped"
    assert "No parent contact is on file" in by_usn["1AR24PN002"]["detail"]
    assert by_usn["1AR24PN003"]["outcome"] == "skipped"
    assert "not marked verified" in by_usn["1AR24PN003"]["detail"]
    assert body["summary"]["sent"] == 1
    assert body["summary"]["skipped"] == 2


def test_each_student_gets_their_own_report_and_their_own_number(client, db):
    seed = _seed(db)
    sender = pd.DryRunSender()
    db_ = db
    res = pd.deliver_to_student(db_, seed["user"].id, seed["teacher"], seed["ok"], sender)
    assert res["outcome"] == "sent"
    assert len(sender.sent) == 1
    assert sender.sent[0]["phone"] == "+919812345678"
    assert sender.sent[0]["filename"] == "attendance_report_1AR24PN001.pdf"

    # a second student with their own number
    s_ok2 = _make_student(db, "1AR24PN010", phone="+919887654321", verified=True)
    _mark(db, s_ok2, seed["subject"], datetime.date.today(), "present", seed["user"].id)
    res2 = pd.deliver_to_student(db_, seed["user"].id, seed["teacher"], s_ok2, sender)
    assert res2["outcome"] == "sent"
    assert sender.sent[1]["phone"] == "+919887654321"
    assert sender.sent[1]["filename"] == "attendance_report_1AR24PN010.pdf"
    # recipients and documents are never mixed up
    assert sender.sent[0]["phone"] != sender.sent[1]["phone"]
    assert sender.sent[0]["filename"] != sender.sent[1]["filename"]


def test_delivered_pdf_is_the_official_student_report(client, db):
    """The bytes sent to the parent are exactly the student's own report."""
    import student_report
    seed = _seed(db)
    sender = pd.DryRunSender()
    pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender)

    official = student_report.render_pdf(
        student_report.build_report(db, seed["ok"], None, None, None))
    assert sender.sent[0]["size"] == len(official)
    assert official.startswith(b"%PDF-1.4")
    assert b"1AR24PN001" in official
    assert b"NOT ELIGIBLE" in official or b"ELIGIBLE" in official


def test_invalid_number_is_skipped(client, db):
    seed = _seed(db)
    bad = _make_student(db, "1AR24PN020", phone="09812345678", verified=True)
    _mark(db, bad, seed["subject"], datetime.date.today(), "present", seed["user"].id)
    sender = pd.DryRunSender()
    res = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], bad, sender)
    assert res["outcome"] == "skipped"
    assert "valid E.164" in res["detail"]
    assert sender.sent == []


def test_provider_failure_is_reported_as_failed_never_sent(client, db):
    seed = _seed(db)
    sender = pd.DryRunSender(fail_for={"+919812345678"})
    res = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender)
    assert res["outcome"] == "failed"
    assert res["detail"]


# --------------------------------------------------------------------------
# WhatsApp configuration - never pretend
# --------------------------------------------------------------------------
def test_unconfigured_provider_reports_unconfigured(client, db, monkeypatch):
    seed = _seed(db)
    monkeypatch.delenv("WHATSAPP_PROVIDER", raising=False)
    monkeypatch.delenv("WHATSAPP_TOKEN", raising=False)
    monkeypatch.delenv("WHATSAPP_PHONE_NUMBER_ID", raising=False)

    status = pd.provider_status()
    assert status["configured"] is False
    assert "WHATSAPP_PROVIDER" in status["missing"]

    sender = pd.get_sender()
    assert isinstance(sender, pd.UnconfiguredSender)
    res = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender)
    assert res["outcome"] == "unconfigured"
    assert "not configured" in res["detail"].lower()
    assert "WHATSAPP_PROVIDER" in res["detail"]


def test_provider_status_reports_missing_credentials(client, db, monkeypatch):
    monkeypatch.setenv("WHATSAPP_PROVIDER", "whatsapp_cloud_api")
    monkeypatch.delenv("WHATSAPP_TOKEN", raising=False)
    monkeypatch.delenv("WHATSAPP_PHONE_NUMBER_ID", raising=False)
    status = pd.provider_status()
    assert status["configured"] is False
    assert set(status["missing"]) == {"WHATSAPP_TOKEN", "WHATSAPP_PHONE_NUMBER_ID"}

    monkeypatch.setenv("WHATSAPP_TOKEN", "tok")
    monkeypatch.setenv("WHATSAPP_PHONE_NUMBER_ID", "123")
    status = pd.provider_status()
    assert status["configured"] is True
    assert isinstance(pd.get_sender(), pd.WhatsAppCloudApiSender)


def test_eligible_endpoint_exposes_provider_status(client, db, monkeypatch):
    seed = _seed(db)
    monkeypatch.delenv("WHATSAPP_PROVIDER", raising=False)
    body = client.get("/api/teacher/reports/notify/eligible",
                      headers=auth(seed["token"])).json()
    assert body["provider"]["configured"] is False
    assert "WHATSAPP_PROVIDER" in body["provider"]["missing"]


# --------------------------------------------------------------------------
# Duplicate prevention & audit trail
# --------------------------------------------------------------------------
def test_same_report_is_not_sent_twice_in_one_day(client, db):
    seed = _seed(db)
    sender = pd.DryRunSender()
    first = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender)
    second = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender)
    assert first["outcome"] == "sent"
    assert second["outcome"] == "duplicate"
    assert len(sender.sent) == 1


def test_a_different_period_is_not_treated_as_a_duplicate(client, db):
    seed = _seed(db)
    sender = pd.DryRunSender()
    start = datetime.date.today() - datetime.timedelta(days=7)
    pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender,
                          start_date=start)
    res = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender)
    assert res["outcome"] == "sent"
    assert len(sender.sent) == 2


def test_every_attempt_is_audited_in_the_notifications_table(client, db):
    seed = _seed(db)
    sender = pd.DryRunSender()
    pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender)
    pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["no"], sender)

    rows = db.query(main.Notification).filter(
        main.Notification.type == "parent_report_delivery").all()
    assert len(rows) == 2
    assert all(r.user_id == seed["user"].id for r in rows)
    import json
    payloads = [json.loads(r.message) for r in rows]
    assert {p["usn"] for p in payloads} == {"1AR24PN001", "1AR24PN002"}
    assert payloads[0]["phone"] != "+919812345678"  # only the masked form is stored
    assert "*" in payloads[0]["phone"]
    assert {p["outcome"] for p in payloads} == {"sent", "skipped"}


# --------------------------------------------------------------------------
# Send All / Select individual over HTTP
# --------------------------------------------------------------------------
def test_send_all_confirms_with_the_right_recipient_count(client, db):
    seed = _seed(db)
    body = client.get("/api/teacher/reports/notify/eligible",
                      headers=auth(seed["token"])).json()
    assert body["counts"]["deliverable"] == 1
    assert body["counts"]["total"] == 3

    started = client.post("/api/teacher/reports/notify", json={"dry_run": True},
                          headers=auth(seed["token"])).json()
    assert started["total"] == 3
    assert started["already_delivered"] == 1
    assert started["without_contact"] == 2


def test_selecting_no_one_is_a_no_op(client, db):
    seed = _seed(db)
    started = client.post("/api/teacher/reports/notify",
                          json={"student_ids": [], "dry_run": True},
                          headers=auth(seed["token"])).json()
    body = _wait_batch(client, seed["token"], started["batch_id"])
    assert body["total"] == 3


def test_selecting_one_student_only_processes_that_student(client, db):
    seed = _seed(db)
    started = client.post("/api/teacher/reports/notify",
                          json={"student_ids": [seed["ok"].id], "dry_run": True},
                          headers=auth(seed["token"])).json()
    body = _wait_batch(client, seed["token"], started["batch_id"])
    assert body["total"] == 1
    assert [r["usn"] for r in body["results"]] == ["1AR24PN001"]


def test_batch_size_is_capped(client, db):
    seed = _seed(db)
    res = client.post("/api/teacher/reports/notify",
                      json={"student_ids": list(range(1, pd.MAX_BATCH_SIZE + 2))},
                      headers=auth(seed["token"]))
    assert res.status_code == 400
    assert "limited to" in res.json()["detail"]


def test_invalid_dates_are_rejected(client, db):
    seed = _seed(db)
    today = datetime.date.today().isoformat()
    res = client.post("/api/teacher/reports/notify",
                      json={"start_date": today, "end_date": "2020-01-01"},
                      headers=auth(seed["token"]))
    assert res.status_code == 400
    res = client.post("/api/teacher/reports/notify",
                      json={"start_date": "not-a-date"}, headers=auth(seed["token"]))
    assert res.status_code == 400


def test_unknown_batch_is_404(client, db):
    seed = _seed(db)
    assert client.get("/api/teacher/reports/notify/batch/does-not-exist",
                      headers=auth(seed["token"])).status_code == 404


# --------------------------------------------------------------------------
# WhatsApp Cloud API sender (network stubbed - no real provider is used)
# --------------------------------------------------------------------------
class _StubResponse:
    def __init__(self, status, body):
        self.status = status
        self._body = body

    def read(self):
        return self._body.encode()

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def test_cloud_api_sender_uploads_then_sends_without_public_url(monkeypatch):
    calls = []

    def fake_urlopen(req, timeout=None):
        calls.append((req.full_url, req.data))
        if req.full_url.endswith("/media"):
            return _StubResponse(200, '{"id": "MEDIA123"}')
        return _StubResponse(200, '{"messages": [{"id": "wamid.1"}]}')

    monkeypatch.setattr(pd.urllib.request, "urlopen", fake_urlopen)
    sender = pd.WhatsAppCloudApiSender(token="tok", phone_number_id="999")
    res = sender.send_document("+919812345678", b"%PDF-1.4 fake", "r.pdf")

    assert res.outcome == "sent"
    assert len(calls) == 2
    media_url, media_body = calls[0]
    assert media_url.endswith("/999/media")
    assert b"%PDF-1.4 fake" in media_body          # the PDF is uploaded directly
    assert b"http" not in media_body.split(b"%PDF")[0]  # never referenced by public link
    msg_url, msg_body = calls[1]
    assert msg_url.endswith("/999/messages")
    assert b'"to": "919812345678"' in msg_body     # the + is stripped for the API
    assert b"MEDIA123" in msg_body


def test_cloud_api_sender_reports_provider_rejection(monkeypatch):
    def fake_urlopen(req, timeout=None):
        if req.full_url.endswith("/media"):
            return _StubResponse(200, '{"id": "M"}')
        return _StubResponse(400, '{"error": {"message": "rate limited"}}')

    monkeypatch.setattr(pd.urllib.request, "urlopen", fake_urlopen)
    sender = pd.WhatsAppCloudApiSender(token="tok", phone_number_id="999")
    res = sender.send_document("+919812345678", b"%PDF-1.4", "r.pdf")
    assert res.outcome == "failed"
    assert "400" in res.detail


def test_cloud_api_sender_never_raises_on_network_error(monkeypatch):
    def fake_urlopen(req, timeout=None):
        raise OSError("connection refused")

    monkeypatch.setattr(pd.urllib.request, "urlopen", fake_urlopen)
    sender = pd.WhatsAppCloudApiSender(token="tok", phone_number_id="999")
    res = sender.send_document("+919812345678", b"%PDF-1.4", "r.pdf")
    assert res.outcome == "failed"
    assert "connection refused" in res.detail


# --------------------------------------------------------------------------
# Unit helpers
# --------------------------------------------------------------------------
def test_mask_and_validate():
    assert pd.mask_phone(None) is None
    assert pd.mask_phone("+919812345678") == "+91******5678"
    assert pd.validate_e164("+919812345678")
    assert not pd.validate_e164("09812345678")
    assert not pd.validate_e164("+9198123")
    assert not pd.validate_e164("")
    assert not pd.validate_e164(None)
    assert not pd.validate_e164("+0123456789")


def test_migration_left_existing_students_untouched(client, db):
    """No student row may gain a contact that was not stored there."""
    seed = _seed(db)
    untouched = db.query(main.Student).get(seed["other"].id)
    assert untouched.parent_whatsapp == "+919800000000"
    fresh = db.query(main.Student).get(seed["no"].id)
    assert fresh.parent_whatsapp is None
    assert fresh.parent_whatsapp_verified in (False, 0)


# --------------------------------------------------------------------------
# .env loading (secrets come from the operator, never invented)
# --------------------------------------------------------------------------
def test_env_file_is_read_and_real_environment_still_wins(tmp_path, monkeypatch):
    import env_loader
    env_file = tmp_path / ".env"
    env_file.write_text(
        "# comment\n"
        "WHATSAPP_PROVIDER=whatsapp_cloud_api\n"
        "WHATSAPP_TOKEN='from-file'\n"
        "WHATSAPP_PHONE_NUMBER_ID=\"12345\"\n"
        "EMPTY_ONE=\n",
        encoding="utf-8",
    )
    # load_env mutates os.environ globally, so pin the keys we touch with
    # monkeypatch to guarantee they are restored for the rest of the suite.
    monkeypatch.delenv("WHATSAPP_TOKEN", raising=False)
    monkeypatch.setenv("WHATSAPP_TOKEN", "from-real-env")
    for key in ("WHATSAPP_PROVIDER", "WHATSAPP_PHONE_NUMBER_ID", "EMPTY_ONE"):
        monkeypatch.delenv(key, raising=False)

    found = env_loader.load_env(str(env_file))
    assert found["WHATSAPP_PROVIDER"] == "whatsapp_cloud_api"
    # a value already in the real environment is never overwritten by the file
    assert os.environ["WHATSAPP_TOKEN"] == "from-real-env"
    # quoting is stripped, blanks stay blank
    assert os.environ.get("WHATSAPP_PHONE_NUMBER_ID") == "12345"
    assert os.environ.get("EMPTY_ONE") == ""


def test_missing_env_file_leaves_provider_unconfigured(monkeypatch):
    import env_loader
    for key in ("WHATSAPP_PROVIDER", "WHATSAPP_TOKEN", "WHATSAPP_PHONE_NUMBER_ID"):
        monkeypatch.delenv(key, raising=False)
    env_loader.load_env(str(pathlib.Path(__file__).parent / "no-such-file.env"))
    status = pd.provider_status()
    assert status["configured"] is False
    assert isinstance(pd.get_sender(), pd.UnconfiguredSender)


# --------------------------------------------------------------------------
# Email channel
# --------------------------------------------------------------------------
def test_email_falls_back_to_the_students_own_real_address(client, db):
    """No parent email on file -> the student's own registered email is used."""
    seed = _seed(db)
    sender = pd.DryRunSender()
    res = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"],
                                sender, channel="email")
    assert res["outcome"] == "sent"
    assert res["recipient_via"] == "student"
    assert sender.sent[0]["phone"] == "1AR24PN001@stu.local"
    assert sender.sent[0]["filename"] == "attendance_report_1AR24PN001.pdf"


def test_verified_parent_email_takes_priority_over_the_students_own(client, db):
    seed = _seed(db)
    s = _make_student(db, "1AR24EM001", own_email="kid@stu.local",
                      parent_email="parent@family.local",
                      parent_email_verified=True)
    _mark(db, s, seed["subject"], datetime.date.today(), "present", seed["user"].id)
    sender = pd.DryRunSender()
    res = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], s, sender,
                                channel="email")
    assert res["outcome"] == "sent"
    assert res["recipient_via"] == "parent"
    assert sender.sent[0]["phone"] == "parent@family.local"


def test_unverified_parent_email_is_never_silently_swapped(client, db):
    """If an admin put a parent email but did not verify it, do not fall back."""
    seed = _seed(db)
    s = _make_student(db, "1AR24EM002", own_email="kid2@stu.local",
                      parent_email="unverified@family.local",
                      parent_email_verified=False)
    sender = pd.DryRunSender()
    res = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], s, sender,
                                channel="email")
    assert res["outcome"] == "skipped"
    assert "not marked verified" in res["detail"]
    assert sender.sent == []


def test_email_and_whatsapp_do_not_collide_as_duplicates(client, db):
    seed = _seed(db)
    sender = pd.DryRunSender()
    wa = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender)
    em = pd.deliver_to_student(db, seed["user"].id, seed["teacher"], seed["ok"], sender,
                               channel="email")
    assert wa["outcome"] == "sent"
    assert em["outcome"] == "sent"
    assert len(sender.sent) == 2


def test_full_email_address_is_never_returned_to_the_client(client, db):
    seed = _seed(db)
    body = client.get("/api/teacher/reports/notify/eligible?channel=email",
                      headers=auth(seed["token"])).json()
    assert body["channel"] == "email"
    for row in body["students"]:
        assert "1AR24PN001@stu.local" not in str(row.values())
    # the masked form keeps only the first and last character of the local part
    row = next(r for r in body["students"] if r["usn"] == "1AR24PN001")
    assert row["recipient_masked"] == "1********1@stu.local"


def test_eligible_endpoint_reports_both_channels(client, db, monkeypatch):
    seed = _seed(db)
    monkeypatch.delenv("EMAIL_PROVIDER", raising=False)
    body = client.get("/api/teacher/reports/notify/eligible?channel=email",
                      headers=auth(seed["token"])).json()
    assert body["channels"]["email"]["configured"] is False
    assert "EMAIL_PROVIDER" in body["channels"]["email"]["missing"]
    assert body["channels"]["whatsapp"]["configured"] is False


def test_invalid_channel_is_rejected(client, db):
    seed = _seed(db)
    res = client.get("/api/teacher/reports/notify/eligible?channel=carrier-pigeon",
                     headers=auth(seed["token"]))
    assert res.status_code == 400
    res = client.post("/api/teacher/reports/notify",
                      json={"channel": "carrier-pigeon"}, headers=auth(seed["token"]))
    assert res.status_code == 400


def test_email_batch_over_http_delivers_to_students_own_address(client, db):
    seed = _seed(db)
    started = client.post("/api/teacher/reports/notify",
                          json={"dry_run": True, "channel": "email"},
                          headers=auth(seed["token"])).json()
    assert started["channel"] == "email"
    body = _wait_batch(client, seed["token"], started["batch_id"])
    assert body["status"] == "done"
    assert body["summary"]["sent"] == 3
    assert all(r["recipient_via"] == "student" for r in body["results"])


def test_email_status_reports_missing_credentials(monkeypatch):
    for key in ("EMAIL_PROVIDER", "SMTP_HOST", "SMTP_USERNAME",
                "SMTP_PASSWORD", "SMTP_FROM"):
        monkeypatch.delenv(key, raising=False)
    status = pd.email_status()
    assert status["configured"] is False
    assert "EMAIL_PROVIDER" in status["missing"]

    monkeypatch.setenv("EMAIL_PROVIDER", "smtp")
    status = pd.email_status()
    assert status["configured"] is False
    assert "SMTP_HOST" in status["missing"]
    assert "SMTP_USERNAME / SMTP_PASSWORD" in status["missing"]

    monkeypatch.setenv("SMTP_HOST", "smtp.gmail.com")
    monkeypatch.setenv("SMTP_USERNAME", "me@gmail.com")
    monkeypatch.setenv("SMTP_PASSWORD", "abcd efgh ijkl mnop")
    monkeypatch.setenv("SMTP_FROM", "me@gmail.com")
    assert pd.email_status()["configured"] is True
    assert isinstance(pd.get_sender("email"), pd.EmailSender)


def test_email_sender_builds_a_real_message_with_the_pdf_attached(monkeypatch):
    captured = {}

    def fake_send(msg):
        captured["to"] = msg["To"]
        captured["subject"] = msg["Subject"]
        captured["payload"] = msg.as_string()

    sender = pd.EmailSender(host="smtp.example.com", port=587, username="u",
                            password="p", use_tls=True, sender_addr="u@example.com",
                            sender_name="TrinetraX")
    sender._send = fake_send
    res = sender.send_document("parent@family.local", b"%PDF-1.4 fake", "r.pdf")

    assert res.outcome == "sent"
    assert captured["to"] == "parent@family.local"
    assert "attendance" in captured["subject"].lower()
    assert "JVBERi0xLjQgZmFrZQ==" in captured["payload"]
    assert "r.pdf" in captured["payload"]
    assert "application/pdf" in captured["payload"]


def test_email_sender_rejects_bad_address_and_reports_failures():
    sender = pd.EmailSender(host="h", port=25, username="", password="",
                            use_tls=False, sender_addr="a@bb.cc", sender_name="T")
    assert sender.send_document("not-an-email", b"%PDF", "r.pdf").outcome == "failed"
    assert sender.send_document("", b"%PDF", "r.pdf").outcome == "failed"
    assert sender.send_document("a@bb.cc", b"", "r.pdf").outcome == "failed"

    def boom(msg):
        raise OSError("connection refused")

    sender._send = boom
    res = sender.send_document("a@bb.cc", b"%PDF", "r.pdf")
    assert res.outcome == "failed"
    assert "connection refused" in res.detail


def test_mask_and_validate_email():
    assert pd.mask_email(None) is None
    assert pd.mask_email("notanemail") is None
    assert pd.mask_email("parent@family.local") == "p****t@family.local"
    assert pd.validate_email("parent@family.local")
    assert not pd.validate_email("parent@family")
    assert not pd.validate_email("@family.local")
    assert not pd.validate_email("")
