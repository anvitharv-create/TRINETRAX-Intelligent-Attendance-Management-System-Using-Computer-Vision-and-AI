# TrinetraX — Intelligent Attendance Management System

An AI-powered attendance management system that uses computer vision and face recognition to simplify classroom attendance, maintain attendance records, and visualize student attendance analytics.

## About the Project

**TrinetraX** is a web-based attendance management system designed to reduce manual effort and make classroom attendance more efficient. It uses face detection and face recognition to identify enrolled students from classroom images. Teachers can review the recognition results, confirm attendance, and access attendance records and analytics.

## Key Features

- **AI-Based Face Detection:** Detects faces in classroom images using YuNet.
- **Face Recognition:** Uses SFace embeddings to match detected faces with enrolled students.
- **Unknown Face Identification:** Separates unrecognized faces for review.
- **Teacher Confirmation:** Allows teachers to review detected matches before saving attendance.
- **Attendance Management:** Stores and retrieves attendance records.
- **Dashboard and Analytics:** Visualizes attendance information using charts.
- **Authentication:** Supports password hashing and token-based API authorization.
- **Attendance History:** Provides access to saved attendance records.

## Tech Stack

| Category | Technologies |
|---|---|
| Programming Language | Python |
| Frontend | React, TypeScript |
| Build Tool | Vite |
| Styling | Tailwind CSS v4 |
| Backend | FastAPI |
| Face Detection | OpenCV, YuNet |
| Face Recognition | SFace |
| Model Runtime | ONNX Runtime |
| Database | SQLite |
| ORM | SQLAlchemy 2.0 |
| API Communication | Axios |
| Routing | React Router |
| Data Visualization | Recharts |
| Authentication | PBKDF2-SHA256, UUIDv4 bearer tokens |
| Testing | pytest, httpx |

## How It Works

1. The teacher opens the web application and captures a classroom image using the browser camera.
2. The frontend sends the image to the FastAPI backend.
3. OpenCV processes the image, and YuNet detects the faces.
4. SFace generates face embeddings for the detected faces.
5. The system compares these embeddings with stored student embeddings.
6. Recognized students and unknown faces are returned to the frontend.
7. The teacher reviews the results and confirms attendance.
8. Confirmed attendance records are saved in the SQLite database.
9. Attendance history and analytics are displayed through the application dashboard.

## System Architecture

```text
Teacher / Browser Camera
          |
          v
   React + TypeScript
          |
        Axios
          |
          v
     FastAPI Backend
          |
          v
  OpenCV + YuNet + SFace
          |
          v
  Face Embedding Matching
          |
          v
 Teacher Review & Confirmation
          |
          v
      SQLite Database
          |
          v
 Attendance Dashboard & Analytics
```

## Face Recognition Approach

TrinetraX uses a face detection and recognition pipeline:

- **YuNet:** Locates faces and facial landmarks in an image.
- **SFace:** Aligns detected faces and generates 128-dimensional face embeddings.
- **Embedding Matching:** Compares generated embeddings with enrolled student embeddings using cosine distance.
- **Matching Threshold:** A cosine distance of 0.45 or less is used as the matching criterion.
- **Teacher Review:** Recognition results are reviewed before attendance is confirmed.

The matching confidence displayed by the application is a derived score and should not be interpreted as a measured model accuracy percentage.

## Security

- Password hashing using PBKDF2-SHA256 with 50,000 iterations.
- UUIDv4 bearer tokens for authenticated API requests.
- Token session management using SQLite.
- Protected API access through authorization headers.

## Getting Started

### Prerequisites

Install the following before setting up the project:

- Python
- Node.js and npm
- Git

### Installation

**1. Clone the repository**

```bash
git clone <YOUR_GITHUB_REPOSITORY_URL>
cd <YOUR_PROJECT_DIRECTORY>
```

**2. Set up the backend**

```bash
cd backend
python -m venv .venv
```

Activate the virtual environment.

Windows:

```bash
.venv\Scripts\activate
```

Install the backend dependencies:

```bash
pip install -r requirements.txt
```

**3. Start the backend**

Use the FastAPI entry-point module configured in your project. For example, if it is `main.py`:

```bash
uvicorn main:app --reload
```

**4. Set up the frontend**

Open a separate terminal and navigate to the frontend directory:

```bash
cd frontend
npm install
npm run dev
```

> **Note:** Adjust the directory names, entry-point module, environment variables, and installation commands to match your repository. Ensure the required face-recognition model files and student embeddings are available before testing recognition.

## Future Enhancements

- Liveness detection to help prevent photo-based spoofing.
- Scalability improvements for larger classrooms.
- Production deployment with HTTPS and appropriate server configuration.
- Expanded evaluation using documented recognition accuracy and performance measurements.
- Additional notification integrations.

## Project Information

- **Project Name:** TrinetraX — Intelligent Attendance Management System Using Computer Vision and AI
- **Department:** Information Science and Engineering
- **Institution:** Amruta Institute of Engineering and Management Sciences (AIMS)
- **Academic Year:** 2025–2026

## Contributors

- Anvitha RV
- Megha B Patil
- Reshma Karegouda Mulimani
- Satvik K
- Shashank BG

## License

Choose and add an appropriate open-source license before distributing this project publicly.
