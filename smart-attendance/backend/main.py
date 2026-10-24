from fastapi import FastAPI, HTTPException, Depends, BackgroundTasks, UploadFile, File, Form, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field, validator
from typing import List, Optional, Dict, Any
import uuid
import os
from datetime import datetime
import json
import hashlib
import hmac
import secrets
import shutil
import sqlite3
import threading

# --- Database Setup ---
from sqlalchemy import (
    create_engine, Column, Integer, String, Float, Date, Boolean, DateTime, Table, ForeignKey,
    func, distinct, text, inspect
)
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, relationship

# Overridable so tests/CI never touch the live database. Default unchanged:
# a relative path resolved against the working directory (backend/attendance.db
# when started with cwd=backend, as test_server.py and the docs do).
SQLALCHEMY_DATABASE_URL = os.environ.get("ATTENDANCE_DB_URL", "sqlite:///./attendance.db")
engine = create_engine(SQLALCHEMY_DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

# --- Models ---
class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True, nullable=True)
    email = Column(String, unique=True, index=True)
    full_name = Column(String)
    role = Column(String, nullable=True)
    branch = Column(String, nullable=True)
    semester = Column(String, nullable=True)
    section = Column(String, nullable=True)
    hashed_password = Column(String, nullable=False)
    is_active = Column(Boolean, default=True)
    profile_photo = Column(String, nullable=True)  # base64 data URL
    
    # Relationships
    student = relationship("Student", back_populates="user", uselist=False)
    teacher = relationship("Teacher", back_populates="user", uselist=False)

class Student(Base):
    __tablename__ = "students"
    id = Column(Integer, primary_key=True, index=True)
    student_id = Column(String, unique=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), unique=True, nullable=False)
    name = Column(String, index=True)
    roll_number = Column(String, index=True)
    branch = Column(String, index=True)
    semester = Column(String, index=True)
    section = Column(String, index=True)
    email = Column(String, unique=True, index=True)
    profile_photo_path = Column(String, nullable=True)
    face_embedding = Column(String, nullable=True)  # JSON string for future AI
    is_active = Column(Boolean, default=True)
    parent_whatsapp = Column(String, nullable=True)
    parent_whatsapp_verified = Column(Boolean, default=False)
    parent_email = Column(String, nullable=True)
    parent_email_verified = Column(Boolean, default=False)
    user = relationship("User", back_populates="student")

    __table_args__ = {"sqlite_autoincrement": True}

class Teacher(Base):
    __tablename__ = "teachers"
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), unique=True, nullable=False)
    name = Column(String, index=True)
    employee_id = Column(String, unique=True, index=True)
    branch = Column(String, index=True)
    semester = Column(String, index=True)
    section = Column(String, index=True)
    email = Column(String, unique=True, index=True)
    profile_photo_path = Column(String, nullable=True)
    user = relationship("User", back_populates="teacher")

    __table_args__ = {"sqlite_autoincrement": True}

class Subject(Base):
    __tablename__ = "subjects"
    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, index=True)
    code = Column(String, index=True)
    branch = Column(String, index=True)
    semester = Column(String, index=True)
    teacher_id = Column(Integer, ForeignKey("teachers.id"), nullable=True)

class BranchSemesterSection(Base):
    __tablename__ = "branch_semester_section"
    id = Column(Integer, primary_key=True, index=True)
    branch = Column(String, index=True, nullable=False)
    semester = Column(String, index=True, nullable=False)
    section = Column(String, index=True, nullable=False)

class AttendanceRecord(Base):
    __tablename__ = "attendance_records"
    id = Column(Integer, primary_key=True, index=True)
    student_id = Column(Integer, ForeignKey("students.id"), nullable=False)
    subject_id = Column(Integer, ForeignKey("subjects.id"), nullable=False)
    date = Column(Date, nullable=False)
    status = Column(String, nullable=False)
    confidence = Column(Float, nullable=True)
    marked_by = Column(Integer, ForeignKey("users.id"), nullable=False)
    marked_at = Column(DateTime, default=datetime.utcnow)

class Notification(Base):
    __tablename__ = "notifications"
    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, nullable=False)
    title = Column(String, nullable=False)
    message = Column(String, nullable=False)
    type = Column(String)
    read = Column(Boolean, default=False)
    created_at = Column(DateTime, default=datetime.utcnow)

class TokenSession(Base):
    __tablename__ = "token_sessions"
    id = Column(Integer, primary_key=True, index=True)
    token = Column(String, unique=True, index=True, nullable=False)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)

# Create tables
Base.metadata.create_all(bind=engine)


def _migrate_students_table():
    """Add the parent-contact columns to a database created before them.

    ``create_all`` never alters an existing table, so a live attendance.db
    would otherwise keep missing the two nullable columns. Both default to
    NULL / False, so no existing row gains an invented contact or relationship.
    """
    try:
        columns = {c["name"] for c in inspect(engine).get_columns("students")}
    except Exception:
        return
    with engine.begin() as conn:
        if "parent_whatsapp" not in columns:
            conn.execute(text("ALTER TABLE students ADD COLUMN parent_whatsapp VARCHAR"))
        if "parent_whatsapp_verified" not in columns:
            conn.execute(text(
                "ALTER TABLE students ADD COLUMN parent_whatsapp_verified BOOLEAN DEFAULT 0"))
        if "parent_email" not in columns:
            conn.execute(text("ALTER TABLE students ADD COLUMN parent_email VARCHAR"))
        if "parent_email_verified" not in columns:
            conn.execute(text(
                "ALTER TABLE students ADD COLUMN parent_email_verified BOOLEAN DEFAULT 0"))


_migrate_students_table()

# --- Token helpers (SQLite-backed, survives restarts) ---
def _token_store_create(db, token, user_id):
    ts = TokenSession(token=token, user_id=user_id)
    db.add(ts)
    db.commit()

def _token_store_lookup(db, token):
    ts = db.query(TokenSession).filter(TokenSession.token == token).first()
    return ts.user_id if ts else None

def _token_store_delete(db, token):
    db.query(TokenSession).filter(TokenSession.token == token).delete()
    db.commit()

def user_to_dict(user, db=None):
    """Serialize a User row without leaking the password hash."""
    data = {
        "id": user.id,
        "username": user.username,
        "email": user.email,
        "full_name": user.full_name,
        "role": user.role,
        "branch": user.branch,
        "semester": user.semester,
        "section": user.section,
        "is_active": user.is_active,
        "profile_photo": user.profile_photo if hasattr(user, "profile_photo") else None,
    }
    if db is not None and user.role == "student":
        student = db.query(Student).filter(Student.user_id == user.id).first()
        if student:
            data["student_id"] = student.student_id
    if db is not None and user.role == "teacher":
        teacher = db.query(Teacher).filter(Teacher.user_id == user.id).first()
        if teacher:
            data["employee_id"] = teacher.employee_id
    return data

def student_to_dict(student):
    return {
        "id": student.id,
        "student_id": student.student_id,
        "user_id": student.user_id,
        "name": student.name,
        "roll_number": student.roll_number,
        "branch": student.branch,
        "semester": student.semester,
        "section": student.section,
        "email": student.email,
        "profile_photo_path": student.profile_photo_path,
        "face_embedding": student.face_embedding,
        "is_active": student.is_active,
        # Parent contact is reported so staff can fill it in; the delivery
        # feature keeps its own masked view for reports.
        "parent_email": student.parent_email,
        "parent_email_verified": bool(student.parent_email_verified),
        "parent_whatsapp": student.parent_whatsapp,
        "parent_whatsapp_verified": bool(student.parent_whatsapp_verified),
    }


def teacher_to_dict(teacher):
    return {
        "id": teacher.id,
        "user_id": teacher.user_id,
        "employee_id": teacher.employee_id,
        "name": teacher.name,
        "email": teacher.email,
        "branch": teacher.branch,
        "semester": teacher.semester,
        "section": teacher.section,
        "profile_photo_path": teacher.profile_photo_path,
    }


# --- Seed data: branches, HODs, teachers, students, subjects, credentials ---
import random as _random
from datetime import timedelta

BRANCHES = ["ISE", "CSE", "ECE", "AIML", "CIVIL"]
BRANCH_CODE = {"ISE": "IS", "CSE": "CS", "ECE": "EC", "AIML": "AI", "CIVIL": "CV"}
SEMESTERS = ["1st Semester", "3rd Semester", "5th Semester", "7th Semester"]
SECTIONS = ["Section A", "Section B"]
SEM_YEAR = {"1st Semester": "26", "3rd Semester": "25", "5th Semester": "24", "7th Semester": "23"}

HODS = {
    "ISE": "DR B I D Kumar",
    "CSE": "DR Mahantesh Matapathi",
    "ECE": "Dr. Veeresh Patil",
    "AIML": "Prof. Shreyas Shivananjappa",
    "CIVIL": "Mr. M Rudramurthy",
}

TEACHER_POOL = {
    "ISE": ["Prof. Uttam", "Prof. Kavya", "Prof. Vivek", "Prof. Nagangouda",
            "Prof. Shruthi", "Prof. Manjunath", "Prof. Anitha", "Prof. Prakash",
            "Prof. Rimpi D"],
    "CSE": ["Prof. Ramesh", "Prof. Kavitha", "Prof. Suresh", "Prof. Deepa",
            "Prof. Nagaraj", "Prof. Rekha", "Prof. Santosh", "Prof. Divya"],
    "ECE": ["Prof. Ashok", "Prof. Bharathi", "Prof. Chandru", "Prof. Deepika",
            "Prof. Eshwar", "Prof. Fathima", "Prof. Girish", "Prof. Hema"],
    "AIML": ["Prof. Imran", "Prof. Jayanthi", "Prof. Kiran", "Prof. Lakshmi",
             "Prof. Mahesh", "Prof. Nandini", "Prof. Omkar", "Prof. Prasanna"],
    "CIVIL": ["Prof. Ravi", "Prof. Sunitha", "Prof. Tejas", "Prof. Usha",
              "Prof. Varun", "Prof. Yashoda", "Prof. Zaheer", "Prof. Anoop"],
}

STUDENT_FIRST = [
    "Aarav", "Ananya", "Rahul", "Priya", "Arjun", "Sneha", "Vikram", "Kavya",
    "Rohan", "Meera", "Ishaan", "Divya", "Manish", "Neha", "Suresh", "Pooja",
    "Amit", "Riya", "Kiran", "Sanya", "Ravi", "Tanvi", "Nikhil", "Anjali",
    "Varun", "Shreya", "Aditya", "Simran", "Harsh", "Pallavi", "Deepak", "Sonali",
    "Gaurav", "Ishita", "Kunal", "Anushka", "Megha", "Bharat", "Chaitra", "Darshan",
    "Esha", "Farhan", "Geeta", "Harini", "Irfan", "Jyothi", "Karthik", "Lavanya",
    "Madhav", "Nithya", "Om", "Praveen", "Qadir", "Rashmi", "Sahana", "Tharun",
]
STUDENT_LAST = [
    "Sharma", "Verma", "Patel", "Reddy", "Nair", "Kulkarni", "Iyer", "Gupta",
    "Singh", "Rao", "Joshi", "Mehta", "Hegde", "Shetty", "Naik", "Gowda",
    "Swamy", "Deshpande", "Bhat", "Kamath", "Prabhu", "Kini", "Nayak", "Kulkarni",
]

ISE_5TH_SEM = [
    ("1AR24IS002", "Anjushree D"), ("1AR24IS003", "Anushree Das"),
    ("1AR24IS004", "Chandana S"), ("1AR24IS006", "Dickson Devason"),
    ("1AR24IS007", "Gouri P Nagonveer"), ("1AR24IS008", "Harshitha"),
    ("1AR24IS009", "Hitesh R"), ("1AR24IS010", "Jharana Bisoyi"),
    ("1AR24IS011", "Joshua M"), ("1AR24IS013", "Lekhana M"),
    ("1AR24IS014", "Mahalakshmi C"), ("1AR24IS015", "Mangalagowri"),
    ("1AR24IS016", "Pavithra K"), ("1AR24IS017", "Pooja M"),
    ("1AR24IS018", "Poornima K"), ("1AR24IS019", "Pragathi B S"),
    ("1AR24IS020", "Prarthana"), ("1AR24IS021", "Preethi A"),
    ("1AR24IS022", "Preethi A C"), ("1AR24IS023", "Priyanka P"),
    ("1AR24IS024", "Puneeth YM"), ("1AR24IS025", "Radhika R"),
    ("1AR24IS026", "Raghavi R"), ("1AR24IS027", "Rakshitha N C"),
    ("1AR24IS028", "Sahana Satish Meti"), ("1AR24IS029", "Sahana Y S"),
    ("1AR24IS030", "Sanjana"), ("1AR24IS031", "Shanavi C"),
    ("1AR24IS032", "Shashank T R"), ("1AR24IS033", "Shravani V S"),
    ("1AR24IS034", "Shreya M Ulavi"), ("1AR24IS035", "Sneha J P"),
    ("1AR24IS036", "Swapna K G"), ("1AR24IS038", "Varshan R"),
    ("1AR24IS039", "Vishwanath K"),
]

ISE_7TH_SEM = [
    ("1AR23IS001", "AISHWARYA SHIDDALINGAYYA MALIMATH"), ("1AR23IS002", "AMRUTHA ARALI"),
    ("1AR23IS003", "AMRUTHA S"), ("1AR23IS004", "AMULYA V"),
    ("1AR23IS005", "ANANYA K"), ("1AR23IS006", "ANVITHA R V"),
    ("1AR23IS007", "BHASKAR"), ("1AR23IS008", "CHANDANA S"),
    ("1AR23IS009", "CHANNAVEER MATRANGI"), ("1AR23IS010", "CHATTU SRILAKSHMI"),
    ("1AR23IS011", "DHEERAJ JADAV C S"), ("1AR23IS012", "GANGADHAR B PALKAR"),
    ("1AR23IS013", "HARSHITHA S"), ("1AR23IS014", "HEMANTH SAGAR S"),
    ("1AR23IS015", "JAHNAVI B L"), ("1AR23IS016", "JYOTHI D P"),
    ("1AR23IS017", "KEERTANA VASANT SIDDAPPAGOL"), ("1AR23IS018", "KOUSHIK M JADAV"),
    ("1AR23IS019", "LIKHITHA R NAIK"), ("1AR23IS020", "MAHESH"),
    ("1AR23IS021", "MANISH GOWDA R"), ("1AR23IS022", "MANU M"),
    ("1AR23IS023", "MEGHA B PATIL"), ("1AR23IS024", "MOHAMMED SAHIL"),
    ("1AR23IS025", "NIRANJAN P"), ("1AR23IS026", "NIRVAN M"),
    ("1AR23IS027", "NITHIN B N"), ("1AR23IS028", "NIVEDITHA C A"),
    ("1AR23IS029", "OMKAR T P"), ("1AR23IS030", "RENISHA GONSALVES"),
    ("1AR23IS031", "RESHMA KAREGOUDA MULIMANI"), ("1AR23IS032", "SAGAR S P"),
    ("1AR23IS033", "SANJANA S N"), ("1AR23IS034", "SATVIK"),
    ("1AR23IS035", "SHARATH A S"), ("1AR23IS036", "SHASHANK B G"),
    ("1AR23IS037", "SHIV KUMAR T"), ("1AR23IS038", "SHIVALEELA A"),
    ("1AR23IS039", "SHIVARAMA R"), ("1AR23IS040", "SHRAVANI N"),
    ("1AR23IS041", "SHREYA V"), ("1AR23IS042", "SIMON LEO AELXANDER"),
    ("1AR23IS043", "SONAL C R"), ("1AR23IS044", "SONU M A"),
    ("1AR23IS045", "SRUJANA C"), ("1AR23IS046", "SRUJANA DESHPANDE"),
    ("1AR23IS047", "SUMAN C"), ("1AR23IS048", "SUSHMITHA L"),
    ("1AR23IS049", "SUSHMITHA Y S"), ("1AR23IS050", "SWASTHIK D"),
    ("1AR23IS051", "TANUSH G BALEGAR"), ("1AR23IS052", "TEJASWINI NAGARAJ KODER"),
    ("1AR23IS053", "UDAY D"), ("1AR23IS054", "VAISHNAVI K R"),
    ("1AR23IS055", "VIVEK M HEMADE"), ("1AR23IS056", "YASHVANTHA N"),
    ("1AR23IS057", "YASHWANTH B N"), ("1AR23IS058", "YOGESHWARI R"),
]

ISE_7TH_SUBJECTS = [
    ("BIS702", "Parallel Computing"), ("BIS703", "Information & Network Security"),
    ("BIS704", "Big Data Analytics"),
    ("BIS714A", "Deep Learning"), ("BIS755x", "E-Waste Management"),
    ("BIS786", "Major Project Phase-II"),
    ("BISL702", "Parallel Computing Lab"), ("BISL703", "Network Security Lab"),
    ("BISL714A", "Deep Learning Lab"),
]

# Verified ISE 7th-semester faculty allocation (subject code -> faculty name),
# taken from the college subject-allocation sheet. Names are matched against the
# existing teacher accounts:
#   "Prof. Utam H"     -> Prof. Uttam       (T-IS-01)
#   "Prof. Nagangouda K H" -> Prof. Nagangouda (T-IS-04)
#   "Prof. Kavya A D"  -> Prof. Kavya       (T-IS-02)
#   "Prof. Rimpi D"    -> Prof. Rimpi D     (T-IS-09)
#   "Dr. Kumar B I D"  -> the ISE HOD "DR B I D Kumar", who is linked to a
#                         teacher profile in seed_demo_data() so these lectures
#                         can be assigned without a duplicate faculty account.
# "Big Data Analytics" uses code BIS704, following the existing BIS7xx pattern
# for 7th-semester theory subjects.  Everything else (labs, 1st/3rd/5th semester)
# is distributed by _assign_ise_faculty() so every ISE teacher lands on 4-5
# distinct subjects.
ISE_7TH_FACULTY = {
    "BIS702": "DR B I D Kumar",     # Parallel Computing
    "BIS703": "Prof. Nagangouda",   # Information & Network Security
    "BIS704": "Prof. Uttam",        # Big Data Analytics
    "BIS714A": "Prof. Rimpi D",     # Deep Learning
    "BIS755x": "Prof. Kavya",       # E-Waste Management
    "BIS786": "DR B I D Kumar",     # Major Project Phase-II
}

BRANCH_SUBJECTS = {
    "ISE": [
        ("BIS101", "Discrete Mathematics"), ("BIS102", "C Programming for Problem Solving"),
        ("BIS103", "Digital Design & Computer Organization"), ("BIS104", "Data Structures"),
        ("BIS201", "Object Oriented Programming with Java"), ("BIS202", "Computer Organization & Architecture"),
        ("BIS203", "Database Management Systems"), ("BIS204", "Operating Systems"),
        ("BIS301", "Computer Networks"), ("BIS302", "Software Engineering"),
        ("BIS303", "Design & Analysis of Algorithms"), ("BIS304", "Web Technologies"),
        ("BIS401", "Machine Learning"), ("BIS402", "Cloud Computing"),
        ("BISL101", "C Programming Lab"), ("BISL104", "Data Structures Lab"),
        ("BISL201", "Java Programming Lab"), ("BISL203", "DBMS Lab"),
        ("BISL301", "Computer Networks Lab"), ("BISL401", "Machine Learning Lab"),
    ],
    "CSE": [
        ("BCS101", "Discrete Mathematics"), ("BCS102", "Problem Solving with C"),
        ("BCS103", "Digital Logic Design"), ("BCS104", "Data Structures"),
        ("BCS201", "Object Oriented Programming"), ("BCS202", "Computer Organization"),
        ("BCS203", "DBMS"), ("BCS204", "Operating Systems"),
        ("BCS301", "Computer Networks"), ("BCS302", "Software Engineering"),
        ("BCS303", "Analysis of Algorithms"), ("BCS304", "Web Programming"),
        ("BCS401", "Machine Learning"), ("BCS402", "Cloud Computing"),
        ("BCSL101", "C Programming Lab"), ("BCSL203", "DBMS Lab"),
        ("BCSL301", "Networks Lab"), ("BCSL401", "ML Lab"),
    ],
    "ECE": [
        ("BEC101", "Engineering Mathematics"), ("BEC102", "Basic Electronics"),
        ("BEC103", "Circuit Analysis"), ("BEC104", "Digital Electronics"),
        ("BEC201", "Signals & Systems"), ("BEC202", "Microprocessors"),
        ("BEC203", "Analog Communication"), ("BEC204", "Control Systems"),
        ("BEC301", "Digital Communication"), ("BEC302", "VLSI Design"),
        ("BEC303", "Embedded Systems"), ("BEC304", "Antennas & Propagation"),
        ("BEC401", "Wireless Communication"), ("BEC402", "IoT Systems"),
        ("BECL102", "Basic Electronics Lab"), ("BECL202", "Microprocessors Lab"),
        ("BECL301", "Communication Lab"), ("BECL401", "IoT Lab"),
    ],
    "AIML": [
        ("BAI101", "Mathematics for AI"), ("BAI102", "Programming with Python"),
        ("BAI103", "Data Structures & Algorithms"), ("BAI104", "Statistics & Probability"),
        ("BAI201", "Artificial Intelligence"), ("BAI202", "Machine Learning"),
        ("BAI203", "Data Mining"), ("BAI204", "Operating Systems"),
        ("BAI301", "Deep Learning"), ("BAI302", "Natural Language Processing"),
        ("BAI303", "Computer Vision"), ("BAI304", "Reinforcement Learning"),
        ("BAI401", "Generative AI"), ("BAI402", "MLOps"),
        ("BAIL102", "Python Programming Lab"), ("BAIL202", "Machine Learning Lab"),
        ("BAIL301", "Deep Learning Lab"), ("BAIL302", "NLP Lab"),
    ],
    "CIVIL": [
        ("BCV101", "Engineering Mathematics"), ("BCV102", "Engineering Mechanics"),
        ("BCV103", "Building Materials"), ("BCV104", "Surveying"),
        ("BCV201", "Strength of Materials"), ("BCV202", "Fluid Mechanics"),
        ("BCV203", "Concrete Technology"), ("BCV204", "Structural Analysis"),
        ("BCV301", "Geotechnical Engineering"), ("BCV302", "Transportation Engineering"),
        ("BCV303", "Environmental Engineering"), ("BCV304", "Hydrology"),
        ("BCV401", "Earthquake Engineering"), ("BCV402", "Construction Management"),
        ("BCVL102", "Surveying Lab"), ("BCVL202", "Strength of Materials Lab"),
        ("BCVL301", "Geotechnical Lab"), ("BCVL401", "CAD Lab"),
    ],
}

CREDENTIALS_FILE = os.environ.get("CREDENTIALS_FILE") or os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "credentials.txt"
)

# --- Password hashing (PBKDF2-HMAC-SHA256) ---
# Stored format: pbkdf2_sha256$<iterations>$<salt>$<hexdigest>
# Legacy rows (created before this upgrade) hold the raw password; they are
# accepted by verify_password() and re-hashed transparently (on login and by
# the background migration started on app startup).
PASSWORD_PREFIX = "pbkdf2_sha256$"
PBKDF2_ITERATIONS = int(os.environ.get("PBKDF2_ITERATIONS", "50000"))


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac(
        "sha256", str(password).encode("utf-8"), salt.encode("utf-8"), PBKDF2_ITERATIONS
    )
    return "%s%d$%s$%s" % (PASSWORD_PREFIX, PBKDF2_ITERATIONS, salt, dk.hex())


def is_hashed_password(stored) -> bool:
    return isinstance(stored, str) and stored.startswith(PASSWORD_PREFIX)


def verify_password(password: str, stored: str) -> bool:
    """Check a password against a PBKDF2 hash (or a legacy plaintext row)."""
    if stored is None:
        return False
    if not is_hashed_password(stored):
        # Pre-migration row: constant-time compare against the stored value.
        return hmac.compare_digest(str(stored), str(password))
    try:
        _prefix, iterations, salt, digest = stored.split("$", 3)
        dk = hashlib.pbkdf2_hmac(
            "sha256", str(password).encode("utf-8"), salt.encode("utf-8"), int(iterations)
        )
        return hmac.compare_digest(dk.hex(), digest)
    except (ValueError, TypeError):
        return False


def _staff_password_chain():
    """
    Deterministic demo passwords for the seeded staff.

    Reproduces seed_demo_data()'s generation order exactly, so the values
    always match credentials.txt. Returns ({("hod", branch)|"teacher": pwd},
    used_passwords_set).
    """
    used = set()
    chain = {}
    for branch, name in HODS.items():
        chain[("hod", branch)] = _password_for(name, used)
    for branch in BRANCHES:
        for tname in TEACHER_POOL[branch]:
            chain[("teacher", tname)] = _password_for(tname, used)
    return chain, used


def _staff_password_pool(db, exclude_user_id=None):
    """
    Candidate password pool for NEW staff accounts: the seeded demo chain plus
    the base names of staff created through the UI (whose passwords cannot be
    recovered from the hash).
    """
    _chain, used = _staff_password_chain()
    query = db.query(User).filter(User.role.in_(["hod", "teacher"]))
    if exclude_user_id is not None:
        query = query.filter(User.id != exclude_user_id)
    for u in query.all():
        used.add(_first_name(u.full_name) + "@26")
    return used


def _first_name(name):
    cleaned = name or ""
    for prefix in ("Prof. ", "Prof.", "Dr. ", "Dr.", "Mr. ", "Mrs. ", "Ms. "):
        cleaned = cleaned.replace(prefix, "")
    parts = cleaned.split()
    return parts[0].lower() if parts else "student"

def _slug(name):
    cleaned = name or ""
    for prefix in ("Prof. ", "Prof.", "Dr. ", "Dr.", "Mr. ", "Mrs. ", "Ms. "):
        cleaned = cleaned.replace(prefix, "")
    return cleaned.strip().lower().replace(" ", ".").replace("/", "-").replace(",", "")

def _password_for(name, used_passwords):
    base = _first_name(name)
    pwd = f"{base}@26"
    n = 2
    while pwd in used_passwords:
        pwd = f"{base}{n}@26"
        n += 1
    used_passwords.add(pwd)
    return pwd

def _student_password(sid):
    return f"{sid}@26"

def _make_user(db, username, email, full_name, role, branch, semester, section, password):
    user = db.query(User).filter((User.username == username) | (User.email == email)).first()
    if user:
        # Existing accounts keep their stored password (hashing it again on
        # every restart would be slow and would wipe user-changed passwords).
        # Legacy plaintext rows are left in place for the startup migration /
        # upgrade-on-login to convert.
        user.full_name = full_name
        user.role = role
        user.branch = branch
        user.semester = semester
        user.section = section
        return user
    user = User(username=username, email=email, full_name=full_name, role=role,
                branch=branch, semester=semester, section=section,
                hashed_password=hash_password(password))
    db.add(user)
    db.flush()
    return user


def _assign_ise_faculty(db, teacher_id_by_name=None):
    """
    Give every ISE subject a teacher, in two stages.

    1. The verified 7th-semester allocation (ISE_7TH_FACULTY) is applied first
       and is never overridden by stage 2.
    2. Every remaining ISE subject (1st/3rd/5th semester plus the 7th-semester
       labs) is handed to whichever ISE teacher currently holds the fewest
       subjects, so each ISE teacher ends up with 4-5 distinct subjects across
       all semesters instead of the 7-9 the old round-robin seeding produced.

    Fully deterministic: the same rows always produce the same result, so
    restarting the server never shuffles the allocation.

    Returns ({teacher name: subject count}, [(code, name, missing faculty)]).
    """
    ise_teachers = db.query(Teacher).filter(
        Teacher.branch == "ISE").order_by(Teacher.id).all()
    if not ise_teachers:
        return {}, []

    counts = {t.id: 0 for t in ise_teachers}
    by_name = {t.name: t.id for t in ise_teachers}
    if teacher_id_by_name:
        for tname, tid in teacher_id_by_name.items():
            by_name.setdefault(tname, tid)

    subjects = db.query(Subject).filter(
        Subject.branch == "ISE").order_by(Subject.id).all()

    # Stage 1: verified 7th-semester allocation.
    fixed = set()
    unresolved = []
    for subj in subjects:
        if subj.semester != "7th Semester":
            continue
        faculty = ISE_7TH_FACULTY.get(subj.code)
        if not faculty:
            continue
        tid = by_name.get(faculty)
        if tid is None:
            # No account matches: leave the subject exactly as stored, exclude it
            # from the rebalance and report it instead of guessing.
            unresolved.append((subj.code, subj.name, faculty))
            fixed.add(subj.id)
            continue
        subj.teacher_id = tid
        counts[tid] += 1
        fixed.add(subj.id)

    # Stage 2: balance everything else over the ISE roster.
    remaining = [s for s in subjects if s.id not in fixed]
    # Deterministic order: newest subject first so an added subject (e.g. Big
    # Data Analytics) does not silently reshuffle the whole allocation.
    for subj in sorted(remaining, key=lambda s: (s.semester, -s.id)):
        target = min(ise_teachers, key=lambda t: (counts[t.id], t.id))
        subj.teacher_id = target.id
        counts[target.id] += 1

    names = {t.id: t.name for t in ise_teachers}
    return {names[tid]: n for tid, n in counts.items()}, unresolved


def seed_demo_data():
    db = SessionLocal()
    try:
        used_passwords = set()
        rng = _random.Random(42)

        # --- Admin (Principal) account ---
        admin = _make_user(db, "principal", "principal@nvidia.edu", "Principal",
                           "admin", None, None, None, "admin123")

        # --- HODs (one per branch, fixed names) ---
        hod_records = []
        for branch, name in HODS.items():
            uname = f"hod.{BRANCH_CODE[branch].lower()}"
            email = f"{uname}@nvidia.edu"
            pwd = _password_for(name, used_passwords)
            _make_user(db, uname, email, name, "hod", branch, None, None, pwd)
            hod_records.append({
                "role": "hod", "branch": branch, "name": name,
                "username": uname, "email": email, "password": pwd,
            })
        db.commit()

        # --- Teachers (ISE: named set + fillers, others: 8 each) ---
        teacher_records = []
        teacher_ids = {}
        teacher_id_by_name = {}
        for branch in BRANCHES:
            names = TEACHER_POOL[branch]
            branch_teacher_ids = []
            for i, tname in enumerate(names):
                uname = f"{_slug(tname)}.{BRANCH_CODE[branch].lower()}"
                email = f"{uname}@nvidia.edu"
                emp_id = f"T-{BRANCH_CODE[branch]}-{i + 1:02d}"
                pwd = _password_for(tname, used_passwords)
                u = _make_user(db, uname, email, tname, "teacher", branch, None, None, pwd)
                t = db.query(Teacher).filter(Teacher.user_id == u.id).first()
                if not t:
                    t = Teacher(user_id=u.id, employee_id=emp_id, name=tname,
                                email=email, branch=branch, semester=None, section=None)
                    db.add(t)
                    db.flush()
                branch_teacher_ids.append(t.id)
                teacher_id_by_name[tname] = t.id
                teacher_records.append({
                    "role": "teacher", "branch": branch, "name": tname,
                    "employee_id": emp_id, "username": uname,
                    "email": email, "password": pwd,
                })
            teacher_ids[branch] = branch_teacher_ids
        db.commit()

        # Link the ISE HOD ("DR B I D Kumar") to a teacher profile so the
        # verified ISE 7th-semester lectures assigned to "Dr. Kumar B I D" can
        # be stored against the existing HOD account. No new faculty is created.
        ise_hod_user = db.query(User).filter(User.username == "hod.is").first()
        if ise_hod_user is not None:
            hod_teacher = db.query(Teacher).filter(Teacher.user_id == ise_hod_user.id).first()
            if not hod_teacher:
                hod_teacher = Teacher(user_id=ise_hod_user.id, name=HODS["ISE"],
                                      employee_id="T-IS-HOD", branch="ISE",
                                      email=ise_hod_user.email)
                db.add(hod_teacher)
                db.flush()
            teacher_id_by_name[HODS["ISE"]] = hod_teacher.id
        db.commit()

        # --- Branch / Semester / Section combos ---
        def _sections_for(branch, sem):
            # ISE runs a single fixed section (1st/3rd are trimmed to one class
            # of 30/60 students, 5th and 7th use their real rosters).
            if branch == "ISE":
                return ["Section A"]
            return SECTIONS

        for branch in BRANCHES:
            for sem in SEMESTERS:
                for sec in _sections_for(branch, sem):
                    existing = db.query(BranchSemesterSection).filter(
                        BranchSemesterSection.branch == branch,
                        BranchSemesterSection.semester == sem,
                        BranchSemesterSection.section == sec).first()
                    if not existing:
                        db.add(BranchSemesterSection(branch=branch, semester=sem, section=sec))
        db.commit()

        # --- Students ---
        def build_student_record(branch, semester, sid, name, section):
            email = f"{sid}@nvidia.edu"
            pwd = _student_password(sid)
            u = _make_user(db, sid, email, name, "student", branch, semester, section, pwd)
            stu = db.query(Student).filter(Student.student_id == sid).first()
            if not stu:
                stu = Student(student_id=sid, user_id=u.id, name=name, roll_number=sid,
                              branch=branch, semester=semester, section=section, email=email)
                db.add(stu)
                db.flush()
            return stu

        for branch in BRANCHES:
            code = BRANCH_CODE[branch]
            for sem in SEMESTERS:
                year = SEM_YEAR[sem]
                if branch == "ISE" and sem == "5th Semester":
                    for i, (sid, nm) in enumerate(ISE_5TH_SEM):
                        build_student_record(branch, sem, sid, nm, SECTIONS[0])
                    continue
                if branch == "ISE" and sem == "7th Semester":
                    for i, (sid, nm) in enumerate(ISE_7TH_SEM):
                        build_student_record(branch, sem, sid, nm, SECTIONS[0])
                    continue
                # Idempotency guard: a class that already has students is never
                # appended to again (this used to add 30-40 new students per
                # section on EVERY restart, inflating the DB to 29k rows).
                if db.query(Student).filter(
                    Student.branch == branch, Student.semester == sem
                ).count() > 0:
                    continue
                for sec in SECTIONS:
                    count = rng.randint(40, 50)
                    existing_seq = db.query(Student).filter(
                        Student.branch == branch, Student.semester == sem).count()
                    for i in range(count):
                        first = STUDENT_FIRST[rng.randrange(len(STUDENT_FIRST))]
                        last = STUDENT_LAST[rng.randrange(len(STUDENT_LAST))]
                        name = f"{first} {last}"
                        seq = existing_seq + i + 1
                        sid = f"1AR{year}{code}{seq:03d}"
                        while db.query(Student).filter(Student.student_id == sid).first():
                            seq += 1
                            sid = f"1AR{year}{code}{seq:03d}"
                        build_student_record(branch, sem, sid, name, sec)
        db.commit()

        # --- Trim the ISE 1st/3rd batches to the agreed class size ---
        # Older builds of this seeder appended a fresh batch of students on every
        # restart, so ISE 1st semester ended up with 1748 rows and 3rd with 1587
        # while every other class sits at ~100 and the real ISE rosters are 35/58.
        # Every class size shown in the app is read from stored data, so these
        # batches are reduced to 30 / 60 students (lowest USN, Section A) together
        # with their attendance rows, accounts and login sessions.  Idempotent:
        # it only runs while a class is above its target.
        for trim_sem, target in (("1st Semester", 30), ("3rd Semester", 60)):
            total = db.query(Student).filter(
                Student.branch == "ISE", Student.semester == trim_sem).count()
            if total > target:
                p = {"b": "ISE", "s": trim_sem, "n": target}
                keep = ("(select id from students where branch=:b and semester=:s "
                        "order by id limit :n)")
                keep_u = ("(select user_id from students where branch=:b and semester=:s "
                          "order by id limit :n)")
                cls = ("(select id from students where branch=:b and semester=:s)")
                cls_u = ("(select user_id from students where branch=:b and semester=:s)")
                db.execute(text(
                    "delete from attendance_records where student_id in %s and "
                    "student_id not in %s" % (cls, keep)), p)
                for table, col, drop in (("notifications", "user_id", cls_u),
                                         ("token_sessions", "user_id", cls_u),
                                         ("users", "id", cls_u)):
                    db.execute(text(
                        "delete from %s where %s in %s and %s not in %s"
                        % (table, col, drop, col, keep_u)), p)
                db.execute(text(
                    "delete from students where branch=:b and semester=:s and "
                    "id not in %s" % keep), p)
                db.query(Student).filter(
                    Student.branch == "ISE", Student.semester == trim_sem
                ).update({"section": SECTIONS[0]}, synchronize_session=False)
            db.query(BranchSemesterSection).filter(
                BranchSemesterSection.branch == "ISE",
                BranchSemesterSection.semester == trim_sem,
                BranchSemesterSection.section != SECTIONS[0]
            ).delete(synchronize_session=False)
        db.commit()

        # --- Subjects (ISE 7th sem fixed; others random 5-9 incl. lab) ---
        for branch in BRANCHES:
            tids = teacher_ids[branch]
            for sem in SEMESTERS:
                if branch == "ISE" and sem == "7th Semester":
                    subs = ISE_7TH_SUBJECTS
                else:
                    pool = BRANCH_SUBJECTS[branch]
                    start = rng.randrange(0, max(1, len(pool) - 6))
                    count = rng.randint(5, min(9, len(pool)))
                    subs = list(pool[start:start + count])
                    if not any("Lab" in nm for _, nm in subs):
                        labs = [s for s in pool if "Lab" in s[1]]
                        if labs:
                            subs[-1] = labs[start % len(labs)]
                for j, (code, nm) in enumerate(subs):
                    existing = db.query(Subject).filter(
                        Subject.code == code, Subject.branch == branch,
                        Subject.semester == sem).first()
                    if branch == "ISE":
                        # ISE faculty is decided in one place by
                        # _assign_ise_faculty() below: the verified 7th-semester
                        # allocation first, then a balanced 4-5 subjects per
                        # teacher over every other semester.
                        if not existing:
                            db.add(Subject(name=nm, code=code, branch=branch,
                                           semester=sem, teacher_id=None))
                        continue
                    teacher_id = tids[j % len(tids)] if tids else None
                    if existing:
                        existing.teacher_id = teacher_id
                    else:
                        db.add(Subject(name=nm, code=code, branch=branch,
                                       semester=sem, teacher_id=teacher_id))
        db.commit()

        # --- ISE: verified 7th-sem allocation + balanced 4-5 subject workload ---
        ise_workload, ise_unresolved = _assign_ise_faculty(db, teacher_id_by_name)
        db.commit()
        if ise_unresolved:
            print("[seed] ISE faculty left unassigned (no matching account):",
                  ise_unresolved)
        off_target = {n: c for n, c in ise_workload.items() if not 4 <= c <= 5}
        if off_target:
            print("[seed] ISE teacher(s) outside the 4-5 subject range:",
                  off_target)

        # --- Seed attendance history (past 5 days, ~85% present) ---
        if db.query(AttendanceRecord).count() == 0:
            all_subjects = db.query(Subject).all()
            all_students = db.query(Student).all()
            today = datetime.now().date()
            marker = admin.id
            for subj in all_subjects:
                class_students = [s for s in all_students
                                  if s.branch == subj.branch and s.semester == subj.semester]
                if not class_students:
                    continue
                for days_ago in range(1, 6):
                    d = today - timedelta(days=days_ago)
                    if d.weekday() >= 5:
                        continue
                    for s in class_students:
                        seed = (abs(hash(f"{s.id}-{subj.id}-{d}")) % 100)
                        present = seed < 85
                        status = "present" if present else "absent"
                        db.add(AttendanceRecord(
                            student_id=s.id, subject_id=subj.id, date=d,
                            status=status,
                            confidence=0.92 if present else None,
                            marked_by=marker,
                        ))
            # Today's attendance (partial) so dashboards show live numbers
            for subj in all_subjects:
                class_students = [s for s in all_students
                                  if s.branch == subj.branch and s.semester == subj.semester]
                if not class_students:
                    continue
                for i, s in enumerate(class_students[:30]):
                    present = i % 7 != 0  # ~85% present today
                    existing = db.query(AttendanceRecord).filter(
                        AttendanceRecord.student_id == s.id,
                        AttendanceRecord.subject_id == subj.id,
                        AttendanceRecord.date == today).first()
                    if not existing:
                        db.add(AttendanceRecord(
                            student_id=s.id, subject_id=subj.id, date=today,
                            status="present" if present else "absent",
                            confidence=0.93 if present else None,
                            marked_by=marker,
                        ))
        db.commit()

        # --- Seed role-based notifications ---
        all_hods = db.query(User).filter(User.role == "hod").all()
        some_teachers = db.query(User).filter(User.role == "teacher").limit(3).all()
        some_students = db.query(User).filter(User.role == "student").limit(5).all()
        notif_plans = [
            (admin.id, "College Overview", "Overall attendance is steady this month.", "info"),
            (admin.id, "Reports Ready", "Monthly college attendance report is ready.", "info"),
        ]
        for hod in all_hods:
            notif_plans.append((hod.id, "Low Attendance Alert",
                                f"Some students in {hod.branch} are below 75% attendance.", "warning"))
        for t in some_teachers:
            notif_plans.append((t.id, "Attendance Saved",
                                f"Attendance for a {t.branch} class saved successfully.", "success"))
        for s in some_students:
            notif_plans.append((s.id, "Attendance Warning",
                                "Please maintain attendance above 75%.", "warning"))
        for uid, title, msg, ntype in notif_plans:
            existing = db.query(Notification).filter(
                Notification.user_id == uid, Notification.title == title).first()
            if not existing:
                db.add(Notification(user_id=uid, title=title, message=msg,
                                    type=ntype, created_at=datetime.utcnow()))
        db.commit()

        # --- Write credentials.txt (HODs + teachers only) ---
        try:
            lines = [
                "SMART ATTENDANCE SYSTEM - LOGIN CREDENTIALS",
                "============================================",
                "",
                "HOds (Department Heads)",
                "----------------------",
                f"{'Branch':8} {'Name':38} {'Username':18} {'Password':18}",
            ]
            for r in hod_records:
                lines.append(f"{r['branch']:8} {r['name']:38} {r['username']:18} {r['password']:18}")
            lines += ["", "Teachers", "--------",
                      f"{'Branch':8} {'Employee ID':13} {'Name':28} {'Username':25} {'Password':18}"]
            for r in teacher_records:
                lines.append(f"{r['branch']:8} {r['employee_id']:13} {r['name']:28} "
                             f"{r['username']:25} {r['password']:18}")
            lines += [
                "",
                "Students",
                "--------",
                "Username = USN (e.g. 1AR24IS002). Password = <USN>@26 (e.g. 1ar24is002@26).",
                "All student accounts follow this pattern - see the Student register page for details.",
                "",
            ]
            with open(CREDENTIALS_FILE, "w", encoding="utf-8") as f:
                f.write("\n".join(lines))
        except Exception as e:
            print("Could not write credentials file:", e)
    finally:
        db.close()

if os.environ.get("SEED_DEMO_DATA", "1").lower() not in ("0", "false", "no"):
    seed_demo_data()

# --- FastAPI App ---
app = FastAPI(title="TrinetraX: Intelligent Attendance Management System", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "http://localhost:8000",
        "http://127.0.0.1:8000",
    ],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

security = HTTPBearer()


# --- One-time migration: hash legacy plaintext password rows ---
def _sqlite_db_path():
    url = SQLALCHEMY_DATABASE_URL
    if not url.startswith("sqlite"):
        return None
    if "///" not in url:
        return None
    path = url.split("///", 1)[1]
    if not path or path == ":memory:":
        return None
    return path


def _migrate_plaintext_passwords():
    """
    Hash every users.hashed_password row that is still plaintext.

    Takes a consistent SQLite backup FIRST (required before any bulk password
    modification), then hashes in memory and writes with a conditional UPDATE
    so a concurrent login upgrade is never clobbered. Runs in a background
    thread at startup - verify_password() still accepts legacy rows meanwhile.
    """
    path = _sqlite_db_path()
    if not path or not os.path.exists(path):
        return
    try:
        con = sqlite3.connect(path, timeout=30)
        rows = con.execute(
            "select id, hashed_password from users "
            "where hashed_password not like 'pbkdf2_sha256$%'"
        ).fetchall()
        if not rows:
            con.close()
            return

        ts = datetime.now().strftime("%Y%m%d-%H%M%S")
        backup = "%s.bak-pwdmigrate-%s" % (path, ts)
        try:
            dst = sqlite3.connect(backup)
            con.backup(dst)
            dst.close()
        except Exception as exc:
            print("[password-migration] backup failed, aborting:", exc)
            con.close()
            return

        print("[password-migration] hashing %d legacy password row(s) (backup: %s)"
              % (len(rows), backup))
        hashed = [(hash_password(pw), uid, pw) for uid, pw in rows]
        con.executemany(
            "update users set hashed_password = ? where id = ? and hashed_password = ?",
            hashed,
        )
        con.commit()
        print("[password-migration] done - %d row(s) converted to PBKDF2" % len(hashed))
        con.close()
    except Exception as exc:  # pragma: no cover - never block startup
        print("[password-migration] failed:", exc)


@app.on_event("startup")
def _start_password_migration():
    if os.environ.get("PASSWORD_MIGRATION", "1").lower() in ("0", "false", "no"):
        return
    threading.Thread(target=_migrate_plaintext_passwords, daemon=True).start()

# --- Dependency ---
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db = Depends(get_db)
):
    token = credentials.credentials
    user_id = _token_store_lookup(db, token)
    if user_id is None:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=401, detail="User not found")
    return user_to_dict(user, db)

# --- Pydantic Schemas ---
class LoginRequest(BaseModel):
    username: str  # accepts username, email, USN or employee id
    password: str

class RegisterHODRequest(BaseModel):
    branch: str
    name: Optional[str] = None

class RegisterTeacherRequest(BaseModel):
    branch: str
    name: str
    employee_id: Optional[str] = None

class RegisterStudentRequest(BaseModel):
    name: str
    branch: str
    semester: str
    section: str
    student_id: Optional[str] = None  # auto-generated when omitted
    email: Optional[str] = None

class UserUpdateRequest(BaseModel):
    full_name: Optional[str] = None
    email: Optional[str] = None
    password: Optional[str] = None
    branch: Optional[str] = None
    semester: Optional[str] = None
    section: Optional[str] = None

class StudentUpdateRequest(BaseModel):
    name: Optional[str] = None
    email: Optional[str] = None
    roll_number: Optional[str] = None
    branch: Optional[str] = None
    semester: Optional[str] = None
    section: Optional[str] = None
    password: Optional[str] = None
    # Parent / guardian contact, used by the parent-report delivery feature.
    parent_email: Optional[str] = None
    parent_email_verified: Optional[bool] = None
    parent_whatsapp: Optional[str] = None
    parent_whatsapp_verified: Optional[bool] = None

class TeacherUpdateRequest(BaseModel):
    name: Optional[str] = None
    email: Optional[str] = None
    branch: Optional[str] = None
    semester: Optional[str] = None
    section: Optional[str] = None
    password: Optional[str] = None

class BranchSemesterSectionCreate(BaseModel):
    branch: str
    semester: str
    section: str

class StudentCreate(BaseModel):
    student_id: str
    name: str
    roll_number: str
    branch: str
    semester: str
    section: str
    email: str
    profile_photo_path: Optional[str] = None
    face_embedding: Optional[str] = None
    # Parent / guardian contact, used by the parent-report delivery feature.
    parent_email: Optional[str] = None
    parent_email_verified: Optional[bool] = None
    parent_whatsapp: Optional[str] = None
    parent_whatsapp_verified: Optional[bool] = None

class TeacherCreate(BaseModel):
    employee_id: str
    name: str
    email: str
    branch: str
    semester: str
    section: str
    profile_photo_path: Optional[str] = None

class SubjectCreate(BaseModel):
    name: str
    code: str
    branch: str
    semester: str
    teacher_id: Optional[int] = None

class AttendanceUpdate(BaseModel):
    status: str
    confidence: Optional[float] = None

class RegisterFaceRequest(BaseModel):
    student_id: str = Field(..., min_length=1)
    name: Optional[str] = None
    image_base64: str
    # Optional guided multi-angle capture. When present every angle is validated
    # before anything is stored, and a single failure stores nothing at all.
    images: Optional[List[Dict[str, str]]] = None

class RemoveFaceRequest(BaseModel):
    student_id: str

class AnalyzeImageRequest(BaseModel):
    image_base64: str
    subject_id: Optional[int] = None
    section: Optional[str] = None

# --- Shared recognition service instance ---
from recognition.service import create_recognition_service, RecognitionError
import bulk_faces as bulk
_shared_recognition = None

def get_recognition_service():
    global _shared_recognition
    if _shared_recognition is None:
        _shared_recognition = create_recognition_service(backend="auto")
    return _shared_recognition

# --- Auth ---
def _find_user_by_identifier(db, identifier):
    """Find a User by username, email, student USN or teacher employee id."""
    user = db.query(User).filter(
        (User.username == identifier) | (User.email == identifier)
    ).first()
    if user:
        return user
    stu = db.query(Student).filter(Student.student_id == identifier).first()
    if stu:
        return db.query(User).filter(User.id == stu.user_id).first()
    tea = db.query(Teacher).filter(Teacher.employee_id == identifier).first()
    if tea:
        return db.query(User).filter(User.id == tea.user_id).first()
    return None

@app.post("/api/auth/login")
async def login(credentials: LoginRequest, db = Depends(get_db)):
    user = _find_user_by_identifier(db, credentials.username)
    if not user or not verify_password(credentials.password, user.hashed_password):
        raise HTTPException(status_code=401, detail="Invalid username or password")
    if not is_hashed_password(user.hashed_password):
        # Upgrade legacy plaintext row to PBKDF2 on successful login.
        user.hashed_password = hash_password(credentials.password)
        db.commit()
    token = str(uuid.uuid4())
    _token_store_create(db, token, user.id)
    return {"access_token": token, "token_type": "bearer", "user": user_to_dict(user, db)}

@app.get("/api/auth/check")
async def check_registered(
    role: str,
    branch: Optional[str] = None,
    db = Depends(get_db)
):
    """Check who is already registered for a role (+branch)."""
    query = db.query(User).filter(User.role == role)
    if branch:
        query = query.filter(User.branch == branch)
    users = query.all()
    out = []
    for u in users:
        d = user_to_dict(u, db)
        out.append(d)
    return {"role": role, "branch": branch, "users": out, "registered": len(out)}

@app.get("/api/auth/credentials")
async def list_credentials(
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """
    List HOD + teacher accounts (usernames / employee ids only).

    Passwords are NEVER returned here - the endpoint used to expose every
    staff password to unauthenticated callers. Demo passwords are written to
    credentials.txt by the seeder instead.
    """
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    hods = db.query(User).filter(User.role == "hod").all()
    teachers = db.query(User).filter(User.role == "teacher").all()
    out = []
    for u in list(hods) + list(teachers):
        d = user_to_dict(u, db)
        if u.role == "teacher":
            tea = db.query(Teacher).filter(Teacher.user_id == u.id).first()
            if tea:
                d["employee_id"] = tea.employee_id
        out.append(d)
    return {"credentials": out, "note": "Passwords are not exposed by this endpoint; see credentials.txt"}

@app.post("/api/auth/register/hod")
async def register_hod(req: RegisterHODRequest, db = Depends(get_db)):
    """Register the HOD for a branch. The HOD name is fixed per branch."""
    if req.branch not in BRANCHES:
        raise HTTPException(status_code=400, detail="Invalid branch")
    name = req.name or HODS.get(req.branch)
    if not name:
        raise HTTPException(status_code=400, detail="HOD name not available for this branch")
    uname = f"hod.{BRANCH_CODE[req.branch].lower()}"
    email = f"{uname}@nvidia.edu"
    existing = db.query(User).filter((User.username == uname) | (User.email == email)).first()
    if existing:
        # Re-provision to the deterministic demo password (hashes cannot be
        # reversed) so the register screen can still show working credentials.
        pwd = _staff_password_chain()[0].get(("hod", req.branch)) or _password_for(
            name, _staff_password_pool(db, exclude_user_id=existing.id)
        )
        existing.hashed_password = hash_password(pwd)
        db.commit()
        return {"registered": True, "user": user_to_dict(existing, db),
                "username": uname, "password": pwd}
    used = _staff_password_pool(db)
    pwd = _password_for(name, used)
    u = User(username=uname, email=email, full_name=name, role="hod",
             branch=req.branch, hashed_password=hash_password(pwd))
    db.add(u)
    db.commit()
    db.refresh(u)
    return {"registered": True, "user": user_to_dict(u, db), "username": uname, "password": pwd}

@app.post("/api/auth/register/teacher")
async def register_teacher(req: RegisterTeacherRequest, db = Depends(get_db)):
    if req.branch not in BRANCHES:
        raise HTTPException(status_code=400, detail="Invalid branch")
    uname = _slug(req.name) + "." + BRANCH_CODE[req.branch].lower()
    email = f"{uname}@nvidia.edu"
    existing_u = db.query(User).filter((User.username == uname) | (User.email == email)).first()
    if existing_u:
        tea = db.query(Teacher).filter(Teacher.user_id == existing_u.id).first()
        chain = _staff_password_chain()[0]
        pwd = chain.get(("teacher", req.name)) or _password_for(
            req.name, _staff_password_pool(db, exclude_user_id=existing_u.id)
        )
        existing_u.hashed_password = hash_password(pwd)
        db.commit()
        return {"registered": True, "user": user_to_dict(existing_u, db),
                "employee_id": tea.employee_id if tea else None,
                "username": uname, "password": pwd}
    emp_id = req.employee_id or f"T-{BRANCH_CODE[req.branch]}-{db.query(Teacher).count() + 1:02d}"
    used = _staff_password_pool(db)
    pwd = _password_for(req.name, used)
    u = User(username=uname, email=email, full_name=req.name, role="teacher",
             branch=req.branch, hashed_password=hash_password(pwd))
    db.add(u)
    db.flush()
    t = Teacher(user_id=u.id, employee_id=emp_id, name=req.name, email=email, branch=req.branch)
    db.add(t)
    db.commit()
    return {"registered": True, "user": user_to_dict(u, db),
            "employee_id": emp_id, "username": uname, "password": pwd}

def _next_student_id(db, branch, semester):
    code = BRANCH_CODE[branch]
    year = SEM_YEAR.get(semester, "26")
    existing = db.query(Student).filter(
        Student.branch == branch, Student.semester == semester,
        Student.student_id.like(f"1AR{year}{code}%")).all()
    seq = 1
    while True:
        sid = f"1AR{year}{code}{seq:03d}"
        if sid not in {s.student_id for s in existing}:
            return sid
        seq += 1

@app.post("/api/auth/register/student")
async def register_student(req: RegisterStudentRequest, db = Depends(get_db)):
    if req.branch not in BRANCHES or req.semester not in SEMESTERS:
        raise HTTPException(status_code=400, detail="Invalid branch or semester")
    sid = req.student_id or _next_student_id(db, req.branch, req.semester)
    if db.query(Student).filter(Student.student_id == sid).first():
        raise HTTPException(status_code=400, detail="Student ID already registered")
    email = req.email or f"{sid}@nvidia.edu"
    pwd = _student_password(sid)
    u = User(username=sid, email=email, full_name=req.name, role="student",
             branch=req.branch, semester=req.semester, section=req.section,
             hashed_password=hash_password(pwd))
    db.add(u)
    db.flush()
    stu = Student(student_id=sid, user_id=u.id, name=req.name, roll_number=sid,
                  branch=req.branch, semester=req.semester, section=req.section, email=email)
    db.add(stu)
    db.commit()
    return {"registered": True, "user": user_to_dict(u, db),
            "student_id": sid, "password": pwd}

@app.post("/api/auth/register/students/bulk")
async def register_students_bulk(payload: dict, db = Depends(get_db)):
    """
    Bulk student registration from CSV data.
    Expects {"branch": str, "semester": str, "section": str, "rows": [{"name": str, ...}]}
    """
    branch = payload.get("branch")
    semester = payload.get("semester")
    section = payload.get("section", "Section A")
    rows = payload.get("rows", [])
    if branch not in BRANCHES or semester not in SEMESTERS:
        raise HTTPException(status_code=400, detail="Invalid branch or semester")
    if not rows:
        raise HTTPException(status_code=400, detail="No student rows provided")
    created, skipped, errors = [], [], []
    for row in rows:
        name = (row.get("name") or "").strip()
        if not name:
            errors.append({"row": row, "error": "missing name"})
            continue
        sid = (row.get("student_id") or "").strip() or None
        if sid and db.query(Student).filter(Student.student_id == sid).first():
            skipped.append({"student_id": sid, "name": name})
            continue
        if not sid:
            sid = _next_student_id(db, branch, semester)
        email = (row.get("email") or "").strip() or f"{sid}@nvidia.edu"
        pwd = _student_password(sid)
        u = User(username=sid, email=email, full_name=name, role="student",
                 branch=branch, semester=semester, section=section,
                 hashed_password=hash_password(pwd))
        db.add(u)
        db.flush()
        db.add(Student(student_id=sid, user_id=u.id, name=name, roll_number=sid,
                       branch=branch, semester=semester, section=section, email=email))
        created.append({"student_id": sid, "name": name, "password": pwd})
    db.commit()
    return {"created": created, "skipped": skipped, "errors": errors,
            "total_created": len(created), "total_skipped": len(skipped)}

@app.post("/api/auth/logout")
async def logout(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db = Depends(get_db)
):
    _token_store_delete(db, credentials.credentials)
    return {"status": "logged out"}

# --- User Management ---
@app.get("/api/users/me", response_model=Dict[str, Any])
async def get_me(current_user = Depends(get_current_user)):
    return current_user

@app.put("/api/users/me")
async def update_me(update: UserUpdateRequest, db = Depends(get_db), current_user = Depends(get_current_user)):
    """Edit own profile (name, email, password, branch/semester/section)."""
    user = db.query(User).filter(User.id == current_user["id"]).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    if update.full_name is not None:
        user.full_name = update.full_name
    if update.email is not None and update.email != user.email:
        if db.query(User).filter(User.email == update.email, User.id != user.id).first():
            raise HTTPException(status_code=400, detail="Email already in use")
        user.email = update.email
    if update.password:
        user.hashed_password = hash_password(update.password)
    if update.branch is not None:
        user.branch = update.branch
    if update.semester is not None:
        user.semester = update.semester
    if update.section is not None:
        user.section = update.section
    # sync linked profile records
    if user.role == "student":
        stu = db.query(Student).filter(Student.user_id == user.id).first()
        if stu:
            if update.full_name is not None:
                stu.name = update.full_name
            if update.email is not None and update.email != stu.email:
                stu.email = update.email
            if update.branch is not None:
                stu.branch = update.branch
            if update.semester is not None:
                stu.semester = update.semester
            if update.section is not None:
                stu.section = update.section
    elif user.role == "teacher":
        tea = db.query(Teacher).filter(Teacher.user_id == user.id).first()
        if tea:
            if update.full_name is not None:
                tea.name = update.full_name
            if update.email is not None and update.email != tea.email:
                tea.email = update.email
            if update.branch is not None:
                tea.branch = update.branch
            if update.semester is not None:
                tea.semester = update.semester
            if update.section is not None:
                tea.section = update.section
    db.commit()
    return user_to_dict(user, db)

@app.post("/api/users/me/photo")
async def upload_photo(request: dict, db = Depends(get_db), current_user = Depends(get_current_user)):
    """Upload a profile photo (base64 data URL)."""
    photo_data = request.get("photo", "")
    if not photo_data:
        raise HTTPException(status_code=400, detail="photo field required")
    user = db.query(User).filter(User.id == current_user["id"]).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    user.profile_photo = photo_data
    db.commit()
    return {"status": "ok", "profile_photo": photo_data}

@app.put("/api/users/{user_id}")
async def update_any_user(user_id: int, update: UserUpdateRequest, db = Depends(get_db), current_user = Depends(get_current_user)):
    """Admin/HOD can edit any user (student, teacher or HOD) info."""
    if current_user["role"] not in ("admin", "hod"):
        raise HTTPException(status_code=403, detail="Admin or HOD only")
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    if update.full_name is not None:
        user.full_name = update.full_name
    if update.email is not None and update.email != user.email:
        if db.query(User).filter(User.email == update.email, User.id != user.id).first():
            raise HTTPException(status_code=400, detail="Email already in use")
        user.email = update.email
    if update.password:
        user.hashed_password = hash_password(update.password)
    if update.branch is not None:
        user.branch = update.branch
    if update.semester is not None:
        user.semester = update.semester
    if update.section is not None:
        user.section = update.section
    if user.role == "student":
        stu = db.query(Student).filter(Student.user_id == user.id).first()
        if stu:
            if update.full_name is not None:
                stu.name = update.full_name
            if update.email is not None and update.email != stu.email:
                stu.email = update.email
            if update.branch is not None:
                stu.branch = update.branch
            if update.semester is not None:
                stu.semester = update.semester
            if update.section is not None:
                stu.section = update.section
    elif user.role == "teacher":
        tea = db.query(Teacher).filter(Teacher.user_id == user.id).first()
        if tea:
            if update.full_name is not None:
                tea.name = update.full_name
            if update.email is not None and update.email != tea.email:
                tea.email = update.email
            if update.branch is not None:
                tea.branch = update.branch
            if update.semester is not None:
                tea.semester = update.semester
            if update.section is not None:
                tea.section = update.section
    db.commit()
    return user_to_dict(user, db)

# --- Face Recognition ---
@app.get("/api/recognition/status")
async def recognition_status(
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Staff only")
    svc = get_recognition_service()
    return svc.status()

@app.post("/api/recognition/register")
async def register_face(
    req: RegisterFaceRequest,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """
    Register a student's face for recognition.
    Admin / HOD / Teacher role required.
    """
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")

    svc = get_recognition_service()
    status = svc.status()
    if not status.get("real"):
        # Never pretend a face was registered when OpenCV/models are missing.
        raise HTTPException(
            status_code=503,
            detail="Recognition backend unavailable (%s). Face NOT registered."
                   % status.get("method", "unknown"),
        )

    name = req.name
    student = None
    if req.student_id:
        student = db.query(Student).filter(Student.student_id == req.student_id).first()
        if not student:
            raise HTTPException(status_code=404, detail="Student not found")
        name = name or student.name

    check = svc.analyze_image(req.image_base64)
    if not check.get("ok"):
        raise HTTPException(status_code=400, detail=check.get("reason") or "Invalid face image")

    ok = svc.register_student_face(req.student_id, name, req.image_base64)
    if not ok:
        raise HTTPException(status_code=400, detail="No face found in the image. Please capture a clear photo.")

    # Persist the reference on the student record too
    if student:
        student.face_embedding = "registered"
        db.commit()

    return {
        "status": "registered",
        "student_id": req.student_id,
        "name": name,
        "registered": svc.registered_count(),
        "backend": svc.status(),
    }

@app.post("/api/recognition/register/multi")
async def register_face_multi(
    req: RegisterFaceRequest,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """
    Guided multi-angle registration (front / slight left / slight right).

    Every angle must be valid before anything is written: a partial set is
    refused and the previous registration stays exactly as it was. Only the
    given student's templates are replaced; every other student is untouched.
    """
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")

    svc = get_recognition_service()
    status = svc.status()
    if not status.get("real"):
        raise HTTPException(
            status_code=503,
            detail="Recognition backend unavailable (%s). Face NOT registered."
                   % status.get("method", "unknown"),
        )

    captures = req.images or []
    poses = {str(c.get("pose", "")).lower() for c in captures}
    missing = [p for p in ("front", "left", "right") if p not in poses]
    if missing:
        raise HTTPException(
            status_code=400,
            detail="Missing required angle(s): %s. All three are needed to register."
                   % ", ".join(missing),
        )

    student = None
    if req.student_id:
        student = db.query(Student).filter(Student.student_id == req.student_id).first()
        if not student:
            raise HTTPException(status_code=404, detail="Student not found")
    name = req.name or (student.name if student else None)

    result = svc.register_student_faces(req.student_id, name, captures)
    if not result.get("ok"):
        raise HTTPException(status_code=400, detail=result.get("reason") or "Registration failed")

    if student:
        student.face_embedding = "registered"
        db.commit()

    return {
        "status": "registered",
        "student_id": req.student_id,
        "name": name,
        "angles": result["stored"],
        "templates": svc.student_templates(req.student_id),
        "registered": svc.registered_count(),
        "backend": svc.status(),
    }

@app.post("/api/recognition/remove")
async def remove_face(
    req: RemoveFaceRequest,
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")
    svc = get_recognition_service()
    svc.remove_student_face(req.student_id)
    return {"status": "removed", "student_id": req.student_id}

@app.post("/api/recognition/detect")
async def detect_faces(
    req: AnalyzeImageRequest,
    current_user = Depends(get_current_user),
):
    """Detect faces in an image (no recognition)."""
    _bulk_staff_only(current_user)
    svc = get_recognition_service()
    return svc.detect_faces(req.image_base64)

@app.post("/api/recognition/analyze")
async def analyze_image(
    req: AnalyzeImageRequest,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """
    Run real face detection + recognition on a classroom image.
    Returns results WITHOUT saving so the teacher can review and confirm.
    """
    _bulk_staff_only(current_user)
    svc = get_recognition_service()
    status = svc.status()

    students_by_code = {}
    if req.subject_id:
        subject = db.query(Subject).filter(Subject.id == req.subject_id).first()
        if subject:
            student_query = db.query(Student).filter(
                Student.branch == subject.branch,
                Student.semester == subject.semester,
            )
            if req.section:
                student_query = student_query.filter(Student.section == req.section)
            for s in student_query.all():
                students_by_code[s.student_id] = s

    recognized = []
    try:
        recognized = svc.recognize_faces(
            req.image_base64,
            allowed_student_ids=set(students_by_code),
        )
    except RecognitionError as exc:
        raise HTTPException(status_code=503, detail=str(exc))

    results = []
    for face in recognized:
        sid = face.get("student_id")
        student = students_by_code.get(sid)
        if student:
            results.append({
                "student_id": student.student_id,
                "db_student_id": student.id,
                "name": student.name,
                "roll_number": student.roll_number,
                "status": "present" if face.get("matched") else "unknown",
                "confidence": round(face.get("confidence", 0.0) * 100, 1),
                "matched": bool(face.get("matched")),
            })
        else:
            results.append({
                "student_id": sid,
                "db_student_id": None,
                "name": face.get("name", "Unknown Person"),
                "roll_number": "—",
                "status": "unknown" if not face.get("matched") else "present",
                "confidence": round(face.get("confidence", 0.0) * 100, 1),
                "matched": bool(face.get("matched")),
            })

    return {
        "results": results,
        "num_faces": len(results),
        "backend": status,
        "preview": not bool(status.get("real")),
        "note": "Real face recognition is active." if status.get("real")
                else "Recognition backend unavailable — using simulated results.",
    }

# --- Bulk face registration (Excel roster + ZIP of photos) ---
MAX_BULK_ANALYZED_PHOTOS = 500


def _bulk_staff_only(current_user):
    """Bulk registration is limited to staff roles (admin / HOD / teacher)."""
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")


def _bulk_staff_guard(current_user = Depends(get_current_user)):
    """Route-level guard so non-staff get 403 before body validation runs."""
    _bulk_staff_only(current_user)
    return current_user


def _face_is_registered(svc, student) -> bool:
    """True when the student has a stored embedding AND a DB 'registered' flag."""
    embeddings = getattr(svc, "embeddings", None)
    in_svc = isinstance(embeddings, dict) and bool(student) and student.student_id in embeddings
    flagged = bool(student and student.face_embedding)
    return flagged or in_svc


async def _read_bulk_inputs(roster: UploadFile, photos: UploadFile):
    """Read + validate both uploads, returning (parsed, zip_result, match)."""
    roster_bytes = await roster.read()
    photo_bytes = await photos.read()
    try:
        parsed = bulk.parse_roster(roster_bytes)
    except bulk.BulkError as exc:
        raise HTTPException(status_code=400, detail="Roster file: %s" % exc)
    try:
        zipped = bulk.read_photo_zip(photo_bytes)
    except bulk.BulkError as exc:
        raise HTTPException(status_code=400, detail="Photos ZIP: %s" % exc)
    match = bulk.match_photos(parsed["rows"], list(zipped["photos"]))
    return parsed, zipped, match


def _bulk_plan(db, svc, parsed, zipped, match, validate, limit_analyzed=True):
    """
    Build the per-student import plan.

    validate=True also runs face analysis (single face / valid embedding) and
    produces preview thumbnails - used by the preview endpoint.
    """
    backend_real = bool(svc.status().get("real"))
    items, analyzed = [], 0
    for row in parsed["rows"]:
        sid = row["student_id"]
        student = db.query(Student).filter(Student.student_id == sid).first()
        fname = match["assigned"].get(sid)
        payload = zipped["photos"].get(fname) if fname else None
        registered = _face_is_registered(svc, student)
        item = {
            "student_id": sid,
            "name": (row.get("name") or (student.name if student else None)),
            "row": row.get("row"),
            "filename": fname,
            "extra_photos": match["duplicates"].get(sid, []),
            "in_db": student is not None,
            "already_registered": registered,
            "thumbnail": None,
            "faces": None,
            "status": "ready",
            "reason": "",
        }
        if not fname:
            item["status"] = "missing_photo"
            item["reason"] = "No photo in the ZIP matched this USN"
        elif not student:
            item["status"] = "not_in_db"
            item["reason"] = "Student ID not found in the database (register the student first)"
        elif registered:
            item["status"] = "already_registered"
            item["reason"] = "Face already registered - will not be overwritten"
        elif payload is None:
            item["status"] = "invalid"
            item["reason"] = "Photo could not be read from the ZIP"
        elif not backend_real:
            item["status"] = "blocked"
            item["reason"] = "Recognition backend unavailable (OpenCV or models missing)"
        elif validate and (not limit_analyzed or analyzed < MAX_BULK_ANALYZED_PHOTOS):
            analyzed += 1
            data_url = bulk.to_data_url(payload, fname)
            check = svc.analyze_image(data_url)
            item["faces"] = check.get("num_faces")
            if not check.get("ok"):
                item["status"] = "invalid"
                item["reason"] = check.get("reason") or "Invalid face image"
            else:
                item["thumbnail"] = bulk.thumbnail_data_url(payload)
        elif validate:
            item["status"] = "unvalidated"
            item["reason"] = "Preview analysis limit reached (%d photos)" % MAX_BULK_ANALYZED_PHOTOS
        items.append(item)

    summary = {}
    for it in items:
        summary[it["status"]] = summary.get(it["status"], 0) + 1
    summary["roster_rows"] = len(items)
    summary["photos_matched"] = len(match["assigned"])
    summary["photos_unmatched"] = len(match["unmatched"])
    summary["roster_errors"] = len(parsed["errors"])
    return items, summary


@app.post("/api/faces/bulk/preview", dependencies=[Depends(_bulk_staff_guard)])
async def bulk_faces_preview(
    roster: UploadFile = File(...),
    photos: UploadFile = File(...),
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """
    Validate an Excel roster + photo ZIP WITHOUT changing anything.

    Reports missing photos, unmatched photos, duplicate IDs, students absent
    from the DB, already-registered faces, and images with zero/multiple
    faces or unusable embeddings. The UI shows this before importing.
    """
    _bulk_staff_only(current_user)
    svc = get_recognition_service()
    parsed, zipped, match = await _read_bulk_inputs(roster, photos)
    items, summary = _bulk_plan(db, svc, parsed, zipped, match, validate=True)
    return {
        "roster": {
            "filename": roster.filename,
            "columns": parsed["columns"],
            "rows": len(parsed["rows"]),
            "errors": parsed["errors"],
        },
        "photos": {
            "filename": photos.filename,
            "count": len(zipped["photos"]),
            "errors": zipped["errors"],
        },
        "match": {
            "assigned": match["assigned"],
            "unmatched": match["unmatched"],
            "missing": match["missing"],
            "duplicates": match["duplicates"],
        },
        "items": items,
        "summary": summary,
        "backend": svc.status(),
        "committed": False,
    }


@app.post("/api/faces/bulk/commit", dependencies=[Depends(_bulk_staff_guard)])
async def bulk_faces_commit(
    roster: UploadFile = File(...),
    photos: UploadFile = File(...),
    overwrite: bool = Form(False),
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """
    Import the validated faces. Existing registrations are skipped unless
    overwrite=true. Every image is re-validated (single face, 128-d embedding)
    before it is written; failures never touch existing face data.
    """
    _bulk_staff_only(current_user)
    svc = get_recognition_service()
    if not svc.status().get("real"):
        raise HTTPException(
            status_code=503,
            detail="Recognition backend unavailable (%s). No faces were imported."
                   % svc.status().get("method", "unknown"),
        )
    parsed, zipped, match = await _read_bulk_inputs(roster, photos)
    items, _summary = _bulk_plan(db, svc, parsed, zipped, match, validate=False)

    imported, skipped, failed = [], [], []
    for item in items:
        sid, fname = item["student_id"], item["filename"]
        if item["status"] == "missing_photo":
            skipped.append({"student_id": sid, "name": item["name"],
                            "reason": item["reason"]})
            continue
        if item["status"] == "not_in_db":
            failed.append({"student_id": sid, "name": item["name"],
                           "reason": item["reason"]})
            continue
        if item["status"] == "already_registered":
            if overwrite:
                item["status"] = "ready"
            else:
                skipped.append({"student_id": sid, "name": item["name"],
                                "reason": item["reason"]})
                continue
        if item["status"] == "blocked":
            failed.append({"student_id": sid, "name": item["name"],
                           "reason": item["reason"]})
            continue

        payload = zipped["photos"].get(fname)
        student = db.query(Student).filter(Student.student_id == sid).first()
        if payload is None or student is None:
            failed.append({"student_id": sid, "name": item["name"],
                           "reason": "Photo or student disappeared during import"})
            continue

        data_url = bulk.to_data_url(payload, fname)
        check = svc.analyze_image(data_url)
        if not check.get("ok"):
            failed.append({"student_id": sid, "name": item["name"],
                           "reason": check.get("reason") or "Invalid face image"})
            continue

        ok = svc.register_student_face(sid, student.name, data_url)
        if not ok:
            failed.append({"student_id": sid, "name": item["name"],
                           "reason": "Recognition backend rejected the image"})
            continue
        student.face_embedding = "registered"
        imported.append({"student_id": sid, "name": student.name,
                         "filename": fname, "confidence": 1.0})

    db.commit()
    summary = {
        "imported": len(imported),
        "skipped": len(skipped),
        "failed": len(failed),
        "roster_rows": len(items),
        "overwrite": bool(overwrite),
    }
    return {
        "imported": imported,
        "skipped": skipped,
        "failed": failed,
        "summary": summary,
        "registered_total": svc.registered_count(),
        "backend": svc.status(),
        "committed": True,
    }

# --- Branch/Semester/Section Management ---
@app.post("/api/branch-semester-section")
async def create_branch_semester_section(
    data: BranchSemesterSectionCreate,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod"):
        raise HTTPException(status_code=403, detail="Admin or HOD only")
    # Check if exists
    existing = db.query(BranchSemesterSection).filter(
        BranchSemesterSection.branch == data.branch,
        BranchSemesterSection.semester == data.semester,
        BranchSemesterSection.section == data.section
    ).first()
    if existing:
        raise HTTPException(status_code=400, detail="Branch/semester/section already exists")
    new_bs = BranchSemesterSection(branch=data.branch, semester=data.semester, section=data.section)
    db.add(new_bs)
    db.commit()
    db.refresh(new_bs)
    return new_bs

@app.get("/api/branch-semester-section")
async def list_branch_semester_sections(
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Staff only")
    return db.query(BranchSemesterSection).all()

# --- Student Management ---
@app.post("/api/students")
async def create_student(
    student: StudentCreate, db = Depends(get_db), current_user = Depends(get_current_user)
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")
    # HOD / teacher can only add students to their own branch
    if current_user["role"] in ("hod", "teacher") and current_user.get("branch"):
        student.branch = current_user["branch"]
    # Check if student_id already exists
    existing = db.query(Student).filter(Student.student_id == student.student_id).first()
    if existing:
        raise HTTPException(status_code=400, detail="Student ID already exists")
    
    email = student.email or f"{student.student_id}@nvidia.edu"
    pwd = _student_password(student.student_id)
    # Create user first
    new_user = User(
        username=student.student_id,
        email=email,
        full_name=student.name,
        role="student",
        branch=student.branch,
        semester=student.semester,
        section=student.section,
        hashed_password=hash_password(pwd)
    )
    db.add(new_user)
    db.commit()
    db.refresh(new_user)
    
    # Create student
    new_student = Student(
        student_id=student.student_id,
        user_id=new_user.id,
        name=student.name,
        roll_number=student.roll_number or student.student_id,
        branch=student.branch,
        semester=student.semester,
        section=student.section,
        email=email,
        profile_photo_path=student.profile_photo_path,
        face_embedding=student.face_embedding,
        parent_email=(student.parent_email or "").strip() or None,
        parent_email_verified=bool(student.parent_email_verified),
        parent_whatsapp=(student.parent_whatsapp or "").strip() or None,
        parent_whatsapp_verified=bool(student.parent_whatsapp_verified)
    )
    db.add(new_student)
    db.commit()
    db.refresh(new_student)
    return {**student_to_dict(new_student), "password": pwd}

@app.get("/api/students")
async def list_students(
    branch: Optional[str] = None,
    semester: Optional[str] = None,
    section: Optional[str] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    query = db.query(Student)
    if current_user["role"] in ("hod", "teacher") and current_user.get("branch"):
        branch = current_user["branch"]
    if branch:
        query = query.filter(Student.branch == branch)
    if semester:
        query = query.filter(Student.semester == semester)
    if section:
        query = query.filter(Student.section == section)
    return query.all()

@app.get("/api/students/{student_id}")
async def get_student(student_id: int, db = Depends(get_db)):
    student = db.query(Student).filter(Student.id == student_id).first()
    if not student:
        raise HTTPException(status_code=404, detail="Student not found")
    return student

@app.put("/api/students/{student_id}")
async def update_student(
    student_id: int,
    update: StudentUpdateRequest,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")
    student = db.query(Student).filter(Student.id == student_id).first()
    if not student:
        raise HTTPException(status_code=404, detail="Student not found")
    if update.name is not None:
        student.name = update.name
    if update.email is not None and update.email != student.email:
        if db.query(Student).filter(Student.email == update.email, Student.id != student.id).first():
            raise HTTPException(status_code=400, detail="Email already in use")
        student.email = update.email
    if update.roll_number is not None:
        student.roll_number = update.roll_number
    if update.branch is not None:
        student.branch = update.branch
    if update.semester is not None:
        student.semester = update.semester
    if update.section is not None:
        student.section = update.section
    # Parent / guardian contact: the "verified" flags are only accepted
    # alongside an actual address, so a row can never claim a verified
    # contact that does not exist.
    if update.parent_email is not None:
        student.parent_email = update.parent_email.strip() or None
        if not student.parent_email:
            student.parent_email_verified = False
    if update.parent_email_verified is not None and student.parent_email:
        student.parent_email_verified = bool(update.parent_email_verified)
    if update.parent_whatsapp is not None:
        student.parent_whatsapp = update.parent_whatsapp.strip() or None
        if not student.parent_whatsapp:
            student.parent_whatsapp_verified = False
    if update.parent_whatsapp_verified is not None and student.parent_whatsapp:
        student.parent_whatsapp_verified = bool(update.parent_whatsapp_verified)
    user = db.query(User).filter(User.id == student.user_id).first()
    if user:
        if update.name is not None:
            user.full_name = update.name
        if update.email is not None:
            user.email = update.email
        if update.branch is not None:
            user.branch = update.branch
        if update.semester is not None:
            user.semester = update.semester
        if update.section is not None:
            user.section = update.section
        if update.password:
            user.hashed_password = hash_password(update.password)
    db.commit()
    db.refresh(student)
    return student

@app.delete("/api/students/{student_id}")
async def delete_student(
    student_id: int,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")
    student = db.query(Student).filter(Student.id == student_id).first()
    if not student:
        raise HTTPException(status_code=404, detail="Student not found")
    db.query(AttendanceRecord).filter(AttendanceRecord.student_id == student.id).delete()
    user = db.query(User).filter(User.id == student.user_id).first()
    db.delete(student)
    if user and user.role == "student":
        db.delete(user)
    db.commit()
    return {"status": "deleted"}

# --- Teacher Management ---
@app.post("/api/teachers")
async def create_teacher(
    teacher: TeacherCreate, db = Depends(get_db), current_user = Depends(get_current_user)
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")
    # HOD / teacher can only add teachers to their own branch
    if current_user["role"] in ("hod", "teacher") and current_user.get("branch"):
        teacher.branch = current_user["branch"]
    # Check if employee_id already exists
    existing = db.query(Teacher).filter(Teacher.employee_id == teacher.employee_id).first()
    if existing:
        raise HTTPException(status_code=400, detail="Employee ID already exists")
    
    email = teacher.email or f"{_slug(teacher.name)}.{BRANCH_CODE[teacher.branch].lower()}@nvidia.edu"
    uname = f"{_slug(teacher.name)}.{BRANCH_CODE[teacher.branch].lower()}"
    used = _staff_password_pool(db)
    pwd = _password_for(teacher.name, used)
    # Create user first
    new_user = User(
        username=uname,
        email=email,
        full_name=teacher.name,
        role="teacher",
        branch=teacher.branch,
        semester=teacher.semester,
        section=teacher.section,
        hashed_password=hash_password(pwd)
    )
    db.add(new_user)
    db.commit()
    db.refresh(new_user)
    
    # Create teacher
    new_teacher = Teacher(
        user_id=new_user.id,
        employee_id=teacher.employee_id,
        name=teacher.name,
        email=email,
        branch=teacher.branch,
        semester=teacher.semester,
        section=teacher.section,
        profile_photo_path=teacher.profile_photo_path
    )
    db.add(new_teacher)
    db.commit()
    db.refresh(new_teacher)
    return {**teacher_to_dict(new_teacher), "password": pwd, "username": uname}

@app.get("/api/teachers")
async def list_teachers(
    branch: Optional[str] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    query = db.query(Teacher)
    if current_user["role"] in ("hod", "teacher") and current_user.get("branch"):
        branch = current_user["branch"]
    if branch:
        query = query.filter(Teacher.branch == branch)
    return query.all()

@app.delete("/api/teachers/{teacher_id}")
async def delete_teacher(
    teacher_id: int,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod"):
        raise HTTPException(status_code=403, detail="Admin or HOD only")
    teacher = db.query(Teacher).filter(Teacher.id == teacher_id).first()
    if not teacher:
        raise HTTPException(status_code=404, detail="Teacher not found")
    db.query(Subject).filter(Subject.teacher_id == teacher.id).update({"teacher_id": None})
    user = db.query(User).filter(User.id == teacher.user_id).first()
    db.delete(teacher)
    if user and user.role == "teacher":
        db.delete(user)
    db.commit()
    return {"status": "deleted"}

@app.put("/api/teachers/{teacher_id}")
async def update_teacher(
    teacher_id: int,
    update: TeacherUpdateRequest,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod"):
        raise HTTPException(status_code=403, detail="Admin or HOD only")
    teacher = db.query(Teacher).filter(Teacher.id == teacher_id).first()
    if not teacher:
        raise HTTPException(status_code=404, detail="Teacher not found")
    if update.name is not None:
        teacher.name = update.name
    if update.email is not None and update.email != teacher.email:
        if db.query(Teacher).filter(Teacher.email == update.email, Teacher.id != teacher.id).first():
            raise HTTPException(status_code=400, detail="Email already in use")
        teacher.email = update.email
    if update.branch is not None:
        teacher.branch = update.branch
    if update.semester is not None:
        teacher.semester = update.semester
    if update.section is not None:
        teacher.section = update.section
    user = db.query(User).filter(User.id == teacher.user_id).first()
    if user:
        if update.name is not None:
            user.full_name = update.name
        if update.email is not None:
            user.email = update.email
        if update.branch is not None:
            user.branch = update.branch
        if update.semester is not None:
            user.semester = update.semester
        if update.section is not None:
            user.section = update.section
        if update.password:
            user.hashed_password = hash_password(update.password)
    db.commit()
    db.refresh(teacher)
    return teacher

# --- Subject Management ---
@app.post("/api/subjects")
async def create_subject(
    subject: SubjectCreate,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod"):
        raise HTTPException(status_code=403, detail="Admin or HOD only")
    # Check if subject already exists for this branch/semester
    existing = db.query(Subject).filter(
        Subject.code == subject.code,
        Subject.branch == subject.branch,
        Subject.semester == subject.semester
    ).first()
    if existing:
        raise HTTPException(status_code=400, detail="Subject already exists for this branch/semester")
    
    new_subject = Subject(
        name=subject.name,
        code=subject.code,
        branch=subject.branch,
        semester=subject.semester,
        teacher_id=subject.teacher_id
    )
    db.add(new_subject)
    db.commit()
    db.refresh(new_subject)
    return new_subject

@app.get("/api/subjects")
async def list_subjects(
    branch: Optional[str] = None,
    semester: Optional[str] = None,
    teacher_id: Optional[int] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    query = db.query(Subject)
    if current_user["role"] in ("hod", "teacher") and current_user.get("branch"):
        branch = current_user["branch"]
    if branch:
        query = query.filter(Subject.branch == branch)
    if semester:
        query = query.filter(Subject.semester == semester)
    if teacher_id is not None:
        query = query.filter(Subject.teacher_id == teacher_id)
    return query.all()

@app.get("/api/teacher/dashboard")
async def teacher_dashboard(db = Depends(get_db), current_user = Depends(get_current_user)):
    """Today's attendance + the subjects registered to the current teacher."""
    if current_user["role"] not in ("teacher", "hod"):
        raise HTTPException(status_code=403, detail="Teacher only")
    teacher = db.query(Teacher).filter(Teacher.user_id == current_user["id"]).first()
    if not teacher:
        raise HTTPException(status_code=404, detail="Teacher profile not found")
    subjects = db.query(Subject).filter(Subject.teacher_id == teacher.id).all()
    today = datetime.now().date()

    subject_stats = []
    class_sizes = {}   # one entry per distinct class you teach (branch + semester)
    present_ids = set()  # distinct students present today across all your subjects
    marked_ids = set()   # distinct students marked today across all your subjects
    for subj in subjects:
        key = (subj.branch, subj.semester)
        if key not in class_sizes:
            class_sizes[key] = db.query(Student).filter(
                Student.branch == subj.branch,
                Student.semester == subj.semester).count()
        today_recs = db.query(AttendanceRecord).filter(
            AttendanceRecord.subject_id == subj.id, AttendanceRecord.date == today).all()
        present = 0
        absent = 0
        for r in today_recs:
            if r.status == "present":
                present += 1
                present_ids.add(r.student_id)
            elif r.status == "absent":
                absent += 1
            marked_ids.add(r.student_id)
        subject_stats.append({
            "subject_id": subj.id,
            "name": subj.name,
            "code": subj.code,
            "branch": subj.branch,
            "semester": subj.semester,
            "students_in_class": class_sizes[key],
            "present_today": present,
            "absent_today": absent,
            "marked_today": len(today_recs),
        })

    # Class strength is counted once per class actually taught: four subjects in
    # the same semester report the class size once instead of four times, and the
    # value is read from stored student rows so it matches every other screen.
    total_students = sum(class_sizes.values())
    total_present = len(present_ids)
    total_marked = len(marked_ids)

    return {
        "teacher_id": teacher.id,
        "teacher_name": teacher.name,
        "branch": teacher.branch,
        "total_subjects": len(subjects),
        "subjects": subject_stats,
        "today": {
            "total_present": total_present,
            "total_marked": total_marked,
            "total_class_size": total_students,
        },
    }

@app.get("/api/teacher/reports/attendance")
async def teacher_attendance_report(
    subject_id: Optional[int] = None,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """Attendance report for the subjects assigned to the logged-in teacher.

    The scope is always Subject.teacher_id == teacher.id, so a teacher can only
    ever read records for their own subjects (requesting a foreign subject is a
    403). start_date/end_date bound the window inclusively, rows come back
    sorted by USN, and every number is derived from stored attendance_records
    with the app-wide rule: pct = present / total * 100 (unknown/late still
    count towards the denominator).
    """
    if current_user["role"] not in ("teacher", "hod"):
        raise HTTPException(status_code=403, detail="Teacher only")
    teacher = db.query(Teacher).filter(Teacher.user_id == current_user["id"]).first()
    if not teacher:
        raise HTTPException(status_code=404, detail="Teacher profile not found")

    start = start_date.date() if start_date else None
    end = end_date.date() if end_date else None
    if start and end and start > end:
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")

    assigned = db.query(Subject).filter(Subject.teacher_id == teacher.id).order_by(Subject.code).all()
    assigned_ids = {s.id for s in assigned}
    if subject_id is not None and subject_id not in assigned_ids:
        raise HTTPException(status_code=403, detail="Subject is not assigned to you")
    scope_ids = [subject_id] if subject_id is not None else sorted(assigned_ids)

    by_student = {}
    by_subject = {}
    if scope_ids:
        query = (
            db.query(AttendanceRecord, Student)
            .join(Student, AttendanceRecord.student_id == Student.id)
            .filter(AttendanceRecord.subject_id.in_(scope_ids))
        )
        if start:
            query = query.filter(AttendanceRecord.date >= start)
        if end:
            query = query.filter(AttendanceRecord.date <= end)

        for rec, student in query.all():
            entry = by_student.get(student.id)
            if entry is None:
                entry = {"student": student, "present": 0, "absent": 0, "other": 0, "total": 0}
                by_student[student.id] = entry
            entry["total"] += 1
            if rec.status == "present":
                entry["present"] += 1
            elif rec.status == "absent":
                entry["absent"] += 1
            else:
                entry["other"] += 1

            s_entry = by_subject.get(rec.subject_id)
            if s_entry is None:
                s_entry = {"records": 0, "present": 0}
                by_subject[rec.subject_id] = s_entry
            s_entry["records"] += 1
            if rec.status == "present":
                s_entry["present"] += 1

    rows = []
    for entry in by_student.values():
        student = entry["student"]
        total = entry["total"]
        rows.append({
            "student_id": student.student_id,
            "name": student.name,
            "branch": student.branch,
            "semester": student.semester,
            "section": student.section,
            "present": entry["present"],
            "absent": entry["absent"],
            "other": entry["other"],
            "total": total,
            "pct": round(entry["present"] / total * 100, 1) if total else 0.0,
        })
    rows.sort(key=lambda r: ((r["student_id"] or ""), (r["name"] or "")))

    subject_stats = []
    for subj in assigned:
        if subject_id is not None and subj.id != subject_id:
            continue
        s_entry = by_subject.get(subj.id, {"records": 0, "present": 0})
        records = s_entry["records"]
        subject_stats.append({
            "subject_id": subj.id,
            "code": subj.code,
            "name": subj.name,
            "branch": subj.branch,
            "semester": subj.semester,
            "records": records,
            "present": s_entry["present"],
            "pct": round(s_entry["present"] / records * 100, 1) if records else 0.0,
        })

    total_records = sum(r["total"] for r in rows)
    total_present = sum(r["present"] for r in rows)
    summary = {
        "students": len(rows),
        "records": total_records,
        "present": total_present,
        "absent": sum(r["absent"] for r in rows),
        "other": sum(r["other"] for r in rows),
        "pct": round(total_present / total_records * 100, 1) if total_records else 0.0,
    }

    return {
        "teacher_id": teacher.id,
        "teacher_name": teacher.name,
        "branch": teacher.branch,
        "subjects": [
            {"id": s.id, "code": s.code, "name": s.name, "branch": s.branch, "semester": s.semester}
            for s in assigned
        ],
        "subject_stats": subject_stats,
        "filters": {
            "subject_id": subject_id,
            "start_date": start.isoformat() if start else None,
            "end_date": end.isoformat() if end else None,
        },
        "summary": summary,
        "rows": rows,
    }

@app.delete("/api/subjects/{subject_id}")
async def delete_subject(
    subject_id: int,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod"):
        raise HTTPException(status_code=403, detail="Admin or HOD only")
    subject = db.query(Subject).filter(Subject.id == subject_id).first()
    if not subject:
        raise HTTPException(status_code=404, detail="Subject not found")
    db.query(AttendanceRecord).filter(AttendanceRecord.subject_id == subject.id).delete()
    db.delete(subject)
    db.commit()
    return {"status": "deleted"}

# --- Attendance ---
@app.post("/api/attendance/capture")
async def capture_attendance(
    request: dict,
    db = Depends(get_db),
    current_user = Depends(get_current_user)
):
    """
    Capture attendance from classroom image analysis.
    Staff roles only - students must never mark their own attendance.

    Expected request format:
    {
        "subject_id": int,
        "date": str (ISO date string),
        "image_base64": str,  // base64 encoded classroom image
        "use_recognition": bool  // whether to run face recognition
    }

    Or use pre-computed results:
    {
        "subject_id": int,
        "date": str,
        "student_results": [
            {"student_id": int, "status": "present"/"absent"/"unknown", "confidence": float}
        ]
    }
    """
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")
    subject_id = request.get("subject_id")
    date_str = request.get("date")
    image_base64 = request.get("image_base64")
    use_recognition = request.get("use_recognition", True)
    student_results = request.get("student_results")

    if not subject_id or not date_str:
        raise HTTPException(status_code=400, detail="subject_id and date are required")
    try:
        att_date = datetime.strptime(date_str, "%Y-%m-%d").date()
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid date, expected YYYY-MM-DD")

    marked_by = current_user["id"]

    # If student_results are provided directly, use them
    if student_results:
        results_to_use = student_results
    elif image_base64 and use_recognition:
        # Run face recognition on the classroom image
        recognition_svc = get_recognition_service()
        rec_status = recognition_svc.status()
        if not rec_status.get("real"):
            # A mock/simulated backend must never be recorded as AI attendance.
            raise HTTPException(
                status_code=503,
                detail="Recognition backend unavailable (%s). Refusing to record "
                       "simulated results as attendance." % rec_status.get("method", "unknown"),
            )

        # Detect faces
        detection = recognition_svc.detect_faces(image_base64)

        if not detection.get("success"):
            raise HTTPException(
                status_code=400,
                detail=detection.get("error") or "Face detection failed",
            )

        if detection["num_faces"] == 0:
            raise HTTPException(
                status_code=400,
                detail="No faces detected in the image. Attendance was not recorded.",
            )

        try:
            recognized = recognition_svc.recognize_faces(image_base64)
        except RecognitionError as exc:
            raise HTTPException(status_code=503, detail=str(exc))

        # Build student results from recognition
        results_to_use = []

        for face_result in recognized:
            student_id = face_result["student_id"]
            confidence = face_result["confidence"]

            # Determine status based on confidence
            if student_id == "unknown":
                results_to_use.append({
                    "student_id": None,
                    "status": "unknown",
                    "confidence": confidence
                })
            else:
                # Check if student is in this subject's class
                student = db.query(Student).filter(Student.student_id == student_id).first()
                if student:
                    results_to_use.append({
                        "student_id": student.id,
                        "status": "present",
                        "confidence": confidence
                    })
    else:
        raise HTTPException(status_code=400, detail="No attendance data provided")

    def lookup_student(sid):
        if sid is None:
            return None
        if isinstance(sid, str):
            return db.query(Student).filter(Student.student_id == sid).first()
        return db.query(Student).filter(Student.id == sid).first()

    # Process attendance records
    saved = 0
    if results_to_use:
        for result in results_to_use:
            student = lookup_student(result.get("student_id"))
            if not student:
                continue

            status = result.get("status", "present")
            confidence = result.get("confidence", 0.0)

            # Check if attendance already recorded for this student, subject, date
            existing = db.query(AttendanceRecord).filter(
                AttendanceRecord.student_id == student.id,
                AttendanceRecord.subject_id == subject_id,
                AttendanceRecord.date == att_date
            ).first()

            if existing:
                # Update existing record
                existing.status = status
                existing.confidence = confidence
                existing.marked_by = marked_by
                existing.marked_at = datetime.utcnow()
            else:
                # Create new attendance record
                db.add(AttendanceRecord(
                    student_id=student.id,
                    subject_id=subject_id,
                    date=att_date,
                    status=status,
                    confidence=confidence,
                    marked_by=marked_by
                ))
            saved += 1

    db.commit()

    # Calculate summary
    present_count = sum(1 for r in results_to_use if r.get("status") == "present")
    absent_count = sum(1 for r in results_to_use if r.get("status") in ["absent", "unknown"])
    unknown_count = sum(1 for r in results_to_use if r.get("status") == "unknown")

    return {
        "message": "Attendance recorded successfully",
        "present": present_count,
        "absent": absent_count,
        "unknown": unknown_count,
        "saved": saved,
    }

@app.get("/api/attendance")
async def list_attendance(
    subject_id: Optional[int] = None,
    date: Optional[datetime] = None,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Staff only")
    query = db.query(AttendanceRecord)
    if subject_id:
        query = query.filter(AttendanceRecord.subject_id == subject_id)
    if date:
        query = query.filter(AttendanceRecord.date == date.date())
    start = start_date.date() if start_date else None
    end = end_date.date() if end_date else None
    if start and end and start > end:
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")
    if start:
        query = query.filter(AttendanceRecord.date >= start)
    if end:
        query = query.filter(AttendanceRecord.date <= end)
    return query.order_by(AttendanceRecord.marked_at.desc()).all()

@app.get("/api/attendance/{subject_id}")
async def get_attendance(
    subject_id: int,
    date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Staff only")
    query = db.query(AttendanceRecord).filter(AttendanceRecord.subject_id == subject_id)
    if date:
        query = query.filter(AttendanceRecord.date == date.date())
    return query.all()

@app.get("/api/attendance/student/{student_id}")
async def get_student_attendance(
    student_id: int,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] == "student":
        own = db.query(Student).filter(Student.user_id == current_user["id"]).first()
        if not own or own.id != student_id:
            raise HTTPException(status_code=403, detail="Students can only view their own attendance")
    elif current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Staff only")
    return db.query(AttendanceRecord).filter(AttendanceRecord.student_id == student_id).all()

@app.put("/api/attendance/{record_id}")
async def update_attendance_record(
    record_id: int,
    update: AttendanceUpdate,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")
    record = db.query(AttendanceRecord).filter(AttendanceRecord.id == record_id).first()
    if not record:
        raise HTTPException(status_code=404, detail="Attendance record not found")
    record.status = update.status
    if update.confidence is not None:
        record.confidence = update.confidence
    db.commit()
    db.refresh(record)
    return record

@app.delete("/api/attendance/{record_id}")
async def delete_attendance_record(
    record_id: int,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] not in ("admin", "hod", "teacher"):
        raise HTTPException(status_code=403, detail="Not allowed")
    record = db.query(AttendanceRecord).filter(AttendanceRecord.id == record_id).first()
    if not record:
        raise HTTPException(status_code=404, detail="Attendance record not found")
    db.delete(record)
    db.commit()
    return {"status": "deleted"}

# --- Notifications ---
@app.get("/api/notifications/{user_id}")
async def list_notifications(
    user_id: int,
    unread_only: bool = False,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["id"] != user_id and current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Not your notifications")
    query = db.query(Notification).filter(Notification.user_id == user_id)
    if unread_only:
        query = query.filter(Notification.read == False)
    return query.order_by(Notification.created_at.desc()).all()

@app.put("/api/notifications/{notification_id}/read")
async def mark_notification_read(
    notification_id: int,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    notif = db.query(Notification).filter(Notification.id == notification_id).first()
    if not notif:
        raise HTTPException(status_code=404, detail="Notification not found")
    if notif.user_id != current_user["id"] and current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Not your notification")
    notif.read = True
    db.commit()
    return {"status": "marked as read"}

# --- Admin / College Overview ---
def _student_pct(db, student_id, start=None, end=None):
    query = db.query(AttendanceRecord).filter(AttendanceRecord.student_id == student_id)
    if start:
        query = query.filter(AttendanceRecord.date >= start)
    if end:
        query = query.filter(AttendanceRecord.date <= end)
    records = query.all()
    if not records:
        return 0.0
    present = sum(1 for r in records if r.status == "present")
    return round(present / len(records) * 100, 1)

def _branch_pct(db, branch, semester=None, start=None, end=None):
    query = db.query(AttendanceRecord).join(Student).filter(Student.branch == branch)
    if semester:
        query = query.filter(Student.semester == semester)
    if start:
        query = query.filter(AttendanceRecord.date >= start)
    if end:
        query = query.filter(AttendanceRecord.date <= end)
    records = query.all()
    if not records:
        return 0.0
    present = sum(1 for r in records if r.status == "present")
    return round(present / len(records) * 100, 1)

@app.get("/api/admin/overview")
async def admin_overview(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    trend_days: int = 14,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """
    College-wide snapshot for the Principal.

    Counts entities once (COUNT(DISTINCT ...)) and only from real records:
      * every student / teacher row is counted once, no joins that duplicate them
      * today = present students minus those marked absent somewhere today, so a
        student seen in two subjects is never counted as both present and absent
      * only status == 'absent' counts as absent - unmarked/unknown rows are never
        treated as absences
      * the low-attendance count is the real number of students below the
        configured threshold (no row limit)
      * overall_attendance_pct is None (not 0.0) when nothing was ever recorded
    start_date/end_date (both optional) bound every attendance-derived number and
    every department percentage; the counts of students/teachers/departments are
    not date-based and stay whole-college.
    """
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")

    start = start_date.date() if start_date else None
    end = end_date.date() if end_date else None
    if start and end and start > end:
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")
    threshold = student_report.MIN_ATTENDANCE_PCT

    total_students = db.query(func.count(distinct(Student.id))).scalar() or 0
    total_teachers = db.query(func.count(distinct(Teacher.id))).scalar() or 0

    # A department exists as soon as it has a student, a teacher or a subject.
    departments = {
        b for (b,) in db.query(Student.branch).distinct().all() if b
    } | {
        b for (b,) in db.query(Teacher.branch).distinct().all() if b
    } | {
        b for (b,) in db.query(Subject.branch).distinct().all() if b
    }
    total_depts = len(departments)

    today = datetime.now().date()

    # Today's attendance, unique students only. A student marked present in one
    # subject and absent in another is counted as present, never as both.
    present_ids = {
        sid for (sid,) in db.query(AttendanceRecord.student_id).filter(
            AttendanceRecord.date == today,
            AttendanceRecord.status == "present",
        ).distinct().all()
    }
    absent_ids = {
        sid for (sid,) in db.query(AttendanceRecord.student_id).filter(
            AttendanceRecord.date == today,
            AttendanceRecord.status == "absent",
        ).distinct().all()
    } - present_ids
    present_today = len(present_ids)
    absent_today = len(absent_ids)

    # Attendance window used by every percentage below
    window_q = db.query(AttendanceRecord)
    if start:
        window_q = window_q.filter(AttendanceRecord.date >= start)
    if end:
        window_q = window_q.filter(AttendanceRecord.date <= end)

    total_recs = window_q.with_entities(func.count(AttendanceRecord.id)).scalar() or 0
    present_total = window_q.with_entities(func.count(AttendanceRecord.id)).filter(
        AttendanceRecord.status == "present").scalar() or 0
    overall_pct = round(present_total / total_recs * 100, 1) if total_recs else None

    # Low attendance students (< configured threshold), counted in full
    low_sql = """
        SELECT s.student_id, s.name, s.roll_number, s.branch,
               CAST(SUM(CASE WHEN ar.status = 'present' THEN 1 ELSE 0 END) AS FLOAT)
               / COUNT(ar.id) * 100 AS pct
        FROM attendance_records ar
        JOIN students s ON ar.student_id = s.id
        WHERE ar.id > 0
    """
    low_params = {}
    if start:
        low_sql += " AND ar.date >= :start"
        low_params["start"] = start
    if end:
        low_sql += " AND ar.date <= :end"
        low_params["end"] = end
    low_sql += """
        GROUP BY ar.student_id
        HAVING pct > 0 AND pct < :threshold
        ORDER BY pct ASC
    """
    low_params["threshold"] = threshold
    low_rows = db.execute(text(low_sql), low_params).fetchall()
    low_attendance = [
        {"student_id": r[0], "name": r[1], "roll_number": r[2], "branch": r[3], "percentage": round(r[4], 1)}
        for r in low_rows
    ]

    # Department stats inside the same window
    branch_stats = []
    attention = []
    for branch in sorted(departments):
        b_students = db.query(Student).filter(Student.branch == branch).count()
        b_teachers = db.query(Teacher).filter(Teacher.branch == branch).count()
        b_subjects = db.query(Subject).filter(Subject.branch == branch).count()
        b_window = window_q.join(Student, AttendanceRecord.student_id == Student.id).filter(
            Student.branch == branch
        )
        b_recs = b_window.with_entities(func.count(AttendanceRecord.id)).scalar() or 0
        b_present = b_window.with_entities(func.count(AttendanceRecord.id)).filter(
            AttendanceRecord.status == "present").scalar() or 0
        b_pct = round(b_present / b_recs * 100, 1) if b_recs else None
        b_low = sum(1 for r in low_attendance if r["branch"] == branch)
        branch_stats.append({
            "branch": branch,
            "students": b_students,
            "teachers": b_teachers,
            "subjects": b_subjects,
            "attendance_pct": b_pct,
            "records": b_recs,
            "below_threshold_students": b_low,
        })
        if b_pct is not None and b_pct < threshold:
            attention.append({
                "branch": branch,
                "attendance_pct": b_pct,
                "students": b_students,
                "below_threshold_students": b_low,
            })

    # Attendance trend over the requested window (days without records are skipped)
    from datetime import timedelta
    days = max(1, min(int(trend_days or 14), 90))
    trend = []
    for i in range(days - 1, -1, -1):
        d = today - timedelta(days=i)
        if start and d < start:
            continue
        if end and d > end:
            continue
        day_total = db.query(func.count(AttendanceRecord.id)).filter(
            AttendanceRecord.date == d
        ).scalar() or 0
        if not day_total:
            continue
        day_present = db.query(func.count(AttendanceRecord.id)).filter(
            AttendanceRecord.date == d, AttendanceRecord.status == "present"
        ).scalar() or 0
        trend.append({"date": d.isoformat(), "pct": round(day_present / day_total * 100, 1),
                      "records": day_total})

    return {
        "total_students": total_students,
        "total_teachers": total_teachers,
        "total_departments": total_depts,
        "overall_attendance_pct": overall_pct,
        "present_today": present_today,
        "absent_today": absent_today,
        "low_attendance_count": len(low_attendance),
        "low_attendance_students": low_attendance,
        "branch_stats": branch_stats,
        "monthly_trend": trend,
        "threshold_pct": threshold,
        "records_in_window": total_recs,
        "window": {
            "start_date": start.isoformat() if start else None,
            "end_date": end.isoformat() if end else None,
        },
        "attention": attention,
    }

@app.get("/api/admin/low-attendance/{branch}")
async def admin_low_attendance_by_branch(
    branch: str,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    params = {"branch": branch, "threshold": student_report.MIN_ATTENDANCE_PCT}
    date_sql = ""
    if start_date:
        date_sql += " AND ar.date >= :start"
        params["start"] = start_date.date()
    if end_date:
        date_sql += " AND ar.date <= :end"
        params["end"] = end_date.date()
    low_rows = db.execute(text("""
        SELECT s.student_id, s.name, s.roll_number, s.semester, s.section,
               CAST(SUM(CASE WHEN ar.status = 'present' THEN 1 ELSE 0 END) AS FLOAT)
               / COUNT(ar.id) * 100 AS pct
        FROM attendance_records ar
        JOIN students s ON ar.student_id = s.id
        WHERE s.branch = :branch
    """ + date_sql + """
        GROUP BY ar.student_id
        HAVING pct > 0 AND pct < :threshold
        ORDER BY pct ASC
    """), params).fetchall()
    return [
        {"student_id": r[0], "name": r[1], "roll_number": r[2], "semester": r[3], "section": r[4], "percentage": round(r[5], 1)}
        for r in low_rows
    ]

@app.get("/api/admin/teachers/{branch}")
async def admin_teachers_by_branch(branch: str, db = Depends(get_db), current_user = Depends(get_current_user)):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    teachers = db.query(Teacher).filter(Teacher.branch == branch).all()
    return [{"id": t.id, "name": t.name, "employee_id": t.employee_id, "email": t.email, "branch": t.branch} for t in teachers]

@app.get("/api/admin/students/{branch}")
async def admin_students_by_branch(branch: str, db = Depends(get_db), current_user = Depends(get_current_user)):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    students = db.query(Student).filter(Student.branch == branch).order_by(Student.semester, Student.student_id).all()
    return [{"id": s.id, "student_id": s.student_id, "name": s.name, "roll_number": s.roll_number,
             "semester": s.semester, "section": s.section} for s in students]

@app.get("/api/admin/departments")
async def admin_departments(db = Depends(get_db), current_user = Depends(get_current_user)):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    branches = db.query(Student.branch).distinct().all()
    out = []
    for (branch,) in branches:
        if not branch:
            continue
        out.append({
            "branch": branch,
            "students": db.query(Student).filter(Student.branch == branch).count(),
            "teachers": db.query(Teacher).filter(Teacher.branch == branch).count(),
            "subjects": db.query(Subject).filter(Subject.branch == branch).count(),
            "attendance_pct": _branch_pct(db, branch),
        })
    return out

@app.get("/api/admin/departments/{branch}")
async def admin_department_detail(
    branch: str,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    start = start_date.date() if start_date else None
    end = end_date.date() if end_date else None
    if start and end and start > end:
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")
    semesters = db.query(Student.semester).filter(Student.branch == branch).distinct().all()
    out = []
    for (sem,) in semesters:
        if not sem:
            continue
        out.append({
            "semester": sem,
            "students": db.query(Student).filter(Student.branch == branch, Student.semester == sem).count(),
            "attendance_pct": _branch_pct(db, branch, sem, start, end),
        })
    return {"branch": branch, "semesters": out,
            "window": {"start_date": start.isoformat() if start else None,
                       "end_date": end.isoformat() if end else None}}

@app.get("/api/admin/departments/{branch}/{semester}")
async def admin_semester_detail(
    branch: str,
    semester: str,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    start = start_date.date() if start_date else None
    end = end_date.date() if end_date else None
    if start and end and start > end:
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")
    sections = db.query(Student.section).filter(
        Student.branch == branch, Student.semester == semester).distinct().all()
    out = []
    for (sec,) in sections:
        if not sec:
            continue
        out.append({
            "section": sec,
            "students": db.query(Student).filter(
                Student.branch == branch, Student.semester == semester,
                Student.section == sec).count(),
            "attendance_pct": _section_pct(db, branch, semester, sec, start, end),
        })
    return {"branch": branch, "semester": semester, "sections": out,
            "window": {"start_date": start.isoformat() if start else None,
                       "end_date": end.isoformat() if end else None}}


def _section_pct(db, branch, semester, section, start=None, end=None):
    query = db.query(AttendanceRecord).join(Student).filter(
        Student.branch == branch, Student.semester == semester, Student.section == section)
    if start:
        query = query.filter(AttendanceRecord.date >= start)
    if end:
        query = query.filter(AttendanceRecord.date <= end)
    total = query.with_entities(func.count(AttendanceRecord.id)).scalar() or 0
    if not total:
        return None
    present = query.with_entities(func.count(AttendanceRecord.id)).filter(
        AttendanceRecord.status == "present").scalar() or 0
    return round(present / total * 100, 1)

@app.get("/api/admin/classes/{branch}/{semester}/{section}")
async def admin_class_overview(
    branch: str,
    semester: str,
    section: str,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    start = start_date.date() if start_date else None
    end = end_date.date() if end_date else None
    if start and end and start > end:
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")
    students = db.query(Student).filter(
        Student.branch == branch, Student.semester == semester, Student.section == section).all()
    subjects = db.query(Subject).filter(Subject.branch == branch, Subject.semester == semester).all()
    today = datetime.now().date()

    student_list = []
    present = absent = 0
    for s in students:
        pct = _student_pct(db, s.id, start, end)
        today_rec = db.query(AttendanceRecord).filter(
            AttendanceRecord.student_id == s.id, AttendanceRecord.date == today).first()
        status = today_rec.status if today_rec else "—"
        if status == "present":
            present += 1
        elif status == "absent":
            absent += 1
        student_list.append({
            "student_id": s.student_id, "name": s.name, "roll_number": s.roll_number,
            "attendance_pct": pct, "today_status": status,
        })
    return {
        "branch": branch, "semester": semester, "section": section,
        "total_students": len(students),
        "present_today": present, "absent_today": absent,
        "threshold_pct": student_report.MIN_ATTENDANCE_PCT,
        "window": {"start_date": start.isoformat() if start else None,
                   "end_date": end.isoformat() if end else None},
        "subjects": [{"id": s.id, "name": s.name, "code": s.code} for s in subjects],
        "students": student_list,
    }


@app.get("/api/admin/reports/export")
async def admin_reports_export(
    report_type: str,
    branch: Optional[str] = None,
    semester: Optional[str] = None,
    section: Optional[str] = None,
    subject_id: Optional[int] = None,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """Excel export of exactly the report shown on screen.

    Every number is produced by calling the same endpoint functions the page
    calls, so the workbook can never disagree with the on-screen calculations.
    """
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    import admin_report

    titles = {"college": "College Report", "department": "Department Report",
              "subject": "Attendance Report", "low": "Low Attendance Report"}
    if report_type not in titles:
        raise HTTPException(status_code=400, detail="Unknown report_type")
    if report_type == "department" and not branch:
        raise HTTPException(status_code=400, detail="branch is required")
    if report_type == "department" and section and not semester:
        raise HTTPException(status_code=400, detail="semester is required when section is set")
    if report_type == "subject" and not subject_id:
        raise HTTPException(status_code=400, detail="subject_id is required")
    if start_date and end_date and start_date.date() > end_date.date():
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")

    filters = {
        "start_date": start_date.date().isoformat() if start_date else None,
        "end_date": end_date.date().isoformat() if end_date else None,
        "branch": branch, "semester": semester, "section": section,
    }
    meta = {
        "title": titles[report_type],
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "threshold_pct": student_report.MIN_ATTENDANCE_PCT,
        "filters": filters,
    }

    if report_type == "college":
        payload = await admin_overview(start_date, end_date, 14, db, current_user)
    elif report_type == "department":
        payload = {
            "department": await admin_department_detail(branch, start_date, end_date, db, current_user),
            "semester": await admin_semester_detail(branch, semester, start_date, end_date, db, current_user)
            if semester else None,
        }
        if section:
            payload["class"] = await admin_class_overview(
                branch, semester, section, start_date, end_date, db, current_user)
    elif report_type == "subject":
        filters["subject"] = None
        data = await attendance_report(subject_id, start_date, end_date, db, current_user)
        ids = [int(k) for k in data["records"].keys()]
        students = {s.id: s for s in db.query(Student).filter(Student.id.in_(ids)).all()} if ids else {}
        rows = []
        for sid, recs in data["records"].items():
            st = students.get(int(sid))
            total = len(recs)
            present = sum(1 for r in recs if r["status"] == "present")
            absent = sum(1 for r in recs if r["status"] == "absent")
            rows.append({
                "usn": st.student_id if st else sid,
                "name": st.name if st else "",
                "total": total, "present": present, "absent": absent,
                "other": total - present - absent,
                "pct": round(present / total * 100, 1) if total else 0.0,
            })
        rows.sort(key=lambda r: (r["pct"], r["usn"]))
        payload = rows
        subj = db.query(Subject).filter(Subject.id == subject_id).first()
        filters["subject"] = "%s (%s)" % (subj.name, subj.code) if subj else str(subject_id)
    else:  # low
        if branch:
            payload = await admin_low_attendance_by_branch(branch, start_date, end_date, db, current_user)
        else:
            ov = await admin_overview(start_date, end_date, 14, db, current_user)
            payload = ov["low_attendance_students"]

    workbook = admin_report.render_xlsx(report_type, payload, meta)
    filename = "trinetra-%s-%s.xlsx" % (report_type, datetime.now().strftime("%Y%m%d"))
    return Response(
        content=workbook,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": 'attachment; filename="%s"' % filename},
    )


@app.get("/api/admin/hods")
async def list_hods(db = Depends(get_db), current_user = Depends(get_current_user)):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    hods = db.query(User).filter(User.role == "hod").all()
    return [user_to_dict(u, db) for u in hods]

@app.post("/api/admin/hods")
async def create_hod(data: dict, db = Depends(get_db), current_user = Depends(get_current_user)):
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    email = data.get("email")
    name = data.get("name")
    branch = data.get("branch", "ISE")
    password = data.get("password", "hod123")
    if not email or not name:
        raise HTTPException(status_code=400, detail="email and name required")
    existing = db.query(User).filter(User.email == email).first()
    if existing:
        raise HTTPException(status_code=400, detail="Email already in use")
    u = User(email=email, full_name=name, role="hod", branch=branch,
             hashed_password=hash_password(password), semester=data.get("semester", "6th Semester"),
             section=data.get("section", "Section A"))
    db.add(u)
    db.commit()
    db.refresh(u)
    return user_to_dict(u, db)

# --- Analytics ---
@app.get("/api/analytics/overall")
async def get_overall_analytics(db = Depends(get_db), current_user = Depends(get_current_user)):
    branch_scope = current_user.get("branch") if current_user["role"] in ("hod", "teacher") else None
    students_q = db.query(Student)
    teachers_q = db.query(Teacher)
    subjects_q = db.query(Subject)
    if branch_scope:
        students_q = students_q.filter(Student.branch == branch_scope)
        teachers_q = teachers_q.filter(Teacher.branch == branch_scope)
        subjects_q = subjects_q.filter(Subject.branch == branch_scope)
    total_students = students_q.count()
    total_teachers = teachers_q.count()
    total_subjects = subjects_q.count()

    today = datetime.now().date()

    # Attendance queries scoped to branch
    att_q = db.query(AttendanceRecord)
    if branch_scope:
        att_q = att_q.join(Student).filter(Student.branch == branch_scope)

    total_classes_q = att_q.with_entities(func.count(distinct(AttendanceRecord.date)))
    total_classes = total_classes_q.scalar() or 0

    # Today's present/absent (unique students)
    present_count = db.query(func.count(distinct(AttendanceRecord.student_id))).filter(
        AttendanceRecord.date == today, AttendanceRecord.status == "present"
    )
    absent_count = db.query(func.count(distinct(AttendanceRecord.student_id))).filter(
        AttendanceRecord.date == today, AttendanceRecord.status.in_(["absent", "unknown", "late"])
    )
    if branch_scope:
        present_count = present_count.join(Student).filter(Student.branch == branch_scope)
        absent_count = absent_count.join(Student).filter(Student.branch == branch_scope)
    present_count = present_count.scalar() or 0
    absent_count = absent_count.scalar() or 0

    # Students below threshold via SQL
    params = {"branch": branch_scope} if branch_scope else {}
    sql = """
        SELECT s.student_id, s.name,
               CAST(SUM(CASE WHEN ar.status = 'present' THEN 1 ELSE 0 END) AS FLOAT)
               / COUNT(ar.id) * 100 AS pct
        FROM attendance_records ar
        JOIN students s ON ar.student_id = s.id
        WHERE 1=1
    """
    if branch_scope:
        sql += " AND s.branch = :branch"
    sql += """
        GROUP BY ar.student_id
        HAVING pct > 0 AND pct < 75
        ORDER BY pct ASC
    """
    low_rows = db.execute(text(sql), params).fetchall()
    below_threshold = [
        {"student_id": r[0], "name": r[1], "percentage": round(r[2], 2)}
        for r in low_rows
    ]

    # Branch-wise stats via SQL
    branches = db.query(Student.branch).distinct().all()
    branch_stats = []
    for (branch,) in branches:
        if branch_scope and branch != branch_scope:
            continue
        students_in_branch = db.query(Student).filter(Student.branch == branch).count()
        b_recs = db.query(func.count(AttendanceRecord.id)).join(Student).filter(
            Student.branch == branch
        ).scalar() or 0
        b_present = db.query(func.count(AttendanceRecord.id)).join(Student).filter(
            Student.branch == branch, AttendanceRecord.status == "present"
        ).scalar() or 0
        overall_pct = round(b_present / b_recs * 100, 2) if b_recs > 0 else 0
        branch_stats.append({
            "branch": branch,
            "total_students": students_in_branch,
            "overall_attendance_pct": overall_pct
        })

    return {
        "total_students": total_students,
        "total_teachers": total_teachers,
        "total_subjects": total_subjects,
        "total_classes": total_classes,
        "present_today": present_count,
        "absent_today": absent_count,
        "below_threshold_students": len(below_threshold),
        "branch_stats": branch_stats,
        "below_threshold_list": below_threshold
    }

@app.get("/api/analytics/semester/{semester}")
async def get_semester_analytics(semester: str, db = Depends(get_db), current_user = Depends(get_current_user)):
    branch_scope = current_user.get("branch") if current_user["role"] in ("hod", "teacher") else None
    students_q = db.query(Student).filter(Student.semester == semester)
    if branch_scope:
        students_q = students_q.filter(Student.branch == branch_scope)
    total_students = students_q.count()

    # Low attendance students via ORM
    stu_map = {s.id: s for s in students_q.all()}
    below = []
    for sid, s in stu_map.items():
        total_recs = db.query(func.count(AttendanceRecord.id)).filter(AttendanceRecord.student_id == sid).scalar() or 0
        if total_recs == 0:
            continue
        present_recs = db.query(func.count(AttendanceRecord.id)).filter(
            AttendanceRecord.student_id == sid, AttendanceRecord.status == "present"
        ).scalar() or 0
        pct = round(present_recs / total_recs * 100, 1)
        if 0 < pct < 75:
            below.append({"student_id": s.student_id, "name": s.name, "branch": s.branch, "percentage": pct})
    below.sort(key=lambda x: x["percentage"])

    # Subjects for this semester
    subjects_q = db.query(Subject).filter(Subject.semester == semester)
    if branch_scope:
        subjects_q = subjects_q.filter(Subject.branch == branch_scope)
    subjects = []
    for sub in subjects_q.all():
        total = db.query(func.count(AttendanceRecord.id)).filter(AttendanceRecord.subject_id == sub.id).scalar() or 0
        present = db.query(func.count(AttendanceRecord.id)).filter(
            AttendanceRecord.subject_id == sub.id, AttendanceRecord.status == "present"
        ).scalar() or 0
        pct = round(present / total * 100, 1) if total > 0 else 0
        subjects.append({"code": sub.code, "name": sub.name, "total": total, "present": present, "pct": pct})

    return {
        "semester": semester,
        "total_students": total_students,
        "below_threshold": below,
        "subjects": subjects,
    }

# --- HOD subject & semester comparison report ---
import hod_report


def _hod_report(db, current_user, semester, subject_id, section, start_date, end_date):
    """Build the HOD comparison report, scoped to the caller's own department."""
    if current_user["role"] != "hod":
        raise HTTPException(status_code=403, detail="HOD only")
    try:
        return hod_report.build_report(
            db, current_user,
            semester=semester,
            subject_id=subject_id,
            section=section,
            start_date=start_date.date().isoformat() if start_date else None,
            end_date=end_date.date().isoformat() if end_date else None,
        )
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@app.get("/api/hod/reports/comparison")
async def hod_comparison_report(
    semester: Optional[str] = None,
    subject_id: Optional[int] = None,
    section: Optional[str] = None,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    return _hod_report(db, current_user, semester, subject_id, section, start_date, end_date)


@app.get("/api/hod/reports/comparison/pdf")
async def hod_comparison_pdf(
    semester: Optional[str] = None,
    subject_id: Optional[int] = None,
    section: Optional[str] = None,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """PDF export of exactly the report shown on screen (same builder)."""
    report = _hod_report(db, current_user, semester, subject_id, section, start_date, end_date)
    pdf = hod_report.render_pdf(report)
    filename = "hod-comparison-%s.pdf" % (semester or "all").replace(" ", "-")
    return Response(
        content=pdf,
        media_type="application/pdf",
        headers={"Content-Disposition": 'attachment; filename="%s"' % filename},
    )


@app.get("/api/hod/reports/comparison/xlsx")
async def hod_comparison_xlsx(
    semester: Optional[str] = None,
    subject_id: Optional[int] = None,
    section: Optional[str] = None,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """Excel export of exactly the report shown on screen (same builder)."""
    report = _hod_report(db, current_user, semester, subject_id, section, start_date, end_date)
    book = hod_report.render_xlsx(report)
    filename = "hod-comparison-%s.xlsx" % (semester or "all").replace(" ", "-")
    return Response(
        content=book,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": 'attachment; filename="%s"' % filename},
    )


# --- Reports ---
@app.get("/api/reports/attendance")
async def attendance_report(
    subject_id: Optional[int] = None,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """Per-subject attendance records.

    Requires a logged-in Principal/Admin: the only consumer of this endpoint
    is the admin reports page (the teacher flows use
    /api/teacher/reports/attendance, students use /api/student/report).
    """
    if current_user["role"] != "admin":
        raise HTTPException(status_code=403, detail="Admin only")
    query = db.query(AttendanceRecord)
    if subject_id:
        query = query.filter(AttendanceRecord.subject_id == subject_id)
    if start_date:
        query = query.filter(AttendanceRecord.date >= start_date.date())
    if end_date:
        query = query.filter(AttendanceRecord.date <= end_date.date())
    
    records = query.all()
    
    # Organize by subject
    subject_data = {}
    for rec in records:
        sid = rec.student_id
        if sid not in subject_data:
            subject_data[sid] = []
        subject_data[sid].append({
            "date": rec.date.isoformat(),
            "status": rec.status,
            "confidence": rec.confidence
        })
    
    return {"records": dict(subject_data)}

# --- Student attendance report ---
import student_report


def _own_student(db, current_user):
    """Resolve the logged-in student's own profile row.

    The student id is always taken from the session, never from the request,
    so a student can only ever read their own report (no student_id param).
    """
    if current_user["role"] != "student":
        raise HTTPException(status_code=403, detail="Students only")
    student = db.query(Student).filter(Student.user_id == current_user["id"]).first()
    if not student:
        raise HTTPException(status_code=404, detail="Student profile not found")
    return student


def _student_report_payload(db, current_user, start_date, end_date):
    student = _own_student(db, current_user)
    start = start_date.date() if start_date else None
    end = end_date.date() if end_date else None
    if start and end and start > end:
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")
    try:
        return student_report.build_report(db, student, current_user, start, end)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@app.get("/api/student/report")
async def student_attendance_report(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """The logged-in student's own report: full profile, summary and history.

    No subject/semester scoping - it always covers every marked record of this
    student (optionally bounded by start_date/end_date, inclusive). Rows come
    back newest first and percentages follow the app-wide rule
    pct = present / marked * 100 with unknown/late kept in the denominator.
    """
    return _student_report_payload(db, current_user, start_date, end_date)


@app.get("/api/student/report/pdf")
async def student_attendance_report_pdf(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """Same report as /api/student/report, rendered as a downloadable PDF."""
    data = _student_report_payload(db, current_user, start_date, end_date)
    payload = student_report.render_pdf(data)
    filename = "attendance_report_%s.pdf" % (data["student"].get("usn") or data["student"]["id"])
    return Response(
        content=payload,
        media_type="application/pdf",
        headers={"Content-Disposition": 'attachment; filename="%s"' % filename},
    )


# --- Parent WhatsApp report delivery (teacher scope) ---
import parent_report_delivery as parent_delivery


def _teacher_scope_or_403(db, current_user):
    if current_user["role"] not in ("teacher", "hod"):
        raise HTTPException(status_code=403, detail="Teacher only")
    teacher = db.query(Teacher).filter(Teacher.user_id == current_user["id"]).first()
    if not teacher:
        raise HTTPException(status_code=404, detail="Teacher profile not found")
    return teacher


@app.get("/api/teacher/reports/notify/eligible")
async def parent_report_eligible(
    channel: str = "whatsapp",
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """Students this teacher may send a parent report for, and why each can or
    cannot be delivered right now on the chosen channel. Never returns a full
    phone number or a full email address."""
    if channel not in ("whatsapp", "email"):
        raise HTTPException(status_code=400, detail="channel must be 'whatsapp' or 'email'")
    teacher = _teacher_scope_or_403(db, current_user)
    students = parent_delivery.authorized_students(db, teacher)

    rows = []
    counts = {"total": 0, "deliverable": 0, "no_contact": 0, "unverified": 0, "invalid": 0}
    for s in students:
        contact = parent_delivery.student_contact(s, channel)
        reason = contact["reason"]
        counts["total"] += 1
        if contact["deliverable"]:
            counts["deliverable"] += 1
        elif reason in ("no_parent_contact", "no_valid_address"):
            counts["no_contact"] += 1
        elif reason == "unverified_contact":
            counts["unverified"] += 1
        else:
            counts["invalid"] += 1
        rows.append({
            "student_id": s.id,
            "usn": s.student_id,
            "name": s.name,
            "branch": s.branch,
            "semester": s.semester,
            "section": s.section,
            "phone_masked": contact["masked"],
            "recipient_masked": contact["masked"],
            "recipient_via": contact.get("via"),
            "verified": contact["verified"],
            "deliverable": contact["deliverable"],
            "block_reason": reason,
        })

    return {
        "channel": channel,
        "provider": parent_delivery.provider_status(),
        "channels": {
            "whatsapp": parent_delivery.provider_status(),
            "email": parent_delivery.email_status(),
        },
        "counts": counts,
        "students": rows,
    }


@app.post("/api/teacher/reports/notify")
async def parent_report_notify(
    payload: dict,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """Start a delivery batch for the teacher's own students only.

    ``student_ids`` selects individuals; omit it (or pass an empty list) for
    every authorized student. ``channel`` is ``whatsapp`` or ``email``. Each
    student's official report is built with the same ``student_report`` code
    the student portal uses, then delivered to that student's own verified
    contact - never anyone else's.
    """
    teacher = _teacher_scope_or_403(db, current_user)
    student_ids = payload.get("student_ids") or []
    if not isinstance(student_ids, list):
        raise HTTPException(status_code=400, detail="student_ids must be a list")
    channel = payload.get("channel") or "whatsapp"
    if channel not in ("whatsapp", "email"):
        raise HTTPException(status_code=400, detail="channel must be 'whatsapp' or 'email'")
    dry_run = bool(payload.get("dry_run"))
    try:
        start = datetime.fromisoformat(payload["start_date"]).date() if payload.get("start_date") else None
        end = datetime.fromisoformat(payload["end_date"]).date() if payload.get("end_date") else None
    except ValueError:
        raise HTTPException(status_code=400, detail="Dates must be YYYY-MM-DD")
    if start and end and start > end:
        raise HTTPException(status_code=400, detail="start_date must be on or before end_date")

    try:
        return parent_delivery.start_batch(
            db, teacher, current_user["id"], student_ids,
            start_date=start, end_date=end, dry_run=dry_run, channel=channel,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@app.get("/api/teacher/reports/notify/batch/{batch_id}")
async def parent_report_batch_status(
    batch_id: str,
    db = Depends(get_db),
    current_user = Depends(get_current_user),
):
    """Poll progress of a running batch. Only the teacher who started it."""
    _teacher_scope_or_403(db, current_user)
    batch = parent_delivery.get_batch(batch_id, current_user["id"])
    if not batch:
        raise HTTPException(status_code=404, detail="Batch not found")
    return batch


# --- Health check ---
@app.get("/api/health")
async def health():
    return {"status": "ok", "message": "Smart Attendance System API running"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=True)