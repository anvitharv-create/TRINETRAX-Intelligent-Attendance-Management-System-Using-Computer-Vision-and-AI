"""
Recognition reliability tests.

Uses the authorized test images already stored in recognition/face_data
(one crop per registered student) plus synthetic zero/multi-face images.
Nothing here writes to the live embeddings store (tmp_face_data fixture).
"""
import base64
import json
import os

import pytest

import main
import recognition.service as rs

FACE_DATA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "recognition", "face_data")
LIVE_EMBEDDINGS = os.path.join(FACE_DATA, "embeddings.json")


def _data_url_from_image(img):
    ok, buf = __import__("cv2").imencode(".jpg", img)
    assert ok
    return "data:image/jpeg;base64," + base64.b64encode(buf.tobytes()).decode("ascii")


def _read_crop_b64(sid):
    path = os.path.join(FACE_DATA, "%s.jpg" % sid)
    with open(path, "rb") as fh:
        return "data:image/jpeg;base64," + base64.b64encode(fh.read()).decode("ascii")


def _service(tmp_face_data):
    main._shared_recognition = None
    return main.get_recognition_service()


REAL_BACKEND = rs.recognition_available()
needs_real = pytest.mark.skipif(not REAL_BACKEND,
                                reason="real OpenCV backend/models not installed")


def test_backend_status_shape(client, db):
    from conftest import auth, create_user
    _, staff_token = create_user(db, "status-staff", "teacher")
    res = client.get("/api/recognition/status", headers=auth(staff_token))
    assert res.status_code == 200
    body = res.json()
    assert set(body) >= {"real", "method", "registered", "threshold"}


@needs_real
def test_analyze_single_face_crop(client, tmp_face_data):
    svc = _service(tmp_face_data)
    check = svc.analyze_image(_read_crop_b64("1AR23IS036"))
    assert check["ok"] is True
    assert check["num_faces"] == 1
    assert rs.embedding_is_valid(check["embedding"])


@needs_real
def test_analyze_reports_zero_and_multiple_faces(tmp_face_data):
    import cv2
    import numpy as np
    svc = _service(tmp_face_data)

    blank = np.full((240, 240, 3), 245, dtype=np.uint8)
    res = svc.analyze_image(_data_url_from_image(blank))
    assert res["ok"] is False
    assert res["num_faces"] == 0
    assert "No face" in res["reason"]

    crop_path = os.path.join(FACE_DATA, "1AR23IS036.jpg")
    face = cv2.imread(crop_path)
    assert face is not None
    spacer = np.full((face.shape[0], 40, 3), 255, dtype=np.uint8)
    two = np.hstack([face, spacer, face])
    res = svc.analyze_image(_data_url_from_image(two))
    assert res["ok"] is False
    assert res["num_faces"] >= 2
    assert "Multiple faces" in res["reason"]


@needs_real
def test_analyze_rejects_undecodable_image(tmp_face_data):
    svc = _service(tmp_face_data)
    res = svc.analyze_image("data:image/jpeg;base64," + base64.b64encode(b"junk").decode())
    assert res["ok"] is False


@needs_real
def test_failed_registration_never_overwrites_existing(tmp_face_data):
    svc = _service(tmp_face_data)
    good = _read_crop_b64("1AR23IS036")
    assert svc.register_student_face("TEST-001", "Test Student", good) is True
    before = json.loads(json.dumps(svc.embeddings["TEST-001"]))

    # blank image must fail and leave the stored embedding untouched
    import cv2
    import numpy as np
    blank = np.full((240, 240, 3), 245, dtype=np.uint8)
    assert svc.register_student_face("TEST-001", "Test Student",
                                     _data_url_from_image(blank)) is False
    assert svc.embeddings["TEST-001"] == before

    # undecodable image likewise
    assert svc.register_student_face("TEST-001", "Test Student",
                                     "data:image/jpeg;base64,AAAA") is False
    assert svc.embeddings["TEST-001"] == before

    # empty student id refused
    assert svc.register_student_face("", "Ghost", good) is False


@needs_real
def test_recognize_matches_registered_face_and_respects_allowlist(tmp_face_data):
    svc = _service(tmp_face_data)
    good = _read_crop_b64("1AR23IS036")
    assert svc.register_student_face("MATCH-A", "Student A", good)

    results = svc.recognize_faces(good)
    matched = [r for r in results if r["matched"]]
    assert matched, results
    assert matched[0]["student_id"] == "MATCH-A"
    assert 0.0 <= matched[0]["confidence"] <= 1.0

    # allowlist can exclude the match -> reported as unknown, not matched
    results = svc.recognize_faces(good, allowed_student_ids={"SOMEONE-ELSE"})
    assert all(not r["matched"] for r in results)


def test_mock_backend_never_fakes_registrations(tmp_face_data):
    mock = rs.MockRecognitionService()
    assert mock.register_student_face("X", "Y", "data:;base64,AAAA") is False
    assert mock.analyze_image("data:;base64,AAAA")["ok"] is False
    assert mock.status()["real"] is False


def test_embedding_validation_rules():
    good = [0.1] * 128
    assert rs.embedding_is_valid(good)
    assert not rs.embedding_is_valid([0.1] * 127)
    assert not rs.embedding_is_valid(["a"] * 128)
    assert not rs.embedding_is_valid([float("nan")] * 128)
    assert not rs.embedding_is_valid([0.0] * 128)
    assert not rs.embedding_is_valid("not-a-list")
    assert not rs.embedding_is_valid(None)


def test_live_embeddings_file_is_consistent():
    """Stored file: every vector is a valid 128-d embedding, keyed by USN."""
    with open(LIVE_EMBEDDINGS, encoding="utf-8") as fh:
        data = json.load(fh)
    for sid, rec in data.items():
        assert sid and sid not in ("STU-100", ""), "orphan key %r" % sid
        assert rs.embedding_is_valid(rec.get("embedding"))


@needs_real
def test_cosine_threshold_separates_genuine_and_impostor_pairs():
    """
    Verify (not assume) the 0.45 threshold on real stored data:
      * a registered student's own crop re-encodes to distance << 0.45
      * pairs of different students sit above 0.45
    """
    with open(LIVE_EMBEDDINGS, encoding="utf-8") as fh:
        stored = json.load(fh)
    svc = rs.OpenCVRecognitionService()  # read-only load of the live file
    assert svc.loaded_ok

    encodings = {}
    for sid in stored:
        crop = os.path.join(FACE_DATA, "%s.jpg" % sid)
        if not os.path.exists(crop):
            continue
        emb = svc.encode_face(_read_crop_b64(sid))
        assert emb, "could not re-encode stored crop for %s" % sid
        encodings[sid] = emb

    threshold = rs.DEFAULT_TOLERANCE
    for sid, emb in encodings.items():
        genuine = svc._cosine_distance(emb, stored[sid]["embedding"])
        assert genuine < threshold, "genuine distance %.3f for %s" % (genuine, sid)
        for other, other_emb in encodings.items():
            if other == sid:
                continue
            impostor = svc._cosine_distance(emb, stored[other]["embedding"])
            assert impostor > threshold, (
                "impostor distance %.3f between %s and %s below threshold %.2f"
                % (impostor, sid, other, threshold)
            )
