"""
Shared test setup.

Environment overrides MUST be applied before `main` is imported, otherwise
the seeder would touch the live database. Every test therefore runs against a
throwaway SQLite file with seeding and the password migration disabled, and
the recognition service writes its embeddings into a temp directory so the
live face_data/ folder is never modified.
"""
import os
import sys
import tempfile

BACKEND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if BACKEND not in sys.path:
    sys.path.insert(0, BACKEND)

_TMP = tempfile.mkdtemp(prefix="smart-attendance-tests-")
os.environ["ATTENDANCE_DB_URL"] = "sqlite:///{}".format(
    os.path.join(_TMP, "test.db").replace("\\", "/")
)
os.environ["SEED_DEMO_DATA"] = "0"
os.environ["CREDENTIALS_FILE"] = os.path.join(_TMP, "credentials.txt")
os.environ["PBKDF2_ITERATIONS"] = "1000"
os.environ["PASSWORD_MIGRATION"] = "0"

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402
import recognition.service as recognition_service  # noqa: E402


@pytest.fixture(autouse=True)
def tmp_face_data(tmp_path, monkeypatch):
    """Point the recognition service at a temp embeddings store."""
    monkeypatch.setattr(recognition_service, "_EMBEDDINGS_PATH",
                        str(tmp_path / "embeddings.json"))
    monkeypatch.setattr(recognition_service, "_FACE_DATA_DIR", str(tmp_path))
    main._shared_recognition = None
    yield tmp_path
    main._shared_recognition = None


@pytest.fixture()
def client(tmp_face_data):
    with TestClient(main.app) as c:
        yield c


def _purge_all(session):
    """Empty every table so each test starts from a clean slate."""
    session.rollback()
    for table in reversed(main.Base.metadata.sorted_tables):
        session.execute(table.delete())
    session.commit()


@pytest.fixture()
def db():
    session = main.SessionLocal()
    try:
        _purge_all(session)
        yield session
    finally:
        try:
            _purge_all(session)
        finally:
            session.close()


def create_user(db, username, role, password="secret123", branch="ISE",
                full_name=None, email=None):
    user = main.User(
        username=username,
        email=email or "%s@test.local" % username,
        full_name=full_name or username,
        role=role,
        branch=branch,
        hashed_password=main.hash_password(password),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    token = "test-token-%s-%s" % (username, user.id)
    main._token_store_create(db, token, user.id)
    return user, token


def auth(token):
    return {"Authorization": "Bearer %s" % token}
