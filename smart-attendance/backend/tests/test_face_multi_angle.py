"""
Guided multi-angle face registration (front / slight left / slight right).

Uses the same authorized stored crops the existing recognition tests use, plus
synthetic blank/multi-face images. Nothing here touches the live embeddings file
(the tmp_face_data fixture redirects it).
"""
import base64
import json
import os

import numpy as np
import pytest

import main
import recognition.service as rs
from conftest import auth, create_user

FACE_DATA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "recognition", "face_data")
needs_real = pytest.mark.skipif(not rs.recognition_available(),
                               reason="real OpenCV backend/models not installed")


def _url(img):
    ok, buf = __import__("cv2").imencode(".jpg", img)
    assert ok
    return "data:image/jpeg;base64," + base64.b64encode(buf.tobytes()).decode("ascii")


def _crop(sid):
    with open(os.path.join(FACE_DATA, "%s.jpg" % sid), "rb") as fh:
        return "data:image/jpeg;base64," + base64.b64encode(fh.read()).decode("ascii")


def _blank():
    return _url(np.full((240, 240, 3), 245, dtype=np.uint8))


def _two_faces(sid="1AR23IS036"):
    import cv2
    face = cv2.imread(os.path.join(FACE_DATA, "%s.jpg" % sid))
    spacer = np.full((face.shape[0], 40, 3), 255, dtype=np.uint8)
    return _url(np.hstack([face, spacer, face]))


def _service():
    main._shared_recognition = None
    return main.get_recognition_service()


def _three_angles(sid="1AR23IS036"):
    img = _crop(sid)
    return [{"pose": "front", "image_base64": img},
            {"pose": "left", "image_base64": img},
            {"pose": "right", "image_base64": img}]


# ---------------------------------------------------------------- pure logic
def test_templates_of_reads_legacy_single_embedding_records():
    legacy = {"name": "A", "embedding": [0.1] * 128}
    assert len(rs.templates_of(legacy)) == 1
    assert rs.templates_of(legacy)[0]["pose"] == "front"


def test_templates_of_reads_multi_template_records():
    record = {"name": "A", "embedding": [0.1] * 128,
              "templates": [{"pose": "front", "embedding": [0.1] * 128},
                            {"pose": "left", "embedding": [0.2] * 128}]}
    assert {t["pose"] for t in rs.templates_of(record)} == {"front", "left"}


def test_templates_of_drops_corrupt_vectors():
    record = {"name": "A", "embedding": [0.1] * 64,                       # wrong dimension
              "templates": [{"pose": "front", "embedding": [float("nan")] * 128},
                            {"pose": "left", "embedding": [0.2] * 128}]}
    assert [t["pose"] for t in rs.templates_of(record)] == ["left"]


def test_templates_of_ignores_junk():
    assert rs.templates_of(None) == []
    assert rs.templates_of("nope") == []
    assert rs.templates_of({"embedding": "not-a-list"}) == []


# ------------------------------------------------------------- service level
@needs_real
def test_three_angles_are_stored_and_other_students_untouched(tmp_face_data):
    svc = _service()
    svc.register_student_face("OTHER-1", "Other", _crop("1AR23IS036"))
    before = json.loads(json.dumps(svc.embeddings["OTHER-1"]))

    result = svc.register_student_faces("MULTI-1", "Multi", _three_angles())
    assert result["ok"] is True
    assert result["stored"] == 3

    poses = {t["pose"] for t in svc.student_templates("MULTI-1")}
    assert poses == {"front", "left", "right"}
    assert all(t["dimensions"] == 128 for t in svc.student_templates("MULTI-1"))
    # the legacy single-embedding field still points at the front template
    assert svc.embeddings["MULTI-1"]["embedding"] == \
        next(t["embedding"] for t in svc.embeddings["MULTI-1"]["templates"]
             if t["pose"] == "front")
    # no other student was touched
    assert svc.embeddings["OTHER-1"] == before


@needs_real
def test_invalid_angle_stores_nothing(tmp_face_data):
    svc = _service()
    assert svc.register_student_face("ATOMIC-1", "Atomic", _crop("1AR23IS036")) is True
    before = json.loads(json.dumps(svc.embeddings["ATOMIC-1"]))

    for bad in ("blank", "two-faces"):
        payload = _three_angles()
        payload[1]["image_base64"] = _blank() if bad == "blank" else _two_faces()
        result = svc.register_student_faces("ATOMIC-1", "Atomic", payload)
        assert result["ok"] is False
        assert result["stored"] == 0
        assert result["reason"]
        # previous registration survived the failed attempt
        assert svc.embeddings["ATOMIC-1"] == before


@needs_real
def test_undecodable_image_stores_nothing(tmp_face_data):
    svc = _service()
    payload = _three_angles()
    payload[2]["image_base64"] = "data:image/jpeg;base64,AAAA"
    result = svc.register_student_faces("JUNK-1", "Junk", payload)
    assert result["ok"] is False
    assert "JUNK-1" not in svc.embeddings


@needs_real
def test_unknown_pose_is_refused(tmp_face_data):
    svc = _service()
    payload = _three_angles()
    payload[0]["pose"] = "sideways"
    assert svc.register_student_faces("POSE-1", "Pose", payload)["ok"] is False
    assert "POSE-1" not in svc.embeddings


@needs_real
def test_re_registration_replaces_that_student_only(tmp_face_data):
    svc = _service()
    svc.register_student_face("KEEP-1", "Keep", _crop("1AR23IS036"))
    keep_before = json.loads(json.dumps(svc.embeddings["KEEP-1"]))

    svc.register_student_faces("REDO-1", "Redo", _three_angles())
    first = {t["pose"]: t["embedding"] for t in svc.embeddings["REDO-1"]["templates"]}

    svc.register_student_faces("REDO-1", "Redo", _three_angles())
    second = {t["pose"]: t["embedding"] for t in svc.embeddings["REDO-1"]["templates"]}
    assert set(second) == {"front", "left", "right"}
    assert len(second) == 3                      # not 6 after re-registration
    assert svc.embeddings["KEEP-1"] == keep_before


@needs_real
def test_multi_template_student_still_matches(tmp_face_data):
    svc = _service()
    svc.register_student_faces("MATCH-M", "Match", _three_angles())
    results = svc.recognize_faces(_crop("1AR23IS036"))
    matched = [r for r in results if r["matched"]]
    assert matched and matched[0]["student_id"] == "MATCH-M"


@needs_real
def test_legacy_single_embedding_students_still_match(tmp_face_data):
    """Existing one-photo registrations must keep working unchanged."""
    svc = _service()
    svc.register_student_face("LEGACY-M", "Legacy", _crop("1AR23IS036"))
    matched = [r for r in svc.recognize_faces(_crop("1AR23IS036")) if r["matched"]]
    assert matched and matched[0]["student_id"] == "LEGACY-M"


@needs_real
def test_missing_student_id_is_refused(tmp_face_data):
    svc = _service()
    assert svc.register_student_faces("", "Ghost", _three_angles())["ok"] is False
    assert svc.register_student_faces("EMPTY-1", "Empty", [])["ok"] is False


@needs_real
def test_saved_crops_include_each_angle(tmp_face_data, monkeypatch):
    svc = _service()
    svc.register_student_faces("CROP-1", "Crop", _three_angles())
    out = os.path.join(str(tmp_face_data), "CROP-1")
    for suffix in ("", "_left", "_right"):
        assert os.path.exists(out + suffix + ".jpg")


# ------------------------------------------------------------------ endpoint
def _register(client, body):
    return client.post("/api/recognition/register/multi", headers=body.pop("_headers"),
                       json=body)


@needs_real
def test_endpoint_registers_three_angles(client, db, tmp_face_data):
    svc = _service()
    _, token = create_user(db, "teacher-multi", "teacher", branch="ISE")
    student = main.Student(student_id="MULTI-E1", user_id=_make_user(db, "stud-multi-e1"),
                           name="Multi E1", branch="ISE", semester="5th Semester",
                           section="Section A")
    db.add(student)
    db.commit()

    res = _register(client, {
        "_headers": auth(token),
        "student_id": "MULTI-E1",
        "name": "Multi E1",
        "image_base64": _crop("1AR23IS036"),
        "images": _three_angles(),
    })
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["status"] == "registered"
    assert body["angles"] == 3
    assert {t["pose"] for t in body["templates"]} == {"front", "left", "right"}
    db.refresh(student)
    assert student.face_embedding == "registered"


@needs_real
def test_endpoint_requires_all_three_angles(client, db, tmp_face_data):
    _service()
    _, token = create_user(db, "teacher-partial", "teacher", branch="ISE")
    res = _register(client, {
        "_headers": auth(token),
        "student_id": "PARTIAL-1",
        "image_base64": _crop("1AR23IS036"),
        "images": [{"pose": "front", "image_base64": _crop("1AR23IS036")}],
    })
    assert res.status_code == 400
    assert "left" in res.json()["detail"] and "right" in res.json()["detail"]


@needs_real
def test_endpoint_rejects_bad_angle_without_registering(client, db, tmp_face_data):
    svc = _service()
    _, token = create_user(db, "teacher-bad", "teacher", branch="ISE")
    db.add(main.Student(student_id="BAD-1", user_id=_make_user(db, "stud-bad-1"),
                        name="Bad One", branch="ISE", semester="5th Semester",
                        section="Section A"))
    db.commit()
    payload = _three_angles()
    payload[0]["image_base64"] = _blank()
    res = _register(client, {
        "_headers": auth(token),
        "student_id": "BAD-1",
        "image_base64": _crop("1AR23IS036"),
        "images": payload,
    })
    assert res.status_code == 400
    assert "BAD-1" not in svc.embeddings


@needs_real
def test_endpoint_rejects_unknown_student(client, db, tmp_face_data):
    _service()
    _, token = create_user(db, "teacher-ghost", "teacher", branch="ISE")
    res = _register(client, {
        "_headers": auth(token),
        "student_id": "NO-SUCH-STUDENT",
        "image_base64": _crop("1AR23IS036"),
        "images": _three_angles(),
    })
    assert res.status_code == 404


def test_endpoint_is_staff_only(client, db, tmp_face_data):
    _, token = create_user(db, "student-multi", "student", branch="ISE")
    res = _register(client, {
        "_headers": auth(token),
        "student_id": "NOPE-1",
        "image_base64": "AAAA",
        "images": _three_angles(),
    })
    assert res.status_code == 403


def test_mock_backend_never_claims_multi_angle_success():
    mock = rs.MockRecognitionService()
    result = mock.register_student_faces("X", "Y", _three_angles())
    assert result["ok"] is False
    assert mock.status()["real"] is False


def _make_user(db, username):
    user = main.User(username=username, email="%s@test.local" % username,
                     full_name=username, role="student", branch="ISE",
                     hashed_password=main.hash_password("x"))
    db.add(user)
    db.commit()
    db.refresh(user)
    return user.id