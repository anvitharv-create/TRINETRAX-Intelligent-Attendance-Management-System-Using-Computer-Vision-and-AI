"""Student attendance report: data building + dependency-free PDF export.

Reuses the app-wide rules instead of inventing new ones:
  * pct = present / marked_records * 100   (unknown/late stay in the denominator,
    exactly like _student_pct() and the analytics endpoints)
  * minimum attendance = 75%, the threshold hardcoded across the app, overridable
    with the MIN_ATTENDANCE_PCT environment variable.
  * only records that actually exist are counted - unmarked or not-yet-held
    classes never become "absent".

No new tables, no third-party PDF library (none is installed in this project).
"""
import os
from datetime import datetime
from fractions import Fraction
from typing import Optional

MIN_ATTENDANCE_PCT = float(os.environ.get("MIN_ATTENDANCE_PCT", "75"))


# --------------------------------------------------------------------------
# Data
# --------------------------------------------------------------------------
def build_report(db, student, user=None, start_date=None, end_date=None):
    """Build the student's own attendance report.

    ``student`` is a Student row, ``user`` the matching User dict (optional,
    used for login/profile fields). Only this student's records are ever read.
    """
    if start_date and end_date and start_date > end_date:
        raise ValueError("start_date must be on or before end_date")

    records, summary = _records(db, student.id, start_date, end_date)

    profile = {
        "id": student.id,
        "name": student.name,
        "usn": student.student_id,
        "roll_number": student.roll_number,
        "branch": student.branch,
        "semester": student.semester,
        "section": student.section,
        "email": student.email,
        "profile_photo_path": student.profile_photo_path,
    }
    if user:
        profile["username"] = user.get("username")
        profile["full_name"] = user.get("full_name")
        profile["role"] = user.get("role")

    return {
        "generated_at": datetime.now().strftime("%d %b %Y, %H:%M"),
        "student": profile,
        "scope": "All subjects",
        "filter": {
            "start_date": start_date.isoformat() if start_date else None,
            "end_date": end_date.isoformat() if end_date else None,
        },
        "min_attendance_pct": MIN_ATTENDANCE_PCT,
        "summary": summary,
        "records": records,
    }


def _records(db, student_pk, start_date, end_date):
    from sqlalchemy.orm import aliased  # noqa: F401  (kept local, not required)
    from main import AttendanceRecord, Subject

    query = (
        db.query(AttendanceRecord, Subject)
        .join(Subject, AttendanceRecord.subject_id == Subject.id)
        .filter(AttendanceRecord.student_id == student_pk)
    )
    if start_date:
        query = query.filter(AttendanceRecord.date >= start_date)
    if end_date:
        query = query.filter(AttendanceRecord.date <= end_date)

    # newest first; id breaks same-day ties deterministically
    rows = query.order_by(AttendanceRecord.date.desc(), AttendanceRecord.id.desc()).all()

    records = [
        {
            "id": rec.id,
            "date": rec.date.isoformat(),
            "subject_id": rec.subject_id,
            "subject": subj.name,
            "subject_code": subj.code,
            "status": rec.status,
        }
        for rec, subj in rows
    ]
    return records, _summarise(records)


def _summarise(records):
    total = len(records)
    present = sum(1 for r in records if r["status"] == "present")
    absent = sum(1 for r in records if r["status"] == "absent")
    other = total - present - absent          # unknown / late - not "absent"

    pct = round(present / total * 100, 1) if total else None
    eligible = (pct >= MIN_ATTENDANCE_PCT) if total else None
    if not total:
        shortage = None
        classes_to_attend = None
    elif eligible:
        shortage = 0.0
        classes_to_attend = None
    else:
        shortage = round(MIN_ATTENDANCE_PCT - pct, 1)
        classes_to_attend = _classes_to_attend(present, total)

    return {
        "total_marked": total,
        "present": present,
        "absent": absent,
        "other": other,
        "pct": pct,
        "eligible": eligible,
        "min_attendance_pct": MIN_ATTENDANCE_PCT,
        "shortage_pct": shortage,
        "classes_to_attend": classes_to_attend,
    }


def _classes_to_attend(present, total):
    """Exact number of consecutive classes that must all be attended so that
    (present + n) / (total + n) reaches MIN_ATTENDANCE_PCT.

    Returns None when the figure would be meaningless: nothing marked yet,
    already eligible, or a 100% requirement that can never be reached.
    """
    if total <= 0 or present < 0 or present > total:
        return None
    minimum = Fraction(str(MIN_ATTENDANCE_PCT))
    if minimum >= 100:
        return None
    if Fraction(present, total) * 100 >= minimum:
        return None
    needed = (minimum * total - 100 * present) / (100 - minimum)
    n = needed.__ceil__()
    return n if n > 0 else None


# --------------------------------------------------------------------------
# PDF (minimal PDF 1.4 writer - Helvetica base fonts, no dependencies)
# --------------------------------------------------------------------------
def _esc(text):
    out = []
    for ch in str(text):
        if ch == "\\":
            out.append("\\\\")
        elif ch == "(":
            out.append("\\(")
        elif ch == ")":
            out.append("\\)")
        elif ord(ch) < 256:
            out.append(ch)
        else:
            out.append("?")
    return "".join(out)


def _text_op(x, y, text, size=10, bold=False, color=(0, 0, 0)):
    font = "/F2" if bold else "/F1"
    r, g, b = color
    return (
        "{r:.3f} {g:.3f} {b:.3f} rg BT {font} {size} Tf 1 0 0 1 {x:.2f} {y:.2f} "
        "Tm ({s}) Tj ET"
    ).format(r=r, g=g, b=b, font=font, size=size, x=x, y=y, s=_esc(text))


def _line_op(x1, y1, x2, y2, width=0.5, color=(0.85, 0.88, 0.92)):
    r, g, b = color
    return "{r:.3f} {g:.3f} {b:.3f} RG {w} w {x1:.2f} {y1:.2f} m {x2:.2f} {y2:.2f} l S".format(
        r=r, g=g, b=b, w=width, x1=x1, y1=y1, x2=x2, y2=y2
    )


def _rect_op(x, y, w, h, color):
    r, g, b = color
    return "{r:.3f} {g:.3f} {b:.3f} rg {x:.2f} {y:.2f} {w:.2f} {h:.2f} re f".format(
        r=r, g=g, b=b, x=x, y=y, w=w, h=h
    )


class _PDF:
    WIDTH, HEIGHT = 595.0, 842.0          # A4 in points

    def __init__(self):
        self.pages = [[]]

    @property
    def _ops(self):
        return self.pages[-1]

    def add_page(self):
        self.pages.append([])

    def text(self, x, y, text, size=10, bold=False, color=(0, 0, 0)):
        self._ops.append(_text_op(x, y, text, size, bold, color))

    def line(self, x1, y1, x2, y2, width=0.5, color=(0.85, 0.88, 0.92)):
        self._ops.append(_line_op(x1, y1, x2, y2, width, color))

    def rect(self, x, y, w, h, color):
        self._ops.append(_rect_op(x, y, w, h, color))

    def render(self):
        objs = {}
        page_ids = [5 + 2 * i for i in range(len(self.pages))]
        content_ids = [6 + 2 * i for i in range(len(self.pages))]

        kids = " ".join("%d 0 R" % i for i in page_ids)
        objs[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
        objs[2] = "<< /Type /Pages /Kids [%s] /Count %d >>" % (kids, len(self.pages))
        objs[3] = (b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica "
                   b"/Encoding /WinAnsiEncoding >>")
        objs[4] = (b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold "
                   b"/Encoding /WinAnsiEncoding >>")

        for i, ops in enumerate(self.pages):
            stream = ("\n".join(ops)).encode("latin-1", "replace")
            objs[page_ids[i]] = (
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.0f %.0f] "
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> "
                "/Contents %d 0 R >>" % (self.WIDTH, self.HEIGHT, content_ids[i])
            ).encode("latin-1")
            objs[content_ids[i]] = (
                b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream"
            )

        out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = {}
        for num in sorted(objs):
            offsets[num] = len(out)
            body = objs[num]
            if isinstance(body, str):
                body = body.encode("latin-1")
            out += ("%d 0 obj\n" % num).encode("ascii") + body + b"\nendobj\n"

        xref_pos = len(out)
        size = max(objs) + 1
        out += ("xref\n0 %d\n" % size).encode("ascii")
        out += b"0000000000 65535 f \n"
        for num in range(1, size):
            out += ("%010d 00000 n \n" % offsets[num]).encode("ascii")
        out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n"
                % (size, xref_pos)).encode("ascii")
        return bytes(out)


_NAVY = (0.059, 0.090, 0.165)     # #0f172a
_SLATE = (0.118, 0.227, 0.373)    # #1e3a5f
_GREEN = (0.063, 0.725, 0.506)    # #10b981
_RED = (0.937, 0.267, 0.267)      # #ef4444
_AMBER = (0.961, 0.620, 0.043)    # #f59e0b
_GREY = (0.35, 0.39, 0.45)


def render_pdf(report):
    """Render the exact report shown on screen into a downloadable PDF."""
    pdf = _PDF()
    w, h = _PDF.WIDTH, _PDF.HEIGHT
    m = 48.0
    col_date, col_subj, col_att = m + 6, m + 110, w - m - 90
    bottom = 56.0

    # --- header band ---
    pdf.rect(0, h - 92, w, 92, _NAVY)
    pdf.text(m, h - 48, "TrinetraX - Student Attendance Report", 17, True, (1, 1, 1))
    pdf.text(m, h - 70, "Generated on %s" % report["generated_at"], 9, False,
             (0.78, 0.83, 0.90))

    y = h - 124

    # --- profile ---
    pdf.text(m, y, "Student Profile", 12.5, True, _NAVY)
    y -= 8
    pdf.line(m, y, w - m, y, 0.7, _SLATE)
    y -= 20
    s = report["student"]
    fields = [
        ("Name", s.get("name") or "-"),
        ("USN / Student ID", s.get("usn") or "-"),
        ("Roll number", s.get("roll_number") or "-"),
        ("Branch", s.get("branch") or "-"),
        ("Semester", s.get("semester") or "-"),
        ("Section", s.get("section") or "-"),
        ("Email", s.get("email") or "-"),
        ("Login ID", s.get("username") or "-"),
    ]
    for label, value in fields:
        pdf.text(m, y, "%s:" % label, 9.5, True, _GREY)
        pdf.text(m + 130, y, str(value), 9.5)
        y -= 15

    y -= 14

    # --- summary ---
    pdf.text(m, y, "Attendance Summary", 12.5, True, _NAVY)
    y -= 8
    pdf.line(m, y, w - m, y, 0.7, _SLATE)
    y -= 20

    summary = report["summary"]
    filt = report["filter"]
    period = "All dates"
    if filt.get("start_date") or filt.get("end_date"):
        period = "%s to %s" % (filt.get("start_date") or "beginning",
                               filt.get("end_date") or "today")

    meta = [
        ("Scope", report.get("scope", "All subjects")),
        ("Period", period),
        ("Attendance percentage", "-" if summary["pct"] is None else "%.1f%%" % summary["pct"]),
        ("Classes marked present", str(summary["present"])),
        ("Classes marked absent", str(summary["absent"])),
        ("Total classes marked", str(summary["total_marked"])),
        ("Minimum required", "%.0f%%" % summary["min_attendance_pct"]),
    ]
    for label, value in meta:
        pdf.text(m, y, "%s:" % label, 9.5, True, _GREY)
        pdf.text(m + 130, y, value, 9.5)
        y -= 15

    # eligibility line
    y -= 6
    if summary["eligible"] is None:
        status, tone = "No attendance records yet - eligibility cannot be calculated", _GREY
    elif summary["eligible"]:
        status, tone = ("ELIGIBLE - %.1f%% meets the %.0f%% minimum requirement"
                        % (summary["pct"], summary["min_attendance_pct"]), _GREEN)
    else:
        status = ("NOT ELIGIBLE - %.1f%% is %.1f percentage points below the %.0f%% minimum"
                  % (summary["pct"], summary["shortage_pct"], summary["min_attendance_pct"]))
        tone = _RED
    pdf.rect(m, y - 4, w - 2 * m, 18, (0.97, 0.98, 0.99))
    pdf.text(m + 6, y, status, 9.5, True, tone)
    y -= 20
    if summary.get("classes_to_attend"):
        pdf.text(
            m, y,
            "Attend the next %d class(es) - all present - to reach the %.0f%% requirement."
            % (summary["classes_to_attend"], summary["min_attendance_pct"]),
            9.5, True, _AMBER,
        )
        y -= 16

    # --- history table ---
    y -= 8
    pdf.text(m, y, "Attendance History", 12.5, True, _NAVY)
    y -= 8
    pdf.line(m, y, w - m, y, 0.7, _SLATE)
    y -= 22

    def table_header(y_pos):
        pdf.rect(m, y_pos - 13, w - 2 * m, 17, _NAVY)
        pdf.text(col_date, y_pos - 8, "Date", 9, True, (1, 1, 1))
        pdf.text(col_subj, y_pos - 8, "Subject", 9, True, (1, 1, 1))
        pdf.text(col_att, y_pos - 8, "Attendance", 9, True, (1, 1, 1))
        return y_pos - 24

    y = table_header(y)
    records = report["records"]
    if not records:
        pdf.text(m + 6, y, "No attendance records in this period.", 9.5, False, _GREY)
        y -= 18
    for rec in records:
        if y < bottom + 24:
            pdf.add_page()
            y = h - m
            y = table_header(y)
        label = {"present": "Present", "absent": "Absent"}.get(rec["status"], "Unknown")
        tone = {"present": _GREEN, "absent": _RED}.get(rec["status"], _GREY)
        pdf.text(col_date, y - 9, rec["date"], 9)
        pdf.text(col_subj, y - 9, "%s (%s)" % (rec["subject"], rec["subject_code"]), 9)
        pdf.text(col_att, y - 9, label, 9, True, tone)
        pdf.line(m, y - 15, w - m, y - 15, 0.4)
        y -= 16

    # --- footers ---
    total_pages = len(pdf.pages)
    stamp = "TrinetraX Smart Attendance System"
    for i, ops in enumerate(pdf.pages):
        ops.append(_line_op(m, 40, w - m, 40, 0.5, (0.88, 0.90, 0.93)))
        ops.append(_text_op(m, 28, stamp, 8, False, _GREY))
        ops.append(_text_op(w - m - 70, 28, "Page %d of %d" % (i + 1, total_pages),
                            8, False, _GREY))

    return pdf.render()
