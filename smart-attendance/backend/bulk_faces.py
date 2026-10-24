"""
Bulk face enrollment helpers: Excel roster + ZIP of photos.

Pure functions only (no DB access) so they can be unit tested without a
server. main.py wires them to the Student model and the recognition service.

Matching rules
--------------
* Photo file names are matched to the roster's Student ID / USN column:
    1AR23IS006.jpg, 1AR23IS006.png, 1AR23IS006_front.jpg, 1AR23IS006-2.jpeg
  all map to USN "1AR23IS006" (case-insensitive, first separator ends the id).
* Anything that does not map to a roster row is reported as unmatched.
* Roster rows without a photo are reported as missing.
* Duplicate USNs inside the roster are rejected (first occurrence kept).
"""

import base64
import io
import os
import re
import zipfile
from typing import Dict, List, Optional, Tuple

MAX_ROSTER_BYTES = 5 * 1024 * 1024          # 5 MB
MAX_ZIP_BYTES = 100 * 1024 * 1024           # 100 MB
MAX_PHOTOS = 5000
MAX_UNCOMPRESSED_BYTES = 300 * 1024 * 1024  # zip-bomb guard

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

# Header aliases (normalised: lowercase, alphanumeric only)
ID_HEADERS = {"studentid", "usn", "studentusn", "usnoid", "rollno",
              "rollnumber", "studentno", "admissionno", "id"}
NAME_HEADERS = {"name", "studentname", "fullname"}
BRANCH_HEADERS = {"branch", "department", "dept", "branchcode"}
SEMESTER_HEADERS = {"semester", "sem"}
SECTION_HEADERS = {"section", "sec", "batch", "div", "division"}
EMAIL_HEADERS = {"email", "emailid", "mail", "emailaddress"}


class BulkError(ValueError):
    """Raised for a file-level problem that aborts the whole import."""


def _norm_header(value) -> str:
    return re.sub(r"[^a-z0-9]", "", str(value or "").strip().lower())


def parse_roster(data: bytes) -> Dict:
    """
    Parse an .xlsx student roster.

    Returns {"columns": [...], "rows": [{"student_id","name",...}], "errors": [...]}
    Rows without a student id are reported in errors and excluded from rows.
    """
    if not data:
        raise BulkError("Roster file is empty")
    if len(data) > MAX_ROSTER_BYTES:
        raise BulkError("Roster file is larger than %d MB" % (MAX_ROSTER_BYTES // (1024 * 1024)))

    try:
        import openpyxl
    except ImportError as exc:  # pragma: no cover
        raise BulkError("openpyxl is not installed on the server (%s)" % exc)

    try:
        wb = openpyxl.load_workbook(io.BytesIO(data), read_only=True, data_only=True)
    except Exception as exc:
        raise BulkError("Could not read the Excel file: %s" % exc)

    try:
        sheet = wb.active
        if sheet is None:
            raise BulkError("The Excel file has no worksheets")
        rows_iter = sheet.iter_rows(values_only=True)
        header = None
        header_row_no = 0
        for idx, row in enumerate(rows_iter, start=1):
            if row and any(c is not None and str(c).strip() for c in row):
                header = row
                header_row_no = idx
                break
        if header is None:
            raise BulkError("The Excel file is empty")

        mapping = {}
        for pos, cell in enumerate(header):
            key = _norm_header(cell)
            if key in ID_HEADERS and "student_id" not in mapping:
                mapping["student_id"] = pos
            elif key in NAME_HEADERS and "name" not in mapping:
                mapping["name"] = pos
            elif key in BRANCH_HEADERS and "branch" not in mapping:
                mapping["branch"] = pos
            elif key in SEMESTER_HEADERS and "semester" not in mapping:
                mapping["semester"] = pos
            elif key in SECTION_HEADERS and "section" not in mapping:
                mapping["section"] = pos
            elif key in EMAIL_HEADERS and "email" not in mapping:
                mapping["email"] = pos
        if "student_id" not in mapping:
            raise BulkError(
                "No Student ID/USN column found. Expected a header like "
                "'USN', 'Student ID' or 'Roll No'. Found: %s"
                % ", ".join(str(c) for c in header if c is not None)
            )

        def cell(row, key):
            pos = mapping.get(key)
            if pos is None or pos >= len(row):
                return None
            val = row[pos]
            if val is None:
                return None
            if isinstance(val, float) and val.is_integer():
                val = int(val)
            text = str(val).strip()
            return text or None

        rows, errors = [], []
        seen = {}
        for excel_row, row in enumerate(rows_iter, start=header_row_no + 1):
            if row is None or not any(c is not None and str(c).strip() for c in row):
                continue
            sid = cell(row, "student_id")
            name = cell(row, "name")
            if not sid:
                errors.append({"row": excel_row, "error": "missing student id / USN"})
                continue
            if sid in seen:
                errors.append({"row": excel_row, "error": "duplicate student id %s (first seen on row %d)"
                               % (sid, seen[sid]), "student_id": sid})
                continue
            seen[sid] = excel_row
            rows.append({
                "student_id": sid,
                "name": name,
                "branch": cell(row, "branch"),
                "semester": cell(row, "semester"),
                "section": cell(row, "section"),
                "email": cell(row, "email"),
                "row": excel_row,
            })
        return {"columns": [str(c) if c is not None else "" for c in header],
                "rows": rows, "errors": errors}
    finally:
        try:
            wb.close()
        except Exception:
            pass


def read_photo_zip(data: bytes) -> Dict:
    """
    Safely read image entries out of a ZIP archive.

    Returns {"photos": {filename: bytes}, "errors": [...]}.
    Rejects absolute paths, parent traversal, oversized archives and
    non-image entries (which are reported, not fatal).
    """
    if not data:
        raise BulkError("Photo ZIP file is empty")
    if len(data) > MAX_ZIP_BYTES:
        raise BulkError("Photo ZIP is larger than %d MB" % (MAX_ZIP_BYTES // (1024 * 1024)))

    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        raise BulkError("The uploaded file is not a valid ZIP archive")

    photos, errors = {}, []
    total = 0
    count = 0
    with zf:
        for info in zf.infolist():
            name = info.filename
            if name.endswith("/"):
                continue
            if name.startswith("/") or re.match(r"^[A-Za-z]:", name) or ".." in name.split("/"):
                errors.append({"file": name, "error": "unsafe path in archive"})
                continue
            ext = os.path.splitext(name)[1].lower()
            if ext not in IMAGE_EXTENSIONS:
                errors.append({"file": os.path.basename(name), "error": "not a supported image type"})
                continue
            total += info.file_size
            if total > MAX_UNCOMPRESSED_BYTES:
                raise BulkError("ZIP contents exceed %d MB uncompressed" % (MAX_UNCOMPRESSED_BYTES // (1024 * 1024)))
            count += 1
            if count > MAX_PHOTOS:
                raise BulkError("ZIP contains more than %d images" % MAX_PHOTOS)
            try:
                payload = zf.read(info)
            except Exception as exc:
                errors.append({"file": os.path.basename(name), "error": "could not be read: %s" % exc})
                continue
            if not payload:
                errors.append({"file": os.path.basename(name), "error": "file is empty"})
                continue
            photos[name] = payload
    if not photos:
        raise BulkError("The ZIP archive contains no usable images")
    return {"photos": photos, "errors": errors}


def student_id_from_filename(filename: str) -> Optional[str]:
    """'abc/1AR23IS006_front.jpg' -> '1AR23IS006'. Returns None when the stem
    does not start with something that looks like a student id."""
    stem = os.path.splitext(os.path.basename(filename))[0].strip()
    if not stem:
        return None
    # split on the first separator that follows an alnum id
    m = re.match(r"^([A-Za-z0-9]+)(?:[ _\-.].*)?$", stem)
    if not m:
        return None
    return m.group(1)


def match_photos(rows: List[dict], photo_names: List[str]) -> Dict:
    """
    Map ZIP file names onto roster rows.

    Returns {"assigned": {student_id: filename}, "unmatched": [filename,...],
             "missing": [student_id,...], "duplicates": {student_id: [file,...]}}
    """
    by_id: Dict[str, List[str]] = {}
    for fname in photo_names:
        sid = student_id_from_filename(fname)
        if not sid:
            continue
        by_id.setdefault(sid.lower(), []).append(fname)

    assigned, duplicates = {}, {}
    for row in rows:
        sid = str(row["student_id"]).strip()
        candidates = sorted(by_id.get(sid.lower(), []))
        if not candidates:
            continue
        assigned[sid] = candidates[0]
        if len(candidates) > 1:
            duplicates[sid] = candidates

    roster_ids = {str(r["student_id"]).strip().lower() for r in rows}
    unmatched = sorted(
        f for f in photo_names
        if (student_id_from_filename(f) or "").lower() not in roster_ids
    )
    missing = [str(r["student_id"]).strip() for r in rows
               if str(r["student_id"]).strip() not in assigned]
    return {"assigned": assigned, "unmatched": unmatched,
            "missing": missing, "duplicates": duplicates}


def to_data_url(payload: bytes, filename: str = "") -> Optional[str]:
    ext = os.path.splitext(filename)[1].lower() or ".jpg"
    mime = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
            ".bmp": "image/bmp", ".webp": "image/webp"}.get(ext, "image/jpeg")
    return "data:%s;base64,%s" % (mime, base64.b64encode(payload).decode("ascii"))


def thumbnail_data_url(payload: bytes, max_width: int = 160) -> Optional[str]:
    """Small preview thumbnail (best effort - never raises)."""
    try:
        import cv2
        import numpy as np
        arr = np.frombuffer(payload, np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img is None:
            return None
        h, w = img.shape[:2]
        if w > max_width:
            img = cv2.resize(img, (max_width, max(1, int(h * max_width / w))))
        ok, buf = cv2.imencode(".jpg", img, [int(cv2.IMWRITE_JPEG_QUALITY), 60])
        if not ok:
            return None
        return "data:image/jpeg;base64,%s" % base64.b64encode(buf.tobytes()).decode("ascii")
    except Exception:
        return None
