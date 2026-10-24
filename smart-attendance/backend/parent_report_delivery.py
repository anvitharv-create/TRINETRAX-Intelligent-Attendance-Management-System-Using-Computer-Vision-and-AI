"""Parent WhatsApp report delivery.

Inspection of the existing system (done before this module was written):
  * the ``students`` table had NO parent/guardian/phone columns at all,
  * there was NO student-parent relationship table,
  * there was NO WhatsApp provider, SDK, ``.env`` file or credential anywhere,
  * the only messaging primitive is the in-app ``notifications`` table.

Therefore this module adds the minimum needed to make the feature real and
cannot fabricate anything:
  * two nullable columns on ``students`` (``parent_whatsapp`` and
    ``parent_whatsapp_verified``) - every pre-existing row stays NULL, so no
    number or relationship is ever invented,
  * a provider that only activates when the operator supplies credentials via
    environment variables, otherwise every send is truthfully reported as
    ``unconfigured``,
  * the exact same report builder the student portal uses
    (``student_report.build_report`` + ``render_pdf``) - no second set of
    attendance maths.

Every delivery outcome (sent / failed / skipped / unconfigured / duplicate) is
written to the existing ``notifications`` table as the audit trail, so no new
table is introduced.
"""
import json
import os
import re
import threading
import time
import uuid
from datetime import datetime, date
from typing import Optional

import urllib.error
import urllib.request

from env_loader import ensure_loaded

E164_RE = re.compile(r"^\+[1-9]\d{7,14}$")
PROVIDER_NAME = "whatsapp_cloud_api"
MAX_BATCH_SIZE = int(os.environ.get("PARENT_REPORT_MAX_BATCH", "500"))
SEND_DELAY_SECONDS = float(os.environ.get("WHATSAPP_SEND_DELAY_SECONDS", "0.5"))
GRAPH_API_VERSION = os.environ.get("WHATSAPP_API_VERSION", "v20.0")

BATCHES = {}
_BATCHES_LOCK = threading.Lock()
_IN_FLIGHT = set()
_IN_FLIGHT_LOCK = threading.Lock()
_MAX_BATCHES = 50


# --------------------------------------------------------------------------
# Provider configuration - read from the environment, never hard-coded.
# --------------------------------------------------------------------------
def provider_status() -> dict:
    """Report whether a WhatsApp provider is configured, and what is missing."""
    ensure_loaded()
    missing = []
    if not os.environ.get("WHATSAPP_PROVIDER"):
        missing.append("WHATSAPP_PROVIDER")
    elif os.environ.get("WHATSAPP_PROVIDER", "").strip().lower() != PROVIDER_NAME:
        return {
            "configured": False,
            "provider": os.environ.get("WHATSAPP_PROVIDER"),
            "missing": ["WHATSAPP_PROVIDER must be '%s'" % PROVIDER_NAME],
        }
    for key in ("WHATSAPP_TOKEN", "WHATSAPP_PHONE_NUMBER_ID"):
        if not os.environ.get(key):
            missing.append(key)
    return {
        "configured": not missing,
        "provider": PROVIDER_NAME if not missing else None,
        "missing": missing,
        "base_url": "https://graph.facebook.com/%s" % GRAPH_API_VERSION,
    }


def is_configured() -> bool:
    return bool(provider_status()["configured"])


def mask_phone(phone: Optional[str]) -> Optional[str]:
    """Never return a full number to a client or into the audit trail."""
    if not phone:
        return None
    if len(phone) <= 7:
        return "*" * len(phone)
    return phone[:3] + "*" * (len(phone) - 7) + phone[-4:]


def validate_e164(phone: Optional[str]) -> bool:
    return bool(phone) and bool(E164_RE.match(phone.strip()))


# --------------------------------------------------------------------------
# Senders
# --------------------------------------------------------------------------
class SendOutcome:
    SENT = "sent"
    FAILED = "failed"
    UNCONFIGURED = "unconfigured"
    SKIPPED = "skipped"
    DUPLICATE = "duplicate"

    def __init__(self, outcome: str, detail: str = ""):
        self.outcome = outcome
        self.detail = detail

    @property
    def ok(self) -> bool:
        return self.outcome == self.SENT


class UnconfiguredSender:
    """Default sender. Refuses honestly instead of pretending to send."""

    name = "unconfigured"

    def send_document(self, phone: str, pdf_bytes: bytes, filename: str) -> SendOutcome:
        status = provider_status()
        missing = ", ".join(status["missing"]) or "provider credentials"
        return SendOutcome(
            SendOutcome.UNCONFIGURED,
            "WhatsApp delivery is not configured on this server (missing: %s)." % missing,
        )


class DryRunSender:
    """Records what would be sent without any network call. Used by tests."""

    name = "dry_run"

    def __init__(self, fail_for=None):
        self.sent = []
        self.fail_for = set(fail_for or [])

    def send_document(self, phone: str, pdf_bytes: bytes, filename: str) -> SendOutcome:
        if phone in self.fail_for:
            return SendOutcome(SendOutcome.FAILED, "Provider rejected the message (dry run).")
        if not pdf_bytes:
            return SendOutcome(SendOutcome.FAILED, "Empty document.")
        self.sent.append({"phone": phone, "filename": filename, "size": len(pdf_bytes)})
        return SendOutcome(SendOutcome.SENT, "Dry run - nothing left this machine.")


class WhatsAppCloudApiSender:
    """Meta WhatsApp Business Cloud API (document message).

    The PDF is uploaded as private media and sent by media id, so the report
    is never hosted on a public URL. Two calls per recipient, stdlib only.
    """

    name = PROVIDER_NAME

    def __init__(self, token: str, phone_number_id: str, base_url: Optional[str] = None):
        self._token = token
        self._phone_number_id = phone_number_id
        self._base = base_url or ("https://graph.facebook.com/%s" % GRAPH_API_VERSION)

    # isolated so tests can stub the network without a provider account
    def _request(self, path: str, data: bytes, content_type: str) -> tuple:
        req = urllib.request.Request(
            self._base + path,
            data=data,
            method="POST",
            headers={
                "Authorization": "Bearer %s" % self._token,
                "Content-Type": content_type,
            },
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")

    def _upload_media(self, pdf_bytes: bytes, filename: str) -> str:
        boundary = "----trinetrax%s" % uuid.uuid4().hex
        parts = []
        for name, value in (("messaging_product", "whatsapp"),
                            ("type", "file")):
            parts.append(
                ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                 % (boundary, name, value)).encode("utf-8")
            )
        parts.append(
            ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
             "Content-Type: application/pdf\r\n\r\n" % (boundary, filename)).encode("utf-8")
        )
        parts.append(pdf_bytes)
        parts.append(("\r\n--%s--\r\n" % boundary).encode("utf-8"))
        status, body = self._request(
            "/%s/media" % self._phone_number_id,
            b"".join(parts),
            "multipart/form-data; boundary=%s" % boundary,
        )
        if status >= 300:
            raise RuntimeError("media upload failed (%s): %s" % (status, body[:200]))
        return json.loads(body)["id"]

    def send_document(self, phone: str, pdf_bytes: bytes, filename: str) -> SendOutcome:
        to = phone.strip().lstrip("+")
        try:
            media_id = self._upload_media(pdf_bytes, filename)
            payload = json.dumps({
                "messaging_product": "whatsapp",
                "to": to,
                "type": "document",
                "document": {
                    "id": media_id,
                    "filename": filename,
                    "sender_name": "TrinetraX Smart Attendance",
                },
            }).encode("utf-8")
            status, body = self._request(
                "/%s/messages" % self._phone_number_id, payload, "application/json"
            )
            if status >= 300:
                return SendOutcome(
                    SendOutcome.FAILED, "Provider rejected the message (%s): %s"
                    % (status, body[:200])
                )
            return SendOutcome(SendOutcome.SENT, "Accepted by WhatsApp Cloud API.")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:200] if hasattr(exc, "read") else str(exc)
            return SendOutcome(SendOutcome.FAILED, "HTTP %s: %s" % (exc.code, detail))
        except Exception as exc:
            return SendOutcome(SendOutcome.FAILED, "%s: %s" % (type(exc).__name__, exc))


def get_sender(channel: str = "whatsapp"):
    """Return a real sender for the channel, or the honest stub."""
    if channel == "email":
        status = email_status()
        if not status["configured"]:
            return UnconfiguredSender()
        return EmailSender(
            host=os.environ["SMTP_HOST"].strip(),
            port=int(os.environ.get("SMTP_PORT", "587")),
            username=os.environ.get("SMTP_USERNAME", "").strip(),
            password=os.environ.get("SMTP_PASSWORD", "").strip(),
            use_tls=os.environ.get("SMTP_USE_TLS", "1").strip() not in ("0", "false", "no"),
            sender_addr=(os.environ.get("SMTP_FROM") or os.environ.get("SMTP_USERNAME", "")).strip(),
            sender_name=os.environ.get("EMAIL_SENDER_NAME", "TrinetraX Smart Attendance").strip(),
        )
    status = provider_status()
    if not status["configured"]:
        return UnconfiguredSender()
    return WhatsAppCloudApiSender(
        token=os.environ["WHATSAPP_TOKEN"].strip(),
        phone_number_id=os.environ["WHATSAPP_PHONE_NUMBER_ID"].strip(),
    )


# --------------------------------------------------------------------------
# Email channel - stdlib smtplib only, no new dependency.
# --------------------------------------------------------------------------
def email_status() -> dict:
    """Report whether an SMTP provider is configured, and what is missing."""
    ensure_loaded()
    if not os.environ.get("EMAIL_PROVIDER"):
        return {"configured": False, "provider": None, "missing": ["EMAIL_PROVIDER"]}
    if os.environ.get("EMAIL_PROVIDER", "").strip().lower() != "smtp":
        return {"configured": False, "provider": os.environ.get("EMAIL_PROVIDER"),
                "missing": ["EMAIL_PROVIDER must be 'smtp'"]}
    missing = []
    for key in ("SMTP_HOST", "SMTP_FROM"):
        if not os.environ.get(key):
            missing.append(key)
    # a username/password pair is required by every real SMTP relay
    if not os.environ.get("SMTP_USERNAME") or not os.environ.get("SMTP_PASSWORD"):
        missing.append("SMTP_USERNAME / SMTP_PASSWORD")
    return {
        "configured": not missing,
        "provider": "smtp" if not missing else None,
        "missing": missing,
        "host": os.environ.get("SMTP_HOST"),
    }


EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$")


def validate_email(address) -> bool:
    return bool(address) and bool(EMAIL_RE.match(address.strip()))


# Domains are checked once per process; a hostname that resolves can receive
# mail (RFC 5321 treats a missing MX as an implicit MX to the A/AAAA record),
# while a hostname that does not resolve at all can never do so.
_DOMAIN_MAIL_CACHE = {}

# Never publicly routable, so a lookup would always fail for them.
_OPAQUE_SUFFIXES = (
    "local", "localhost", "internal", "intranet", "lan",
    "test", "invalid", "example",
)


def domain_check_enabled() -> bool:
    # operator config may not be in the process yet on a fresh worker
    ensure_loaded()
    return os.environ.get("EMAIL_DOMAIN_CHECK", "").strip().lower() in (
        "1", "true", "yes", "on",
    )


def domain_accepts_mail(domain) -> bool:
    """True unless the recipient domain has no host record at all.

    Opt-in through ``EMAIL_DOMAIN_CHECK=1`` so offline installs and the test
    suite keep their current behaviour, and so a DNS hiccup can never block a
    genuine send: any lookup failure falls open to "deliverable".

    Special-use suffixes (RFC 2606 / 6762, plus ``.local``) are never public
    mail domains, so they are trusted as operator-internal addresses and are
    not looked up.
    """
    if not domain_check_enabled():
        return True
    name = (domain or "").strip().lower().strip(".")
    if not name:
        return False
    if any(name.endswith("." + suffix) for suffix in _OPAQUE_SUFFIXES):
        return True
    cached = _DOMAIN_MAIL_CACHE.get(name)
    if cached is not None:
        return cached
    try:
        import socket

        socket.getaddrinfo(name, None)
        ok = True
    except socket.gaierror:
        ok = False
    except Exception:
        ok = True  # never block a real send on an unexpected resolver problem
    _DOMAIN_MAIL_CACHE[name] = ok
    return ok


def email_domain_ok(address) -> bool:
    if not address or "@" not in address:
        return False
    return domain_accepts_mail(address.rsplit("@", 1)[1])


def mask_email(address):
    """Hide the local part of an address in UI and audit rows."""
    if not address or "@" not in address:
        return None
    local, _, domain = address.partition("@")
    if len(local) <= 2:
        head = local[:1]
    else:
        head = local[0] + "*" * (len(local) - 2) + local[-1]
    return "%s@%s" % (head, domain)


class EmailSender:
    """Sends the PDF as a real email attachment over SMTP."""

    name = "smtp"

    def __init__(self, host, port, username, password, use_tls, sender_addr, sender_name):
        self._host = host
        self._port = port
        self._username = username
        self._password = password
        self._use_tls = use_tls
        self._from = sender_addr
        self._from_name = sender_name

    # isolated so tests can stub the network without a mail account
    def _send(self, msg):
        import smtplib
        with smtplib.SMTP(self._host, self._port, timeout=30) as server:
            if self._use_tls:
                server.starttls()
            if self._username:
                server.login(self._username, self._password)
            server.send_message(msg)

    def send_document(self, address: str, pdf_bytes: bytes, filename: str) -> SendOutcome:
        from email.mime.application import MIMEApplication
        from email.mime.multipart import MIMEMultipart
        from email.mime.text import MIMEText
        from email.utils import formataddr

        to = (address or "").strip()
        if not validate_email(to):
            return SendOutcome(SendOutcome.FAILED, "Recipient is not a valid email address.")
        if not pdf_bytes:
            return SendOutcome(SendOutcome.FAILED, "Empty document.")

        msg = MIMEMultipart()
        msg["From"] = formataddr((self._from_name, self._from))
        msg["To"] = to
        msg["Subject"] = "Student Attendance Report"
        body = MIMEText(
            "Hello,\n\nThe student's attendance report is attached as a PDF.\n\n"
            "- TrinetraX Smart Attendance\n", "plain",
        )
        msg.attach(body)
        attachment = MIMEApplication(pdf_bytes, _subtype="pdf", name=filename)
        attachment.add_header("Content-Disposition", "attachment", filename=filename)
        msg.attach(attachment)
        try:
            self._send(msg)
            return SendOutcome(SendOutcome.SENT, "Email accepted for delivery by %s." % self._host)
        except Exception as exc:
            return SendOutcome(SendOutcome.FAILED, "%s: %s" % (type(exc).__name__, exc))


# --------------------------------------------------------------------------
# Recipient resolution - scoped to the teacher's own subjects only.
# --------------------------------------------------------------------------
def teacher_classes(db, teacher):
    """Distinct (branch, semester) pairs the teacher is assigned to teach."""
    from main import Subject
    subjects = db.query(Subject).filter(Subject.teacher_id == teacher.id).all()
    return {(s.branch, s.semester) for s in subjects if s.branch and s.semester}


def authorized_students(db, teacher):
    """Every active student in the teacher's own classes, USN ascending."""
    from main import Student
    pairs = teacher_classes(db, teacher)
    if not pairs:
        return []
    query = db.query(Student).filter(Student.is_active == True)  # noqa: E712
    conditions = []
    for branch, semester in pairs:
        conditions.append((Student.branch == branch) & (Student.semester == semester))
    from sqlalchemy import or_
    return query.filter(or_(*conditions)).order_by(Student.student_id).all()


def student_contact(student, channel: str = "whatsapp") -> dict:
    """Resolve and validate this student's recipient for the chosen channel.

    Email falls back to the student's own registered college address when no
    parent email is on file - that is real data that already exists, and the
    UI states plainly who each report will go to. A parent email that IS on
    file but unverified is never silently swapped for another address.
    """
    if channel == "email":
        parent = (getattr(student, "parent_email", None) or "").strip() or None
        verified = bool(getattr(student, "parent_email_verified", False))
        if parent:
            if not verified:
                reason, deliverable = "unverified_contact", False
            elif not validate_email(parent):
                reason, deliverable = "invalid_address", False
            elif not email_domain_ok(parent):
                reason, deliverable = "undeliverable_domain", False
            else:
                reason, deliverable = None, True
            return {"address": parent, "masked": mask_email(parent), "verified": verified,
                    "deliverable": deliverable, "reason": reason, "via": "parent"}
        own = (student.email or "").strip() or None
        deliverable = bool(own) and validate_email(own)
        reason = None if deliverable else "no_valid_address"
        if deliverable and not email_domain_ok(own):
            deliverable = False
            reason = "undeliverable_domain"
        return {"address": own, "masked": mask_email(own), "verified": True,
                "deliverable": deliverable,
                "reason": reason,
                "via": "student"}

    phone = (student.parent_whatsapp or "").strip() or None
    verified = bool(student.parent_whatsapp_verified)
    deliverable = bool(phone) and verified and validate_e164(phone)
    if not phone:
        reason = "no_parent_contact"
    elif not verified:
        reason = "unverified_contact"
    elif not validate_e164(phone):
        reason = "invalid_number"
    else:
        reason = None
    return {
        "address": phone,
        "phone": phone,
        "masked": mask_phone(phone),
        "verified": verified,
        "deliverable": deliverable,
        "reason": reason,
        "via": "parent" if phone else None,
    }


# --------------------------------------------------------------------------
# Delivery - one student at a time, always validated, always audited.
# --------------------------------------------------------------------------
def _already_sent_today(db, student_pk, period_key, channel) -> bool:
    from main import Notification
    rows = db.query(Notification).filter(
        Notification.type == "parent_report_delivery",
        Notification.message.like("%%\"student_id\": %d,%%" % student_pk),
        Notification.message.like("%%\"period\": \"%s\",%%" % period_key),
        Notification.message.like("%%\"channel\": \"%s\",%%" % channel),
        Notification.message.like("%%\"outcome\": \"sent\"%%"),
        Notification.message.like("%%\"day\": \"%s\"%%" % date.today().isoformat()),
    ).all()
    return bool(rows)


def _audit(db, teacher_user_id: int, student, contact: dict, period_key: str,
           outcome: str, detail: str, filename: str, channel: str):
    from main import Notification
    record = {
        "student_id": student.id,
        "usn": student.student_id,
        "phone": contact.get("masked"),
        "recipient": contact.get("masked"),
        "channel": channel,
        "period": period_key,
        "outcome": outcome,
        "detail": detail,
        "filename": filename,
        "day": date.today().isoformat(),
        "at": datetime.utcnow().isoformat(),
    }
    db.add(Notification(
        user_id=teacher_user_id,
        title="Parent report delivery",
        message=json.dumps(record, separators=(",", ": ")),
        type="parent_report_delivery",
        read=False,
        created_at=datetime.utcnow(),
    ))
    db.commit()


def deliver_to_student(db, teacher_user_id: int, teacher, student, sender,
                       start_date=None, end_date=None, dry_run: bool = False,
                       channel: str = "whatsapp") -> dict:
    """Build this student's official report and deliver it on the channel.

    Never raises for an expected failure - every case returns a truthful
    outcome so a batch can never report a false success.
    """
    import student_report

    period_key = "%s..%s" % (start_date.isoformat() if start_date else "*",
                             end_date.isoformat() if end_date else "*")
    filename = "attendance_report_%s.pdf" % (student.student_id or student.id)
    contact = student_contact(student, channel)
    result = {
        "student_id": student.id,
        "usn": student.student_id,
        "name": student.name,
        "phone_masked": contact["masked"],
        "recipient_masked": contact["masked"],
        "recipient_via": contact.get("via"),
        "channel": channel,
        "filename": filename,
        "outcome": None,
        "detail": "",
    }

    def finish(outcome, detail):
        result["outcome"] = outcome
        result["detail"] = detail
        if not dry_run:
            try:
                _audit(db, teacher_user_id, student, contact, period_key,
                       outcome, detail, filename, channel)
            except Exception as exc:
                result["detail"] = "%s (audit write failed: %s)" % (detail, exc)
        return result

    if not contact["deliverable"]:
        reason_text = {
            "no_parent_contact": "No parent contact is on file for this student.",
            "no_valid_address": "No usable email address is on file for this student.",
            "unverified_contact": "A parent contact is on file but is not marked verified.",
            "invalid_number": "The stored parent number is not a valid E.164 number.",
            "invalid_address": "The stored parent email is not a valid address.",
            "undeliverable_domain": (
                "The stored address's domain has no mail server, so no message "
                "could ever arrive."
            ),
        }.get(contact["reason"], "Parent contact could not be verified.")
        return finish(SendOutcome.SKIPPED, reason_text)

    if _already_sent_today(db, student.id, period_key, channel):
        return finish(SendOutcome.DUPLICATE,
                      "An identical report was already delivered on this channel today.")

    if dry_run:
        return finish(SendOutcome.SENT, "Dry run - validated and would be delivered.")

    with _IN_FLIGHT_LOCK:
        if student.id in _IN_FLIGHT:
            return finish(SendOutcome.DUPLICATE, "A delivery for this student is already in progress.")
        _IN_FLIGHT.add(student.id)
    try:
        try:
            report = student_report.build_report(db, student, None, start_date, end_date)
            pdf_bytes = student_report.render_pdf(report)
        except Exception as exc:
            return finish(SendOutcome.FAILED, "PDF generation failed: %s" % exc)
        if not pdf_bytes:
            return finish(SendOutcome.FAILED, "PDF generation produced an empty document.")
        outcome = sender.send_document(contact["address"], pdf_bytes, filename)
        return finish(outcome.outcome, outcome.detail)
    finally:
        with _IN_FLIGHT_LOCK:
            _IN_FLIGHT.discard(student.id)


# --------------------------------------------------------------------------
# Batches - background processing so a large Send All stays responsive.
# --------------------------------------------------------------------------
def start_batch(db, teacher, teacher_user_id: int, student_ids, sender=None,
                start_date=None, end_date=None, dry_run: bool = False,
                channel: str = "whatsapp") -> dict:
    from main import Student

    authorized = {s.id: s for s in authorized_students(db, teacher)}
    if not student_ids:
        student_ids = list(authorized.keys())
    student_ids = [int(i) for i in student_ids]
    if len(student_ids) > MAX_BATCH_SIZE:
        raise ValueError("A batch is limited to %d students." % MAX_BATCH_SIZE)

    rejected = [i for i in student_ids if i not in authorized]
    if rejected:
        raise ValueError(
            "%d selected student(s) are outside your authorized scope." % len(rejected)
        )

    ordered = [authorized[i] for i in student_ids]
    snapshot = [{
        "student_id": s.id,
        "usn": s.student_id,
        "name": s.name,
        "contact": student_contact(s, channel),
    } for s in ordered]

    batch_id = uuid.uuid4().hex
    batch = {
        "id": batch_id,
        "teacher_user_id": teacher_user_id,
        "channel": channel,
        "created_at": datetime.utcnow().isoformat(),
        "status": "running",
        "total": len(ordered),
        "processed": 0,
        "dry_run": dry_run,
        "results": [],
        "summary": {k: 0 for k in (SendOutcome.SENT, SendOutcome.FAILED,
                                   SendOutcome.SKIPPED, SendOutcome.UNCONFIGURED,
                                   SendOutcome.DUPLICATE)},
        "started_at": None,
        "finished_at": None,
    }
    with _BATCHES_LOCK:
        BATCHES[batch_id] = batch
        if len(BATCHES) > _MAX_BATCHES:
            oldest = sorted(BATCHES.items(), key=lambda kv: kv[1]["created_at"])
            for key, _ in oldest[: len(BATCHES) - _MAX_BATCHES]:
                BATCHES.pop(key, None)

    thread = threading.Thread(
        target=_run_batch, args=(batch_id, teacher, teacher_user_id, ordered,
                                 sender, start_date, end_date, dry_run, channel),
        daemon=True,
    )
    thread.start()
    return {"batch_id": batch_id, "total": len(ordered), "channel": channel,
            "already_delivered": sum(1 for s in snapshot if s["contact"]["deliverable"]),
            "without_contact": sum(1 for s in snapshot if not s["contact"]["deliverable"])}


def _run_batch(batch_id, teacher, teacher_user_id, students, sender,
               start_date, end_date, dry_run, channel="whatsapp"):
    from main import SessionLocal
    db = SessionLocal()
    sender = sender or get_sender(channel)
    batch = BATCHES.get(batch_id)
    try:
        batch["started_at"] = datetime.utcnow().isoformat()
        for student in students:
            res = deliver_to_student(db, teacher_user_id, teacher, student, sender,
                                     start_date, end_date, dry_run, channel)
            batch["results"].append(res)
            batch["summary"][res["outcome"]] = batch["summary"].get(res["outcome"], 0) + 1
            batch["processed"] += 1
            if sender.name in (PROVIDER_NAME, "smtp") and res["outcome"] == SendOutcome.SENT:
                time.sleep(SEND_DELAY_SECONDS)
    except Exception as exc:
        batch["error"] = "%s: %s" % (type(exc).__name__, exc)
    finally:
        batch["status"] = "done"
        batch["finished_at"] = datetime.utcnow().isoformat()
        db.close()


def get_batch(batch_id: str, teacher_user_id: int) -> Optional[dict]:
    batch = BATCHES.get(batch_id)
    if not batch or batch["teacher_user_id"] != teacher_user_id:
        return None
    return batch
