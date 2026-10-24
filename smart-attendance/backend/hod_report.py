"""
HOD subject & semester comparison report: data building + exports.

Reuses the app-wide rules instead of inventing new ones:
  * pct = present / marked_records * 100 (unknown/late stay in the denominator,
    exactly like student_report and the analytics endpoints).
  * minimum attendance = the configured threshold (student_report.MIN_ATTENDANCE_PCT,
    env MIN_ATTENDANCE_PCT, default 75) - never hardcoded here.
  * only records that actually exist are counted; unmarked or not-yet-held
    classes never become "absent".

Department scoping happens in the endpoint (HOD branch only); this module also
re-checks it so a foreign subject can never leak into a report.

Exports:
  * PDF - the project's dependency-free PDF writer (student_report._PDF).
  * XLSX - openpyxl, already a project dependency.
"""
import io
from datetime import datetime
from typing import List, Optional

from sqlalchemy import func

import student_report
from student_report import MIN_ATTENDANCE_PCT

from main import Student, Subject, AttendanceRecord

MAX_DISTRIBUTION_ROWS = 40   # a chart of 200 columns is unreadable; the table keeps every row


def _pct(present: int, total: int) -> Optional[float]:
    """Attendance percentage, or None when nothing was ever recorded."""
    if not total:
        return None
    return round(present / total * 100, 1)


def _parse_date(value, field):
    if not value:
        return None
    try:
        return datetime.strptime(value, "%Y-%m-%d").date()
    except ValueError:
        raise ValueError("%s must be YYYY-MM-DD" % field)


def build_report(db, user, semester=None, subject_id=None, section=None,
                 start_date=None, end_date=None):
    """Build the HOD comparison report for the caller's own department."""
    branch = (user or {}).get("branch")
    if not branch:
        raise ValueError("Your account is not linked to a department")

    start = _parse_date(start_date, "start_date")
    end = _parse_date(end_date, "end_date")
    if start and end and start > end:
        raise ValueError("start_date must be on or before end_date")

    threshold = MIN_ATTENDANCE_PCT

    # --- subjects in the department, narrowed by the filters ---------------
    subjects_q = db.query(Subject).filter(Subject.branch == branch)
    if semester:
        subjects_q = subjects_q.filter(Subject.semester == semester)
    subjects = subjects_q.order_by(Subject.semester, Subject.code).all()

    if subject_id is not None:
        subjects = [s for s in subjects if s.id == subject_id]
        if not subjects:
            raise PermissionError("That subject is not part of your department")

    subject_ids = [s.id for s in subjects]

    # --- attendance rows in scope -----------------------------------------
    records_q = db.query(AttendanceRecord).filter(
        AttendanceRecord.subject_id.in_(subject_ids)
    ) if subject_ids else db.query(AttendanceRecord).filter(AttendanceRecord.subject_id == -1)
    if start:
        records_q = records_q.filter(AttendanceRecord.date >= start)
    if end:
        records_q = records_q.filter(AttendanceRecord.date <= end)

    per_subject = {sid: {"records": 0, "present": 0, "sections": set()}
                   for sid in subject_ids}
    per_semester = {}
    semester_of = {s.id: s.semester for s in subjects}
    total_records = 0
    total_present = 0

    # Sections live on the student, so both the section filter and the section
    # column come from the student table rather than being guessed from subject.
    rows = records_q.join(Student, AttendanceRecord.student_id == Student.id)
    if section:
        rows = rows.filter(Student.section == section)
    for rec, student in rows.with_entities(AttendanceRecord, Student).all():
        stats = per_subject[rec.subject_id]
        stats["records"] += 1
        if rec.status == "present":
            stats["present"] += 1
        if student.section:
            stats["sections"].add(student.section)

        sem = semester_of.get(rec.subject_id)
        if sem is not None:
            sem_stats = per_semester.setdefault(sem, {"records": 0, "present": 0,
                                                      "subjects": set()})
            sem_stats["records"] += 1
            sem_stats["subjects"].add(rec.subject_id)
            if rec.status == "present":
                sem_stats["present"] += 1

        total_records += 1
        if rec.status == "present":
            total_present += 1

    # --- per-subject rows --------------------------------------------------
    subject_rows = []
    for subj in subjects:
        stats = per_subject.get(subj.id, {"records": 0, "present": 0, "sections": set()})
        pct = _pct(stats["present"], stats["records"])
        subject_rows.append({
            "subject_id": subj.id,
            "code": subj.code,
            "name": subj.name,
            "semester": subj.semester,
            "sections": sorted(stats["sections"]),
            "records": stats["records"],
            "present": stats["present"],
            "absent": stats["records"] - stats["present"],
            "pct": pct,
            "status": ("no_data" if pct is None else
                       "below_threshold" if pct < threshold else "ok"),
        })

    # --- per-semester rows -------------------------------------------------
    semester_rows = []
    for sem in sorted(per_semester):
        stats = per_semester[sem]
        semester_rows.append({
            "semester": sem,
            "subjects": len(stats["subjects"]),
            "records": stats["records"],
            "present": stats["present"],
            "pct": _pct(stats["present"], stats["records"]),
        })

    with_data = [r for r in subject_rows if r["pct"] is not None]
    below = [r for r in with_data if r["pct"] < threshold]

    distribution = [
        {"bucket": "Below 60%", "count": sum(1 for r in with_data if r["pct"] < 60)},
        {"bucket": "60%% to %.0f%%" % threshold,
         "count": sum(1 for r in with_data if 60 <= r["pct"] < threshold)},
        {"bucket": "%.0f%% to 90%%" % threshold,
         "count": sum(1 for r in with_data if threshold <= r["pct"] < 90)},
        {"bucket": "90% and above", "count": sum(1 for r in with_data if r["pct"] >= 90)},
    ]

    summary = {
        "total_subjects": len(subject_rows),
        "subjects_with_records": len(with_data),
        "semesters_represented": len(semester_rows),
        "attendance_pct": _pct(total_present, total_records),
        "subjects_below_threshold": len(below),
        "students": db.query(func.count(func.distinct(Student.id)))
                    .join(AttendanceRecord, AttendanceRecord.student_id == Student.id)
                    .filter(AttendanceRecord.subject_id.in_(subject_ids)).scalar() or 0,
        "total_records": total_records,
        "present_records": total_present,
        "threshold_pct": threshold,
    }
    if section:
        # the students figure must honour the section filter as well
        summary["students"] = db.query(func.count(func.distinct(Student.id))).join(
            AttendanceRecord, AttendanceRecord.student_id == Student.id
        ).filter(
            AttendanceRecord.subject_id.in_(subject_ids),
            Student.section == section,
        ).scalar() or 0

    return {
        "branch": branch,
        "generated_at": datetime.now().strftime("%d %b %Y, %I:%M %p"),
        "filters": {
            "semester": semester,
            "subject_id": subject_id,
            "section": section,
            "start_date": start,
            "end_date": end,
        },
        "summary": summary,
        "subject_rows": subject_rows,
        "semester_rows": semester_rows,
        "distribution": distribution,
        "insights": _insights(summary, subject_rows, semester_rows, with_data, below, threshold),
    }


def _insights(summary, subject_rows, semester_rows, with_data, below, threshold):
    """Short, factual notes derived only from this report's own numbers."""
    notes = []
    pct = summary["attendance_pct"]
    if pct is None:
        return ["No attendance has been recorded for the selected filters yet."]
    notes.append("Department attendance is %.1f%% across %d recorded classes."
                 % (pct, summary["total_records"]))
    if below:
        names = ", ".join("%s (%s)" % (r["code"], r["pct"]) for r in
                          sorted(below, key=lambda r: r["pct"])[:3])
        notes.append("%d of %d subjects are below the %.0f%% threshold - %s%s"
                     % (len(below), len(with_data), threshold, names,
                        " and others" if len(below) > 3 else ""))
    else:
        notes.append("No subject is below the %.0f%% threshold." % threshold)
    ranked = sorted(with_data, key=lambda r: r["pct"])
    if ranked:
        notes.append("Lowest attendance: %s at %.1f%%; highest: %s at %.1f%%."
                     % (ranked[0]["code"], ranked[0]["pct"],
                        ranked[-1]["code"], ranked[-1]["pct"]))
    compared = [r for r in semester_rows if r["pct"] is not None]
    if len(compared) >= 2:
        best = max(compared, key=lambda r: r["pct"])
        worst = min(compared, key=lambda r: r["pct"])
        notes.append("Semester comparison: %s leads at %.1f%%, %s trails at %.1f%%."
                     % (best["semester"], best["pct"], worst["semester"], worst["pct"]))
    elif len(semester_rows) == 1:
        notes.append("Only one semester has records, so a semester comparison is not meaningful yet.")
    no_data = summary["total_subjects"] - summary["subjects_with_records"]
    if no_data:
        notes.append("%d subject(s) in scope have no attendance records and are excluded "
                     "from percentages." % no_data)
    return notes


# --------------------------------------------------------------------------
# PDF export (reuses the project's dependency-free writer)
# --------------------------------------------------------------------------
def render_pdf(report):
    pdf = student_report._PDF()
    w, h = student_report._PDF.WIDTH, student_report._PDF.HEIGHT
    navy, slate = student_report._NAVY, student_report._SLATE
    green, red, amber, grey = (student_report._GREEN, student_report._RED,
                               student_report._AMBER, student_report._GREY)
    m = 48.0
    bottom = 56.0

    pdf.rect(0, h - 92, w, 92, navy)
    pdf.text(m, h - 48, "TrinetraX - Department Comparison Report", 17, True, (1, 1, 1))
    pdf.text(m, h - 70, "%s - generated on %s" % (report["branch"], report["generated_at"]),
             9, False, (0.78, 0.83, 0.90))

    f = report["filters"]
    period = "%s to %s" % (f.get("start_date") or "beginning",
                           f.get("end_date") or "today") if (f.get("start_date") or f.get("end_date")) else "All dates"
    scope = [report["branch"]]
    if f.get("semester"):
        scope.append(f["semester"])
    if f.get("section"):
        scope.append(f["section"])
    pdf.text(m, h - 118, "Scope: %s  |  Period: %s" % (" / ".join(scope), period), 9, False, grey)

    s = report["summary"]
    y = h - 150
    pdf.text(m, y, "Summary", 12.5, True, navy)
    y -= 8
    pdf.line(m, y, w - m, y, 0.7, slate)
    y -= 20
    cards = [
        ("Subjects", str(s["total_subjects"])),
        ("Semesters", str(s["semesters_represented"])),
        ("Attendance", "-" if s["attendance_pct"] is None else "%.1f%%" % s["attendance_pct"]),
        ("Below %.0f%%" % s["threshold_pct"], str(s["subjects_below_threshold"])),
    ]
    for i, (label, value) in enumerate(cards):
        x = m + i * ((w - 2 * m) / len(cards))
        pdf.text(x, y, value, 15, True, slate)
        pdf.text(x, y - 14, label, 8.5, False, grey)

    y -= 40
    for note in report["insights"]:
        pdf.text(m, y, "- %s" % note, 9, False, (0.2, 0.2, 0.2))
        y -= 14

    y -= 10
    cols = [m + 6, m + 96, m + 250, m + 330, w - m - 60]
    header = ["Subject", "Semester", "Sections", "Records", "Attendance"]
    row_h = 15.0

    def new_page():
        nonlocal y
        pdf.add_page()
        y = h - 60
        pdf.text(m, y, "Subject Comparison (continued)", 11, True, navy)
        y -= 8
        pdf.line(m, y, w - m, y, 0.7, slate)
        y -= 18
        _header_row()

    def _header_row():
        nonlocal y
        for c, label in zip(cols, header):
            pdf.text(c, y, label, 8.5, True, grey)
        y -= 12
        pdf.line(m, y, w - m, y, 0.4, slate)
        y -= 12

    _header_row()
    for row in report["subject_rows"]:
        if y < bottom + row_h:
            new_page()
        pct_text = "-" if row["pct"] is None else "%.1f%%" % row["pct"]
        colour = grey if row["pct"] is None else (red if row["status"] == "below_threshold" else green)
        pdf.text(cols[0], y, "%s - %s" % (row["code"], row["name"]), 9)
        pdf.text(cols[1], y, row["semester"] or "-", 9)
        pdf.text(cols[2], y, ", ".join(row["sections"]) or "-", 9)
        pdf.text(cols[3], y, str(row["records"]), 9)
        pdf.text(cols[4], y, pct_text, 9, True, colour)
        y -= row_h
        pdf.line(m, y + 4, w - m, y + 4, 0.2, (0.92, 0.94, 0.96))

    if not report["subject_rows"]:
        pdf.text(m, y, "No subjects match the selected filters.", 9, False, grey)

    return pdf.render()


# --------------------------------------------------------------------------
# Excel export (openpyxl)
# --------------------------------------------------------------------------
def render_xlsx(report):
    from openpyxl import Workbook
    from openpyxl.styles import Font, PatternFill

    wb = Workbook()
    header_font = Font(bold=True, color="FFFFFF")
    header_fill = PatternFill("solid", fgColor="0F172A")
    s = report["summary"]

    ws = wb.active
    ws.title = "Summary"
    ws["A1"] = "TrinetraX - Department Comparison Report"
    ws["A1"].font = Font(bold=True, size=14)
    ws["A2"] = "Department"
    ws["B2"] = report["branch"]
    f = report["filters"]
    ws["A3"] = "Generated on"
    ws["B3"] = report["generated_at"]
    ws["A4"] = "Filters"
    ws["B4"] = "semester=%s, subject_id=%s, section=%s, %s to %s" % (
        f.get("semester") or "all", f.get("subject_id") or "all", f.get("section") or "all",
        f.get("start_date") or "beginning", f.get("end_date") or "today")

    metrics = [
        ("Total subjects", s["total_subjects"]),
        ("Subjects with records", s["subjects_with_records"]),
        ("Semesters represented", s["semesters_represented"]),
        ("Attendance percentage", "n/a" if s["attendance_pct"] is None else s["attendance_pct"]),
        ("Subjects below threshold", s["subjects_below_threshold"]),
        ("Attendance threshold", s["threshold_pct"]),
        ("Students with records", s["students"]),
        ("Recorded classes", s["total_records"]),
        ("Present records", s["present_records"]),
    ]
    for i, (label, value) in enumerate(metrics, start=6):
        ws.cell(row=i, column=1, value=label).font = Font(bold=True)
        ws.cell(row=i, column=2, value=value)

    ws.cell(row=6 + len(metrics) + 1, column=1, value="Insights").font = Font(bold=True)
    for i, note in enumerate(report["insights"]):
        ws.cell(row=7 + len(metrics) + 1 + i, column=1, value=note)

    ws2 = wb.create_sheet("Subjects")
    headers = ["Subject Code", "Subject Name", "Semester", "Sections", "Records",
               "Present", "Absent", "Attendance %", "Status"]
    ws2.append(headers)
    for cell in ws2[1]:
        cell.font = header_font
        cell.fill = header_fill
    for row in report["subject_rows"]:
        ws2.append([
            row["code"], row["name"], row["semester"], ", ".join(row["sections"]),
            row["records"], row["present"], row["absent"],
            "n/a" if row["pct"] is None else row["pct"], row["status"],
        ])
    for col, width in zip("ABCDEFGHI", (16, 32, 16, 20, 10, 10, 10, 14, 18)):
        ws2.column_dimensions[col].width = width

    ws3 = wb.create_sheet("Semesters")
    ws3.append(["Semester", "Subjects with records", "Recorded classes", "Present", "Attendance %"])
    for cell in ws3[1]:
        cell.font = header_font
        cell.fill = header_fill
    for row in report["semester_rows"]:
        ws3.append([row["semester"], row["subjects"], row["records"], row["present"],
                    "n/a" if row["pct"] is None else row["pct"]])

    buffer = io.BytesIO()
    wb.save(buffer)
    return buffer.getvalue()