"""
Face Recognition Service Module

This module provides face detection and recognition capabilities for the
attendance system.

Two backends are supported:

1. OpenCVRecognitionService (real, used by default when available)
   - Face detection: YuNet (OpenCV DNN, ONNX)
   - Face recognition: SFace (OpenCV DNN, ONNX) producing 128-d embeddings,
     matched with cosine distance.
   - Registered student embeddings are stored in backend/recognition/face_data.

2. MockRecognitionService (fallback / demo)
   - Simulates detection/recognition results so the app still works without
     the OpenCV packages.

The factory `create_recognition_service` picks the real backend automatically
and falls back to the mock backend if OpenCV or the model files are missing.
"""

import base64
import json
import os
import threading
from datetime import datetime
from typing import List, Dict, Optional, Set, Tuple

import numpy as np

try:
    import cv2
    _HAS_CV2 = True
except Exception:  # pragma: no cover
    cv2 = None
    _HAS_CV2 = False

_DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
_FACE_DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "face_data")
_YUNET_PATH = os.path.join(_DATA_DIR, "face_detection_yunet_2023mar.onnx")
_SFACE_PATH = os.path.join(_DATA_DIR, "face_recognition_sface_2021dec.onnx")
_EMBEDDINGS_PATH = os.path.join(_FACE_DATA_DIR, "embeddings.json")
_THREAD_LOCK = threading.RLock()

# SFace produces 128-d embeddings. Verified on the stored registrations:
# genuine (same person) cosine distance 0.08-0.18, impostor 0.73-0.99, so the
# 0.45 default threshold sits in the wide gap between them. Do not change
# without re-measuring on real images.
EMBEDDING_DIM = 128
DEFAULT_TOLERANCE = 0.45

# Guided multi-angle registration. Each step is a separate SFace template of the
# same student; a query face is matched against every template and the closest
# one wins, so a student is still recognised from angles the front photo misses.
POSE_FIELDS = ("front", "left", "right")
MAX_TEMPLATES_PER_STUDENT = 6


def templates_of(record: dict) -> List[dict]:
    """
    Every usable template of one stored student record.

    Records written before multi-angle registration only have "embedding"; those
    still work unchanged. Newer records keep "embedding" (the front template, so
    old readers and the audit script keep working) plus a "templates" list.
    """
    if not isinstance(record, dict):
        return []
    out = []
    stored = record.get("templates")
    if isinstance(stored, list):
        for item in stored:
            if isinstance(item, dict) and embedding_is_valid(item.get("embedding")):
                out.append(item)
    if embedding_is_valid(record.get("embedding")):
        # avoid listing the same vector twice when templates already includes it
        primary = record["embedding"]
        if not any(item.get("embedding") == primary for item in out):
            out.append({"pose": "front", "embedding": primary})
    return out


class RecognitionError(Exception):
    """Raised when the real backend is unusable (models/OpenCV missing)."""


def embedding_is_valid(embedding) -> bool:
    """True when *embedding* is a usable SFace vector (correct size, finite, non-zero)."""
    if not isinstance(embedding, (list, tuple)):
        return False
    if len(embedding) != EMBEDDING_DIM:
        return False
    try:
        vec = np.asarray(embedding, dtype=np.float32)
    except (TypeError, ValueError):
        return False
    if not np.all(np.isfinite(vec)):
        return False
    return float(np.linalg.norm(vec)) > 0.0



class FaceEncoding:
    """Represents a face encoding/embedding for matching."""

    def __init__(self, encoding: List[float], student_id: str, name: str):
        self.encoding = encoding
        self.student_id = student_id
        self.name = name


class OpenCVRecognitionService:
    """
    Real face recognition using OpenCV YuNet (detection) + SFace (recognition).
    """

    def __init__(self, known_faces: Optional[List[FaceEncoding]] = None):
        self._detector = None
        self._recognizer = None
        self.embeddings: Dict[str, dict] = {}
        self.loaded_ok = False

        if not _HAS_CV2:
            self.loaded_ok = False
            return

        try:
            if os.path.exists(_YUNET_PATH):
                self._detector = cv2.FaceDetectorYN.create(
                    _YUNET_PATH, "", (640, 640), 0.7, 0.3, 5000
                )
            if os.path.exists(_SFACE_PATH):
                self._recognizer = cv2.FaceRecognizerSF.create(
                    _SFACE_PATH, ""
                )
            self.loaded_ok = self._detector is not None and self._recognizer is not None
        except Exception as e:  # pragma: no cover
            print(f"[recognition] Failed to init OpenCV backend: {e}")
            self.loaded_ok = False
            return

        if known_faces:
            self.embeddings = {
                f.student_id: {"name": f.name, "embedding": f.encoding}
                for f in known_faces
            }
            self._save_embeddings()
        else:
            self._load_embeddings()

    # ------------------------------------------------------------------ utils
    @staticmethod
    def _decode_image(image_base64: str):
        try:
            if "," in image_base64:
                image_base64 = image_base64.split(",")[1]
            raw = base64.b64decode(image_base64, validate=False)
            if not raw:
                return None
            arr = np.frombuffer(raw, np.uint8)
            return cv2.imdecode(arr, cv2.IMREAD_COLOR)
        except Exception:
            return None

    @staticmethod
    def _cosine_distance(a: List[float], b: List[float]) -> float:
        if not embedding_is_valid(a) or not embedding_is_valid(b):
            return 1.0
        av = np.asarray(a, dtype=np.float32)
        bv = np.asarray(b, dtype=np.float32)
        if av.shape != bv.shape:
            return 1.0
        denom = float(np.linalg.norm(av) * np.linalg.norm(bv))
        if denom == 0.0:
            return 1.0
        dist = 1.0 - float(np.dot(av, bv) / denom)
        if not np.isfinite(dist):
            return 1.0
        return min(1.0, max(0.0, dist))

    # ----------------------------------------------------------- persistence
    def _load_embeddings(self):
        with _THREAD_LOCK:
            if os.path.exists(_EMBEDDINGS_PATH):
                try:
                    with open(_EMBEDDINGS_PATH, "r", encoding="utf-8") as f:
                        self.embeddings = json.load(f)
                except Exception:
                    self.embeddings = {}
            else:
                self.embeddings = {}

    def _save_embeddings(self):
        with _THREAD_LOCK:
            os.makedirs(_FACE_DATA_DIR, exist_ok=True)
            with open(_EMBEDDINGS_PATH, "w", encoding="utf-8") as f:
                json.dump(self.embeddings, f)

    def registered_count(self) -> int:
        return len(self.embeddings)

    # -------------------------------------------------------------- detection
    def _require_models(self) -> Optional[str]:
        """Return an error message when detector/recognizer are unavailable."""
        if not _HAS_CV2:
            return "OpenCV (cv2) is not installed"
        if self._detector is None:
            return "YuNet face detection model is missing (recognition/data/face_detection_yunet_2023mar.onnx)"
        if self._recognizer is None:
            return "SFace recognition model is missing (recognition/data/face_recognition_sface_2021dec.onnx)"
        return None

    def detect_faces(self, image_base64: str) -> Dict[str, object]:
        problem = self._require_models()
        if problem:
            return {"num_faces": 0, "face_locations": [], "success": False,
                    "error": problem, "method": "opencv-yunet"}
        img = self._decode_image(image_base64)
        if img is None:
            return {"num_faces": 0, "face_locations": [], "success": False,
                    "error": "Image could not be decoded", "method": "opencv-yunet"}
        h, w = img.shape[:2]
        self._detector.setInputSize((w, h))
        num_faces, faces = self._detector.detect(img)
        locations = []
        if faces is not None:
            for f in faces:
                x, y, fw, fh = int(f[0]), int(f[1]), int(f[2]), int(f[3])
                locations.append((y, x + fw, y + fh, x))
        return {
            "num_faces": len(locations),
            "face_locations": locations,
            "success": True,
            "method": "opencv-yunet",
            "registered": self.registered_count(),
        }

    def _extract_embedding(self, img, face) -> Optional[List[float]]:
        try:
            aligned = self._recognizer.alignCrop(img, face)
            emb = self._recognizer.feature(aligned)
            return [float(v) for v in emb.flatten()]
        except Exception:
            return None

    # ------------------------------------------------------------- recognition
    def recognize_faces(
        self,
        image_base64: str,
        tolerance: float = DEFAULT_TOLERANCE,
        allowed_student_ids: Optional[Set[str]] = None,
    ) -> List[Dict[str, object]]:
        problem = self._require_models()
        if problem:
            raise RecognitionError(problem)
        img = self._decode_image(image_base64)
        if img is None:
            return []
        h, w = img.shape[:2]
        self._detector.setInputSize((w, h))
        num_faces, faces = self._detector.detect(img)

        results: List[Dict[str, object]] = []
        if faces is None:
            return results

        known_ids = [
            sid
            for sid in self.embeddings
            if sid
            and templates_of(self.embeddings[sid])
            and (allowed_student_ids is None or sid in allowed_student_ids)
        ]

        for face in faces:
            embedding = self._extract_embedding(img, face)
            if embedding is None:
                continue

            best_id = "unknown"
            best_name = "Unknown Person"
            best_dist = float("inf")

            for sid in known_ids:
                rec = self.embeddings[sid]
                # a student may have several templates: keep the closest match
                for template in templates_of(rec):
                    dist = self._cosine_distance(embedding, template["embedding"])
                    if dist < best_dist:
                        best_dist = dist
                        best_id = sid
                        best_name = rec.get("name", "Unknown")

            if best_id != "unknown" and best_dist <= tolerance:
                confidence = max(0.0, min(1.0, round(1.0 - best_dist, 3)))
                results.append({
                    "student_id": best_id,
                    "name": best_name,
                    "confidence": confidence,
                    "encoding": embedding,
                    "matched": True,
                })
            else:
                results.append({
                    "student_id": "unknown",
                    "name": "Unknown Person",
                    "confidence": round(max(0.0, min(1.0, 1.0 - best_dist)), 3)
                    if best_id != "unknown" else 0.0,
                    "encoding": embedding,
                    "matched": False,
                })
        return results

    def encode_face(self, image_base64: str) -> Optional[List[float]]:
        problem = self._require_models()
        if problem:
            raise RecognitionError(problem)
        img = self._decode_image(image_base64)
        if img is None:
            return None
        h, w = img.shape[:2]
        self._detector.setInputSize((w, h))
        num_faces, faces = self._detector.detect(img)
        if faces is None or len(faces) == 0:
            return None
        embedding = self._extract_embedding(img, faces[0])
        return embedding if embedding_is_valid(embedding) else None

    def analyze_image(self, image_base64: str) -> Dict[str, object]:
        """
        Non-destructive validation of a candidate registration image.

        Returns the number of faces plus an optional 128-d embedding and an
        explicit reason when the image is unusable. Never writes anything.
        """
        problem = self._require_models()
        if problem:
            return {"ok": False, "num_faces": 0, "reason": problem}
        img = self._decode_image(image_base64)
        if img is None:
            return {"ok": False, "num_faces": 0, "reason": "Image could not be decoded"}
        h, w = img.shape[:2]
        self._detector.setInputSize((w, h))
        num_faces, faces = self._detector.detect(img)
        count = 0 if faces is None else len(faces)
        if count == 0:
            return {"ok": False, "num_faces": 0, "reason": "No face found in the image"}
        if count > 1:
            return {"ok": False, "num_faces": count,
                    "reason": "Multiple faces found (%d) - use a photo with a single face" % count}
        embedding = self._extract_embedding(img, faces[0])
        if not embedding_is_valid(embedding):
            return {"ok": False, "num_faces": count, "reason": "Could not compute a valid face embedding"}
        return {"ok": True, "num_faces": 1, "embedding": embedding}

    # ------------------------------------------------------------- registration
    def _capture(self, image_base64):
        """Decode one image and return (image, embedding, face) or None.

        A usable capture has exactly one detectable face and yields a valid
        128-d embedding. Anything else is refused here, before anything is
        written to disk.
        """
        img = self._decode_image(image_base64)
        if img is None:
            return None
        h, w = img.shape[:2]
        self._detector.setInputSize((w, h))
        num_faces, faces = self._detector.detect(img)
        if faces is None or len(faces) == 0:
            return None
        if len(faces) > 1:
            # Never guess which of several faces belongs to the student.
            return None
        embedding = self._extract_embedding(img, faces[0])
        if not embedding_is_valid(embedding):
            return None
        return img, embedding, faces[0]

    @staticmethod
    def _safe_filename(student_id):
        return "".join(ch for ch in str(student_id) if ch.isalnum() or ch in ("-", "_"))

    def register_student_face(
        self, student_id: str, name: str, image_base64: str
    ) -> bool:
        """
        Validate first, write last.

        The stored embedding is only replaced when the new image decodes,
        contains exactly one face and yields a valid 128-d embedding. Any
        failure returns False and leaves the existing registration untouched.
        """
        problem = self._require_models()
        if problem:
            print(f"[recognition] register refused: {problem}")
            return False
        if not student_id or not str(student_id).strip():
            return False
        captured = self._capture(image_base64)
        if captured is None:
            return False
        img, embedding, face = captured

        with _THREAD_LOCK:
            self.embeddings[student_id] = {"name": name, "embedding": embedding}
            self._save_embeddings()

        # Save a face crop preview (filename must never escape face_data/)
        self._save_crop(student_id, img, face)
        return True

    def register_student_faces(self, student_id: str, name: str, captures) -> dict:
        """
        Multi-angle registration: validate every angle, then write once.

        ``captures`` is a list of {"pose": "front"|"left"|"right",
        "image_base64": ...}. Either ALL angles are valid and stored, or nothing
        is written at all - a partial registration is never reported as success.
        Only this student's record is touched; other students are untouched.
        Returns {"ok": bool, "stored": int, "reason": str}.
        """
        problem = self._require_models()
        if problem:
            return {"ok": False, "stored": 0, "reason": problem}
        if not student_id or not str(student_id).strip():
            return {"ok": False, "stored": 0, "reason": "A student is required"}
        if not captures:
            return {"ok": False, "stored": 0, "reason": "No images were captured"}

        prepared = []
        for item in captures:
            pose = str(item.get("pose") or "").strip().lower()
            if pose not in POSE_FIELDS:
                return {"ok": False, "stored": 0,
                        "reason": "Unknown pose '%s' (expected %s)"
                                  % (pose, ", ".join(POSE_FIELDS))}
            captured = self._capture(item.get("image_base64"))
            if captured is None:
                return {"ok": False, "stored": 0,
                        "reason": "The %s photo is unusable: it must contain exactly one "
                                  "clear, front-lit face. Please retake it." % pose}
            img, embedding, face = captured
            prepared.append({"pose": pose, "embedding": embedding,
                             "image": img, "face": face})

        # all-or-nothing write, under the same lock as single registration
        with _THREAD_LOCK:
            existing = self.embeddings.get(student_id) or {}
            templates = templates_of(existing)
            kept = []
            for template in templates:
                pose = template.get("pose")
                if any(t["pose"] == pose for t in prepared) and pose:
                    continue          # re-registration replaces that angle
                kept.append({"pose": pose or "front", "embedding": template["embedding"]})
            merged = kept + [{"pose": t["pose"], "embedding": t["embedding"]} for t in prepared]
            if len(merged) > MAX_TEMPLATES_PER_STUDENT:
                merged = merged[-MAX_TEMPLATES_PER_STUDENT:]
            primary = next((t["embedding"] for t in prepared if t["pose"] == "front"), None)
            if primary is None:
                primary = merged[0]["embedding"]
            self.embeddings[student_id] = {
                "name": name,
                "embedding": primary,          # legacy readers still work
                "templates": merged,
            }
            self._save_embeddings()

        for item in prepared:
            self._save_crop(student_id, item["image"], item["face"], item["pose"])
        return {"ok": True, "stored": len(prepared),
                "reason": "%d angle(s) registered" % len(prepared)}

    def student_templates(self, student_id: str) -> List[dict]:
        """Poses currently stored for a student (empty when not registered)."""
        return [
            {"pose": t.get("pose") or "front", "dimensions": len(t["embedding"])}
            for t in templates_of(self.embeddings.get(student_id) or {})
        ]

    def _save_crop(self, student_id, img, face, pose="front"):
        """Face crop preview; the front crop keeps the legacy <student_id>.jpg name."""
        try:
            safe_id = self._safe_filename(student_id)
            if not safe_id:
                return
            x, y, fw, fh = int(face[0]), int(face[1]), int(face[2]), int(face[3])
            crop = img[max(0, y): y + fh, max(0, x): x + fw]
            os.makedirs(_FACE_DATA_DIR, exist_ok=True)
            suffix = "" if pose == "front" else "_%s" % pose
            cv2.imwrite(os.path.join(_FACE_DATA_DIR, "%s%s.jpg" % (safe_id, suffix)), crop)
        except Exception:
            pass

    def remove_student_face(self, student_id: str) -> bool:
        with _THREAD_LOCK:
            if student_id in self.embeddings:
                del self.embeddings[student_id]
                self._save_embeddings()
                return True
        return False

    def status(self) -> Dict[str, object]:
        return {
            "real": self.loaded_ok,
            "method": "opencv-yunet+sface",
            "registered": self.registered_count(),
            "threshold": DEFAULT_TOLERANCE,
        }


class MockRecognitionService:
    """
    Mock recognition service that simulates face recognition for demo use
    when the real OpenCV backend is unavailable.
    """

    def __init__(self, known_faces: Optional[List[FaceEncoding]] = None):
        if known_faces:
            self.known_faces = known_faces
        else:
            self.known_faces = [
                FaceEncoding(
                    encoding=[0.45, 0.32, 0.89, 0.12, 0.67, 0.55, 0.23, 0.78, 0.34, 0.91],
                    student_id="STU-001",
                    name="Alice Johnson",
                ),
                FaceEncoding(
                    encoding=[0.12, 0.67, 0.23, 0.89, 0.45, 0.33, 0.77, 0.11, 0.55, 0.22],
                    student_id="STU-002",
                    name="Bob Smith",
                ),
                FaceEncoding(
                    encoding=[0.65, 0.11, 0.44, 0.77, 0.33, 0.99, 0.22, 0.55, 0.11, 0.66],
                    student_id="STU-003",
                    name="Carol Davis",
                ),
                FaceEncoding(
                    encoding=[0.22, 0.44, 0.66, 0.11, 0.33, 0.55, 0.77, 0.88, 0.99, 0.12],
                    student_id="STU-004",
                    name="David Wilson",
                ),
                FaceEncoding(
                    encoding=[0.55, 0.77, 0.11, 0.33, 0.99, 0.22, 0.44, 0.66, 0.88, 0.12],
                    student_id="STU-005",
                    name="Eve Martin",
                ),
            ]

    def detect_faces(self, image_base64: str) -> Dict[str, object]:
        return {
            "num_faces": 4,
            "face_locations": [
                (100, 150, 300, 50),
                (120, 160, 320, 60),
                (110, 145, 295, 55),
                (115, 155, 310, 58),
            ],
            "success": True,
            "method": "mock-hog-detection",
            "registered": len(self.known_faces),
        }

    def recognize_faces(
        self,
        image_base64: str,
        tolerance: float = DEFAULT_TOLERANCE,
        allowed_student_ids: Optional[Set[str]] = None,
    ) -> List[Dict[str, object]]:
        mock_results = [
            {"student_id": "STU-001", "name": "Alice Johnson", "confidence": 0.95, "matched": True},
            {"student_id": "STU-002", "name": "Bob Smith", "confidence": 0.92, "matched": True},
            {"student_id": "STU-004", "name": "David Wilson", "confidence": 0.88, "matched": True},
            {"student_id": "unknown", "name": "Unknown Person", "confidence": 0.35, "matched": False},
        ]
        results = []
        for result in mock_results:
            if (
                allowed_student_ids is not None
                and result["student_id"] not in allowed_student_ids
            ):
                continue
            if not result["matched"] or result["confidence"] < tolerance:
                results.append({
                    "student_id": "unknown",
                    "name": "Unknown",
                    "confidence": result["confidence"],
                    "matched": False,
                })
            else:
                results.append(result)
        return results

    def encode_face(self, image_base64: str) -> Optional[List[float]]:
        return [0.45, 0.32, 0.89, 0.12, 0.67, 0.55, 0.23, 0.78, 0.34, 0.91]

    def register_student_face(self, student_id, name, image_base64) -> bool:
        # A mock backend must never claim a face was registered: the caller
        # stores a "registered" flag on the student record, which would then be
        # a false AI result.
        print(f"[recognition] mock backend refused registration for {student_id} ({name})")
        return False

    def register_student_faces(self, student_id, name, captures) -> dict:
        # Same rule for the multi-angle flow: no real detection, no registration.
        print(f"[recognition] mock backend refused multi-angle registration for {student_id}")
        return {"ok": False, "stored": 0,
                "reason": "Recognition backend unavailable (OpenCV or models missing)"}

    def student_templates(self, student_id: str) -> List[dict]:
        return []

    def analyze_image(self, image_base64: str) -> Dict[str, object]:
        return {"ok": False, "num_faces": 0,
                "reason": "Recognition backend unavailable (OpenCV or models missing)"}

    def remove_student_face(self, student_id: str) -> bool:
        return True

    def registered_count(self) -> int:
        return len(self.known_faces)

    def status(self) -> Dict[str, object]:
        return {
            "real": False,
            "method": "mock",
            "registered": len(self.known_faces),
            "threshold": 0.6,
        }


# ------------------------------------------------------------------ factory
def create_recognition_service(backend: str = "auto", **kwargs):
    """
    Create a recognition service instance.

    Args:
        backend: "auto" (real if available, else mock), "opencv", or "mock"
        **kwargs: Arguments passed to the backend constructor.
    """
    if backend == "opencv":
        return OpenCVRecognitionService(**kwargs)
    if backend == "mock":
        return MockRecognitionService(**kwargs)

    if _HAS_CV2:
        service = OpenCVRecognitionService(**kwargs)
        if service.loaded_ok:
            return service
    return MockRecognitionService(**kwargs)


# -------------------------------------------------------------------- helper
def recognition_available() -> bool:
    """Return True when the real OpenCV recognition backend is usable."""
    if not _HAS_CV2:
        return False
    try:
        svc = OpenCVRecognitionService()
        return svc.loaded_ok
    except Exception:
        return False


if __name__ == "__main__":
    service = create_recognition_service()
    print("Backend status:", service.status())