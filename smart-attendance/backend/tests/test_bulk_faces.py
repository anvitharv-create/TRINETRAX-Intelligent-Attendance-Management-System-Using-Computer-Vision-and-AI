"""Unit tests for the pure bulk-enrollment helpers in bulk_faces.py."""
import io
import zipfile

import pytest

import bulk_faces as bulk


def make_xlsx(headers, rows):
    import openpyxl
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(headers)
    for r in rows:
        ws.append(r)
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def make_zip(entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return buf.getvalue()


JPG = b"\xff\xd8\xff\xe0fakejpegbits\xff\xd9"


def test_parse_roster_reads_ids_names_and_branch():
    data = make_xlsx(["USN", "Name", "Branch", "Semester", "Section"], [
        ["1AR23IS001", "Ananya K", "ISE", "7th Semester", "Section A"],
        ["1AR23IS002", "Bhaskar", "ISE", "7th Semester", "Section A"],
    ])
    parsed = bulk.parse_roster(data)
    assert parsed["errors"] == []
    assert len(parsed["rows"]) == 2
    assert parsed["rows"][0]["student_id"] == "1AR23IS001"
    assert parsed["rows"][0]["name"] == "Ananya K"
    assert parsed["rows"][0]["branch"] == "ISE"


def test_parse_roster_reports_duplicate_and_missing_ids():
    data = make_xlsx(["Roll No", "Student Name"], [
        ["1AR23IS001", "A"],
        ["1AR23IS001", "A duplicate"],
        [None, "no id"],
        ["1AR23IS002", "B"],
    ])
    parsed = bulk.parse_roster(data)
    ids = [r["student_id"] for r in parsed["rows"]]
    assert ids == ["1AR23IS001", "1AR23IS002"]
    assert len(parsed["errors"]) == 2
    assert any("duplicate" in e["error"] for e in parsed["errors"])
    assert any("missing student id" in e["error"] for e in parsed["errors"])


def test_parse_roster_rejects_missing_id_column():
    data = make_xlsx(["Name", "Email"], [["A", "a@b.c"]])
    with pytest.raises(bulk.BulkError):
        bulk.parse_roster(data)


def test_parse_roster_rejects_garbage_and_oversized():
    with pytest.raises(bulk.BulkError):
        bulk.parse_roster(b"")
    with pytest.raises(bulk.BulkError):
        bulk.parse_roster(b"not an excel file")
    with pytest.raises(bulk.BulkError):
        bulk.parse_roster(b"x" * (bulk.MAX_ROSTER_BYTES + 1))


def test_read_photo_zip_valid_and_non_image_entries():
    data = make_zip({"1AR23IS001.jpg": JPG, "notes.txt": b"hello", "sub/1AR23IS002.png": JPG})
    res = bulk.read_photo_zip(data)
    assert set(res["photos"]) == {"1AR23IS001.jpg", "sub/1AR23IS002.png"}
    assert any("not a supported image type" in e["error"] for e in res["errors"])


def test_read_photo_zip_rejects_bad_zip_and_unsafe_paths():
    with pytest.raises(bulk.BulkError):
        bulk.read_photo_zip(b"PK\x03\x04 definitely not a zip")
    with pytest.raises(bulk.BulkError):
        bulk.read_photo_zip(b"")
    data = make_zip({"../evil.jpg": JPG, "/abs.jpg": JPG, "ok.jpg": JPG})
    res = bulk.read_photo_zip(data)
    assert "ok.jpg" in res["photos"]
    assert len(res["errors"]) == 2  # traversal + absolute path refused
    assert all(name not in res["photos"] for name in ("../evil.jpg", "/abs.jpg"))


def test_read_photo_zip_rejects_archive_without_images():
    with pytest.raises(bulk.BulkError):
        bulk.read_photo_zip(make_zip({"readme.txt": b"hi"}))


@pytest.mark.parametrize("filename,expected", [
    ("1AR23IS006.jpg", "1AR23IS006"),
    ("1AR23IS006_front.jpg", "1AR23IS006"),
    ("photos/1AR23IS006-2.jpeg", "1AR23IS006"),
    ("1AR23IS006.PNG", "1AR23IS006"),
    ("_weird.jpg", None),
    (".jpg", None),
])
def test_student_id_from_filename(filename, expected):
    assert bulk.student_id_from_filename(filename) == expected


def test_match_photos_assigns_reports_missing_unmatched_duplicates():
    rows = [
        {"student_id": "1AR23IS001"},
        {"student_id": "1AR23IS002"},
        {"student_id": "1AR23IS003"},
    ]
    photo_names = [
        "1AR23IS001.jpg",
        "1AR23IS001_back.jpg",      # duplicate for 001
        "1AR23IS009.jpg",           # not on roster
        "random.jpg",               # id-less
    ]
    res = bulk.match_photos(rows, photo_names)
    assert res["assigned"]["1AR23IS001"] == "1AR23IS001.jpg"
    assert res["duplicates"]["1AR23IS001"] == ["1AR23IS001.jpg", "1AR23IS001_back.jpg"]
    assert sorted(res["missing"]) == ["1AR23IS002", "1AR23IS003"]
    assert "1AR23IS009.jpg" in res["unmatched"]
    assert "random.jpg" in res["unmatched"]


def test_to_data_url_sets_mime():
    url = bulk.to_data_url(JPG, "a.png")
    assert url.startswith("data:image/png;base64,")
    assert bulk.to_data_url(JPG, "a.jpg").startswith("data:image/jpeg;base64,")
