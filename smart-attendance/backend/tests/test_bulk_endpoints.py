"""
End-to-end tests for the bulk face registration endpoints
(POST /api/faces/bulk/preview and /api/faces/bulk/commit).
"""
import json
import os

import main
import recognition.service as rs
from conftest import auth, create_user
from test_bulk_faces import JPG, make_xlsx, make_zip

FACE_DATA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "recognition", "face_data")
LIVE_EMBEDDINGS = os.path.join(FACE_DATA, "embeddings.json")

with open(os.path.join(FACE_DATA, "1AR23IS036.jpg"), "rb") as _fh:
    REAL_FACE = _fh.read()


def _roster_bytes():
    return make_xlsx(["USN", "Name", "Branch", "Semester", "Section"], [
        ["BULK001", "Bulk One", "ISE", "7th Semester", "Section A"],
        ["BULK002", "Bulk Two", "ISE", "7th Semester", "Section A"],
        ["BULK003", "Bulk Three", "ISE", "7th Semester", "Section A"],
        ["BULK999", "Not In DB", "ISE", "7th Semester", "Section A"],
    ])


def _zip_bytes():
    return make_zip({
        "BULK001.jpg": REAL_FACE,
        "BULK002.jpg": REAL_FACE,
        "BULK999.jpg": REAL_FACE,
        "STRAY.jpg": JPG,     # matches no roster row
    })


def _seed(db):
    students = {}
    for sid in ("BULK001", "BULK002", "BULK003"):
        u, _ = create_user(db, sid.lower(), "student")
        stu = main.Student(student_id=sid, user_id=u.id, name="Student " + sid,
                           roll_number=sid, branch="ISE", semester="7th Semester",
                           section="Section A", email="%s@test.local" % sid.lower())
        db.add(stu)
        students[sid] = stu
    db.commit()
    students["BULK002"].face_embedding = "registered"
    db.commit()
    return students


def _files():
    return {
        "roster": ("roster.xlsx", _roster_bytes(),
                   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        "photos": ("photos.zip", _zip_bytes(), "application/zip"),
    }


def test_preview_reports_full_validation_plan(client, db):
    _seed(db)
    _, token = create_user(db, "bulk-teacher", "teacher")
    res = client.post("/api/faces/bulk/preview", files=_files(), headers=auth(token))
    assert res.status_code == 200, res.text
    body = res.json()

    assert body["committed"] is False
    assert body["roster"]["rows"] == 4
    assert body["photos"]["count"] == 4

    by_id = {i["student_id"]: i for i in body["items"]}
    assert by_id["BULK001"]["status"] == "ready"
    assert by_id["BULK001"]["thumbnail"], "preview should include a thumbnail"
    assert by_id["BULK002"]["status"] == "already_registered"
    assert by_id["BULK003"]["status"] == "missing_photo"
    assert by_id["BULK999"]["status"] == "not_in_db"
    assert "STRAY.jpg" in body["match"]["unmatched"]

    s = body["summary"]
    assert s["ready"] == 1
    assert s["already_registered"] == 1
    assert s["missing_photo"] == 1
    assert s["not_in_db"] == 1
    assert s["photos_unmatched"] == 1
    assert body["backend"]["real"] is True


def test_preview_does_not_modify_anything(client, db):
    students = _seed(db)
    _, token = create_user(db, "bulk-peeker", "teacher")
    before_flag = students["BULK001"].face_embedding
    before_live = open(LIVE_EMBEDDINGS, encoding="utf-8").read()
    res = client.post("/api/faces/bulk/preview", files=_files(), headers=auth(token))
    assert res.status_code == 200
    db.refresh(students["BULK001"])
    assert students["BULK001"].face_embedding == before_flag
    assert open(LIVE_EMBEDDINGS, encoding="utf-8").read() == before_live


def test_commit_imports_skips_and_fails_correctly(client, db, tmp_face_data):
    students = _seed(db)
    _, token = create_user(db, "bulk-committer", "teacher")

    # pre-existing embedding for BULK002 lives in the TEMP store only
    svc = main.get_recognition_service()
    svc.embeddings["BULK002"] = {"name": "Old Name", "embedding": [0.1] * 128}
    svc._save_embeddings()

    before_live = open(LIVE_EMBEDDINGS, encoding="utf-8").read()
    res = client.post("/api/faces/bulk/commit", files=_files(), headers=auth(token))
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["committed"] is True

    imported_ids = [i["student_id"] for i in body["imported"]]
    skipped_ids = [i["student_id"] for i in body["skipped"]]
    failed_ids = [i["student_id"] for i in body["failed"]]

    assert imported_ids == ["BULK001"]
    assert sorted(skipped_ids) == ["BULK002", "BULK003"]
    assert failed_ids == ["BULK999"]

    # DB flag + temp embeddings store updated, existing entry untouched
    db.refresh(students["BULK001"])
    assert students["BULK001"].face_embedding == "registered"
    store = json.loads(open(os.path.join(str(tmp_face_data), "embeddings.json"),
                            encoding="utf-8").read())
    assert "BULK001" in store
    assert store["BULK002"]["name"] == "Old Name"  # not overwritten (skip)

    # live store untouched
    assert open(LIVE_EMBEDDINGS, encoding="utf-8").read() == before_live

    assert body["summary"] == {"imported": 1, "skipped": 2, "failed": 1,
                               "roster_rows": 4, "overwrite": False}


def test_commit_overwrite_replaces_existing_registration(client, db, tmp_face_data):
    students = _seed(db)
    _, token = create_user(db, "bulk-overwriter", "teacher")
    svc = main.get_recognition_service()
    svc.embeddings["BULK002"] = {"name": "Old Name", "embedding": [0.1] * 128}
    svc._save_embeddings()

    res = client.post("/api/faces/bulk/commit", files=_files(),
                      data={"overwrite": "true"}, headers=auth(token))
    assert res.status_code == 200, res.text
    body = res.json()
    imported_ids = sorted(i["student_id"] for i in body["imported"])
    assert imported_ids == ["BULK001", "BULK002"]
    store = svc.embeddings
    assert store["BULK002"]["name"] == "Student BULK002"  # replaced with real data


def test_commit_refuses_when_backend_unavailable(client, db, monkeypatch):
    _seed(db)
    _, token = create_user(db, "bulk-nobackend", "teacher")
    monkeypatch.setattr(main, "get_recognition_service",
                        lambda: rs.MockRecognitionService())
    res = client.post("/api/faces/bulk/commit", files=_files(), headers=auth(token))
    assert res.status_code == 503
    assert "No faces were imported" in res.json()["detail"]


def test_bulk_requires_auth_and_staff_role(client, db):
    _seed(db)
    res = client.post("/api/faces/bulk/preview", files=_files())
    assert res.status_code in (401, 403)
    _, student_token = create_user(db, "bulk-student", "student")
    res = client.post("/api/faces/bulk/preview", files=_files(),
                      headers=auth(student_token))
    assert res.status_code == 403
    res = client.post("/api/faces/bulk/commit", files=_files(),
                      headers=auth(student_token))
    assert res.status_code == 403


def test_preview_rejects_bad_files(client, db):
    _seed(db)
    _, token = create_user(db, "bulk-bad", "teacher")
    # not an excel file
    res = client.post("/api/faces/bulk/preview",
                      files={"roster": ("r.xlsx", b"junk", "application/octet-stream"),
                             "photos": ("p.zip", _zip_bytes(), "application/zip")},
                      headers=auth(token))
    assert res.status_code == 400
    assert "Roster file" in res.json()["detail"]
    # not a zip
    res = client.post("/api/faces/bulk/preview",
                      files={"roster": ("r.xlsx", _roster_bytes(), "application/octet-stream"),
                             "photos": ("p.zip", b"junk", "application/zip")},
                      headers=auth(token))
    assert res.status_code == 400
    assert "Photos ZIP" in res.json()["detail"]
